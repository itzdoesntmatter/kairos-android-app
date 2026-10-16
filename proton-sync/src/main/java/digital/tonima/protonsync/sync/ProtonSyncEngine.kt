package digital.tonima.protonsync.sync

import android.provider.CalendarContract
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.CalendarInfo
import digital.tonima.protonsync.calendar.ProtonCalendarWriter
import digital.tonima.protonsync.calendar.mapProtonNotification
import digital.tonima.protonsync.crypto.EventPartDecryptor
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.db.CalendarSyncStateEntity
import digital.tonima.protonsync.db.NativeEventMapEntity
import digital.tonima.protonsync.db.SyncDatabase
import digital.tonima.protonsync.db.SyncRunStatsEntity
import digital.tonima.protonsync.db.TombstoneEntity
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.status.CalendarSyncError
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import digital.tonima.protonsync.status.SyncCounters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import logcat.logcat
import retrofit2.HttpException
import java.time.ZoneId

/**
 * The Proton sync engine: fetch → decrypt → reconcile → CalendarProvider (architecture §3,
 * sync-algorithm §2). Pull-only, server-authoritative (D3): local edits to our rows are
 * overwritten by the next pull.
 *
 * Run structure (per account):
 * 1. Session + keyring precondition (no session → `needs_reauth`, never a retry storm).
 * 2. GET /calendar/v1; failure aborts the run (reference behaviour, sync-algorithm §9).
 * 3. Per calendar (invariant I4 — one failing calendar never aborts the others):
 *    a. calendar keys + passphrase unlock (lazy, cached in the keyring), settings (non-fatal);
 *    b. native calendar row reconcile (delete when the calendar vanished server-side);
 *    c. windowed three-Type event fetch with 90-day chunks, 400-halving, page walk, merge;
 *    d. decrypt + parse + ReconcileDiff + idempotent writer upsert;
 *    e. all-time /events/ids deletion authority → tombstones, read through a bounded and
 *       progress-checked walk (max 100 pages); an incomplete walk defers the pass and is
 *       surfaced instead of looping (security-audit N-2/C3);
 *    f. calendar-default reminders for events lacking them.
 *
 * Incremental vs full (sync-algorithm §3): no global resync flag exists; the window is the
 * knob — full = all-time [1970, 2037], incremental = bounded past/future days around the
 * last-sync cursor. The per-event refresh rule (`stored.lastModified < LastEditTime`) makes
 * every run incremental in effect.
 *
 * Event stream (calendar-api.md §5): deliberately NOT implemented in v1 — architecture §7
 * documents the core/v5 poller as the v2 upgrade path; server-side edits arrive at the next
 * periodic run (≥ 15 min).
 *
 * Retry/backoff: none inside the engine. 429/5xx are handled by the API interceptors
 * (Retry-After + capped exponential); a FAILURE result hands retry policy to the worker's
 * WorkManager backoff (architecture §8).
 */
class ProtonSyncEngine(
    private val accountName: String,
    private val api: ProtonApiClient,
    private val sessionManager: SessionManager,
    private val keyring: ProtonCryptoKeyring,
    private val decryptor: EventPartDecryptor,
    private val parser: IcalEventParser,
    private val writer: ProtonCalendarWriter,
    private val db: SyncDatabase,
    private val statusProvider: ProtonSyncStatusProvider,
    private val diff: ReconcileDiff = ReconcileDiff(),
) : SyncEngine {

    override suspend fun syncFull(): SyncResult = runSync(SyncWindow.FULL)

    override suspend fun syncIncremental(since: Long): SyncResult {
        // First run (no cursor) behaves as a full sync; afterwards the window is bounded by
        // the documented past/future defaults (sync-algorithm §3). [since] is epoch millis
        // (the worker's last-sync cursor); SyncWindow works in Unix seconds.
        val window =
            if (since <= 0L) {
                SyncWindow.FULL
            } else {
                SyncWindow.bounded(
                    nowSeconds = System.currentTimeMillis() / 1000L,
                    sinceSeconds = since / 1000L,
                )
            }
        return runSync(window)
    }

    private suspend fun runSync(window: SyncWindow): SyncResult {
        val startedAt = System.currentTimeMillis()
        if (sessionManager.currentRefreshToken() == null) {
            statusProvider.onNeedsReauth()
            return SyncResult.failure("no Proton session — the user must log in again")
        }
        if (!keyring.hasUserKey()) {
            // Process restart: rebuild the ring from keys.dat before the first API call.
            keyring.restore()
            if (!keyring.hasUserKey()) {
                statusProvider.onNeedsReauth()
                return SyncResult.failure("no unlocked key material — the user must log in again")
            }
        }

        statusProvider.onSyncStarted()
        val accumulator = RunAccumulator()

        val calendars =
            try {
                api.getCalendars()
            } catch (e: Exception) {
                accumulator.apiErrors++
                logcat(TAG, LogPriority.ERROR) { "calendar list fetch failed — aborting run: $e" }
                return finishRun(startedAt, accumulator, SyncResult.failure("calendar list fetch failed"), emptyList())
            }

        val calendarErrors = mutableListOf<CalendarSyncError>()
        val serverCalendarIds = calendars.calendars.map { it.id }.toSet()

        // Calendars that vanished server-side: delete the native calendar (cascades events,
        // architecture §3) and all its metadata.
        for (state in db.calendarSyncStateDao().byAccount(accountName)) {
            if (state.protonCalendarId !in serverCalendarIds) {
                deleteVanishedCalendar(state)
            }
        }

        for (calendar in calendars.calendars) {
            try {
                syncCalendar(calendar, window, accumulator)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                accumulator.apiErrors++
                logcat(TAG, LogPriority.ERROR) {
                    "calendar ${calendar.id} failed — skipping (partial sync, I4): $e"
                }
                calendarErrors += CalendarSyncError(calendar.id, safeError(e))
                db.calendarSyncStateDao().recordError(accountName, calendar.id, safeError(e))
            }
        }

        // finishRun decides success vs success-with-note from the per-calendar errors and the
        // walk warnings (security-audit N-2/C3).
        return finishRun(startedAt, accumulator, SyncResult.success(), calendarErrors + accumulator.calendarWarnings)
    }

    private suspend fun syncCalendar(
        calendar: CalendarInfo,
        window: SyncWindow,
        accumulator: RunAccumulator,
    ) {
        unlockCalendarKey(calendar)
        val settings = fetchSettings(calendar.id)
        val state = ensureCalendarRow(calendar)

        val merged = fetchEvents(calendar, window, accumulator)
        val localMap = db.nativeEventMapDao().byCalendar(accountName, calendar.id)
        val plan = diff.plan(merged.mapValues { it.value.lastEditTime ?: 0L }, localMap)
        val localById = localMap.associateBy { it.protonEventId }
        // The writer addresses NATIVE rows: use the native calendar id, not the proton one.
        val nativeCalendarId = state.androidCalendarId
            ?: throw IllegalStateException("calendar ${calendar.id} has no native row")

        for (eventId in plan.touched) {
            val detail = merged.getValue(eventId)
            val (data, decryptFailures, verifyFailures) = decryptToData(detail)
            accumulator.decryptFailures += decryptFailures
            accumulator.verifyFailures += verifyFailures
            if (data.isUndated) {
                // security-audit §3.5c / N-4: no sane date range survived (neither from the
                // payload nor from the server metadata) — skipping beats writing a fabricated
                // 1970 row. Counted with the decrypt failures so the loss is visible.
                accumulator.decryptFailures++
                logcat(TAG, LogPriority.WARN) {
                    "event ${detail.id}: no sane date range — skipping the event (§3.5c)"
                }
                continue
            }
            applyEvent(calendar.id, nativeCalendarId, detail, data, localById[eventId], accumulator)
        }

        applyDefaultReminders(nativeCalendarId, settings)

        // Deletion authority: the all-time ids pass. Any failure — or a walk that stopped early
        // (security-audit N-2 / release-gate C3) — defers the WHOLE pass to the next run: a
        // partial or repeated id set must never be treated as authoritative, or live events
        // would be tombstoned.
        val walk = walkEventIds(calendar.id)
        if (walk is EventIdWalk.Complete) {
            processDeletions(calendar.id, nativeCalendarId, walk.ids, accumulator)
        } else {
            val reason = (walk as EventIdWalk.Incomplete).reason
            logcat(TAG, LogPriority.WARN) { "ids walk for ${calendar.id} stopped early: $reason" }
            // Surfaced twice on purpose: in the run result the card renders, and in the
            // calendar's persisted state row.
            accumulator.calendarWarnings += CalendarSyncError(calendar.id, reason)
        }

        db.calendarSyncStateDao().markSuccess(accountName, calendar.id, System.currentTimeMillis())

        // Recorded after markSuccess (which clears lastError) so the degraded deletion pass
        // stays visible in the calendar's state until a later run walks the ids to the end.
        if (walk is EventIdWalk.Incomplete) {
            db.calendarSyncStateDao().recordError(accountName, calendar.id, walk.reason)
        }
    }

    // region per-calendar steps

    private suspend fun unlockCalendarKey(calendar: CalendarInfo) {
        if (keyring.calendarKey(calendar.id) != null) return
        val keys = api.getCalendarKeys(calendar.id).keys
        val calendarKey = keys.firstOrNull { it.isPrimary } ?: keys.firstOrNull()
            ?: throw IllegalStateException("calendar ${calendar.id} has no keys")
        val passphraseResponse = api.getCalendarPassphrase(calendar.id)
        val passphrases =
            passphraseResponse.passphrases +
                listOfNotNull(passphraseResponse.passphrase)
        val matching = passphrases.firstOrNull { it.id == calendarKey.passphraseId }
            ?: passphrases.firstOrNull()
            ?: throw IllegalStateException(
                "no calendar passphrase matches key ${calendarKey.id} (PassphraseID ${calendarKey.passphraseId})",
            )

        // The member passphrase is encrypted TO THE USER KEY (crypto-envelope.md §2.5):
        // decrypt with the user ring, then unlock the calendar key with it. A calendar may
        // carry several member passphrases (one per member's address-key generation) — try
        // each until one decrypts.
        var passphrase: String? = null
        for (memberPassphrase in matching.memberPassphrases) {
            passphrase = keyring.decryptArmoredWithRing(memberPassphrase.passphrase)
            if (passphrase != null) break
        }
        val finalPassphrase =
            passphrase ?: throw IllegalStateException("calendar ${calendar.id} passphrase undecryptable")
        keyring.addCalendarKey(calendar.id, calendarKey.privateKey, finalPassphrase.toByteArray(Charsets.UTF_8))
    }

    private suspend fun fetchSettings(calendarId: String): CalendarSettingsHandle {
        val response =
            try {
                api.getCalendarSettings(calendarId)
            } catch (e: Exception) {
                // Settings failure is non-fatal (sync-algorithm §9).
                logcat(TAG, LogPriority.WARN) { "settings fetch failed for $calendarId: $e" }
                return CalendarSettingsHandle.None
            }
        val settings = response.settings ?: return CalendarSettingsHandle.None
        val reminders =
            (settings.defaultPartDayNotifications + settings.defaultFullDayNotifications)
                .mapNotNull { mapProtonNotification(it) }
                .distinctBy { it.minutes to it.method }
        return if (reminders.isEmpty()) CalendarSettingsHandle.None else CalendarSettingsHandle.Reminders(reminders)
    }

    private suspend fun ensureCalendarRow(calendar: CalendarInfo): CalendarSyncStateEntity {
        val existing = db.calendarSyncStateDao().find(accountName, calendar.id)
        val color = parseProtonColor(calendar.color)
        val accessLevel = calendarAccessLevel(calendar)
        val androidCalendarId =
            writer.ensureCalendar(
                accountName = accountName,
                calendarName = calendar.id,
                displayName = calendar.name,
                color = color,
                accessLevel = accessLevel,
            )
        val state =
            CalendarSyncStateEntity(
                accountName = accountName,
                protonCalendarId = calendar.id,
                androidCalendarId = androidCalendarId,
                displayName = calendar.name,
                color = color ?: 0,
                lastSyncEpochMs = existing?.lastSyncEpochMs ?: 0L,
                lastError = existing?.lastError,
                errorCount = existing?.errorCount ?: 0,
            )
        db.calendarSyncStateDao().upsert(state)
        return state
    }

    /** architecture §3: 700 for personal calendars and editable members, 200 otherwise. */
    private fun calendarAccessLevel(calendar: CalendarInfo): Int {
        if (calendar.type == 0) return CalendarContract.Calendars.CAL_ACCESS_OWNER
        val ownMember = calendar.members.firstOrNull { it.email == accountName }
        val editable = ownMember != null && ownMember.permissions and 16 != 0
        return if (editable) CalendarContract.Calendars.CAL_ACCESS_OWNER
        else CalendarContract.Calendars.CAL_ACCESS_READ
    }

    private suspend fun fetchEvents(
        calendar: CalendarInfo,
        window: SyncWindow,
        accumulator: RunAccumulator,
    ): Map<String, CalendarEventDetail> =
        coroutineScope {
            // Three parallel Type buckets (0/1/2 = shared/calendar/personal part classes,
            // calendar-api.md §4.2); merged by event ID, first bucket wins.
            val buckets = (0..2).map { type -> async { fetchTypeBucket(calendar, window, type, accumulator) } }
            val merged = linkedMapOf<String, CalendarEventDetail>()
            for (bucket in buckets.awaitAll()) {
                for ((id, detail) in bucket) {
                    merged.putIfAbsent(id, detail)
                }
            }
            merged
        }

    private suspend fun fetchTypeBucket(
        calendar: CalendarInfo,
        window: SyncWindow,
        type: Int,
        accumulator: RunAccumulator,
    ): Map<String, CalendarEventDetail> {
        val result = linkedMapOf<String, CalendarEventDetail>()
        var chunkSeconds = CHUNK_SECONDS
        var chunkStart = window.startSeconds
        var halvings = 0
        while (chunkStart < window.endSeconds) {
            val chunkEnd = minOf(chunkStart + chunkSeconds, window.endSeconds)
            try {
                walkPages(calendar.id, type, chunkStart, chunkEnd) { events ->
                    for (event in events) result.putIfAbsent(event.id, event)
                }
                chunkStart = chunkEnd
            } catch (e: HttpException) {
                val body = e.response()?.errorBody()?.string().orEmpty()
                if (e.code() == 400 && body.contains("window", ignoreCase = true) && halvings < MAX_HALVINGS) {
                    // Reference behaviour: a too-big window halves the chunk, shared across
                    // all Type buckets (calendar-api.md §4.2).
                    chunkSeconds = (chunkSeconds / 2).coerceAtLeast(MIN_CHUNK_SECONDS)
                    halvings++
                    logcat(TAG, LogPriority.WARN) {
                        "window too big for ${calendar.id} type $type — reducing chunk to ${chunkSeconds}s"
                    }
                } else {
                    throw e
                }
            }
        }
        return result
    }

    private suspend fun walkPages(
        calendarId: String,
        type: Int,
        startSeconds: Long,
        endSeconds: Long,
        onPage: (List<CalendarEventDetail>) -> Unit,
    ) {
        var page = 1
        while (page <= MAX_PAGES) {
            val response =
                api.getEvents(
                    calendarId = calendarId,
                    page = page,
                    pageSize = PAGE_SIZE,
                    start = startSeconds,
                    end = endSeconds,
                    timezone = ZoneId.systemDefault().id,
                    type = type,
                )
            onPage(response.events)
            if (response.events.size < PAGE_SIZE) return
            page++
        }
        logcat(TAG, LogPriority.WARN) { "page cap ($MAX_PAGES) hit for $calendarId type $type" }
    }

    /**
     * The all-time id walk — the deletion authority (sync-algorithm §7). Bounded and
     * progress-checked (security-audit N-2, release-gate C3): a server that keeps answering
     * with a full page whose tail never moves (or that cycles ids) used to spin this loop
     * forever, draining battery and network until WorkManager killed the run and leaving the
     * deletion pass permanently unfinished.
     *
     * Two independent stops: at most [MAX_IDS_PAGES] pages are requested (100 ids per page →
     * 10 000 ids per calendar, far above any real calendar while still bounding a hostile
     * one), and the walk ends as soon as it stops making progress — the cursor not advancing
     * or a full page contributing no new ids. Both produce [EventIdWalk.Incomplete], which the
     * caller treats exactly like a failed fetch: the deletion pass is deferred to the next run
     * (never run against a partial set) and the reason is surfaced in the run state.
     * Transport failures are folded into the same result so the caller's deferral logic is the
     * single place that decides what an incomplete id set means.
     */
    private suspend fun walkEventIds(calendarId: String): EventIdWalk {
        val ids = linkedSetOf<String>()
        var afterId: String? = null
        var pages = 0
        var previousSize = 0
        while (pages < MAX_IDS_PAGES) {
            val response =
                try {
                    api.getEventIds(calendarId = calendarId, limit = IDS_PAGE_LIMIT, afterId = afterId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return EventIdWalk.Incomplete("ids fetch failed at page ${pages + 1}: ${safeError(e)}")
                }
            ids += response.ids
            if (response.ids.size < IDS_PAGE_LIMIT) return EventIdWalk.Complete(ids)
            val next = response.ids.lastOrNull()
            if (next == null || next == afterId || ids.size == previousSize) {
                return EventIdWalk.Incomplete(
                    "ids walk stopped making progress at page ${pages + 1} " +
                        "(afterId=$next, ${ids.size} ids seen) — deletion pass deferred",
                )
            }
            previousSize = ids.size
            afterId = next
            pages++
        }
        return EventIdWalk.Incomplete(
            "ids walk hit the $MAX_IDS_PAGES-page cap (${ids.size} ids seen) — deletion pass deferred",
        )
    }

    private suspend fun decryptToData(
        detail: CalendarEventDetail,
    ): Triple<digital.tonima.protonsync.calendar.ProtonEventData, Int, Int> {
        val outcome = decryptor.decrypt(detail)
        val data =
            if (outcome.icalText == null) {
                parser.placeholder(detail, accountName)
            } else {
                parser.parse(outcome.icalText, detail, accountName)
            }
        // Size-cap rejections (security-audit §3.5b / N-1) are counted with the decrypt
        // failures: both mean "content the server sent did not become a native event", and the
        // persisted counter is the surface the settings card already shows. The distinct WARN
        // log above names the actual reason.
        return Triple(data, outcome.decryptFailures + outcome.payloadRejections, outcome.verifyFailures)
    }

    private suspend fun applyEvent(
        protonCalendarId: String,
        nativeCalendarId: Long,
        detail: CalendarEventDetail,
        data: digital.tonima.protonsync.calendar.ProtonEventData,
        existing: NativeEventMapEntity?,
        accumulator: RunAccumulator,
    ) {
        try {
            val androidEventId = writer.upsertEvent(accountName, nativeCalendarId, data)
            db.nativeEventMapDao().upsert(
                listOf(
                    NativeEventMapEntity(
                        accountName = accountName,
                        protonCalendarId = protonCalendarId,
                        protonEventId = detail.id,
                        androidEventId = androidEventId,
                        lastModified = detail.lastEditTime ?: 0L,
                        isException = data.originalSyncId != null,
                        parentProtonEventId = data.originalSyncId,
                    ),
                ),
            )
            accumulator.eventsSynced++
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // architecture §8: a bad RRULE combo must not abort the run — degrade to a
            // single instance and retry once.
            if (data.recurrenceRule != null || data.exceptions.isNotEmpty()) {
                val singleInstance =
                    data.copy(
                        recurrenceRule = null,
                        recurrenceDates = null,
                        exceptionRule = null,
                        exceptions = emptyList(),
                    )
                try {
                    val androidEventId = writer.upsertEvent(accountName, nativeCalendarId, singleInstance)
                    db.nativeEventMapDao().upsert(
                        listOf(
                            NativeEventMapEntity(
                                accountName = accountName,
                                protonCalendarId = protonCalendarId,
                                protonEventId = detail.id,
                                androidEventId = androidEventId,
                                lastModified = detail.lastEditTime ?: 0L,
                                isException = false,
                                parentProtonEventId = null,
                            ),
                        ),
                    )
                    accumulator.eventsSynced++
                    return
                } catch (retry: Exception) {
                    logcat(TAG, LogPriority.ERROR) {
                        "event ${detail.id} write failed twice — recording and skipping: $retry"
                    }
                }
            } else {
                logcat(TAG, LogPriority.ERROR) { "event ${detail.id} write failed — skipping: $e" }
            }
            // Prevent a poisoned event from failing every run: record the server version so
            // the refresh rule skips it until the server edits it again (S-4 rationale).
            db.nativeEventMapDao().upsert(
                listOf(
                    NativeEventMapEntity(
                        accountName = accountName,
                        protonCalendarId = protonCalendarId,
                        protonEventId = detail.id,
                        androidEventId = existing?.androidEventId,
                        lastModified = detail.lastEditTime ?: 0L,
                        isException = data.originalSyncId != null,
                        parentProtonEventId = data.originalSyncId,
                    ),
                ),
            )
        }
    }

    private suspend fun applyDefaultReminders(
        nativeCalendarId: Long,
        settings: CalendarSettingsHandle,
    ) {
        if (settings is CalendarSettingsHandle.Reminders) {
            writer.applyDefaultReminders(accountName, nativeCalendarId, settings.reminders)
        }
    }

    private suspend fun processDeletions(
        calendarId: String,
        nativeCalendarId: Long,
        serverIds: Set<String>,
        accumulator: RunAccumulator,
    ) {
        val localMap = db.nativeEventMapDao().byCalendar(accountName, calendarId)
        val tombstoneDao = db.tombstoneDao()

        for (local in localMap) {
            if (local.protonEventId in serverIds) {
                // The event still exists server-side: any stale tombstone is dropped.
                tombstoneDao.delete(accountName, calendarId, local.protonEventId)
                continue
            }
            val tombstone = tombstoneDao.byCalendar(accountName, calendarId)
                .firstOrNull { it.protonEventId == local.protonEventId }
            if (tombstone == null) {
                tombstoneDao.upsert(
                    TombstoneEntity(
                        accountName = accountName,
                        protonCalendarId = calendarId,
                        protonEventId = local.protonEventId,
                        successfulRunsSince = 0,
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                continue
            }
            val runs = tombstone.successfulRunsSince + 1
            if (runs >= TombstoneEntity.SUCCESSFUL_RUNS_REQUIRED) {
                // After 2 successful runs the absence is authoritative: delete the native
                // rows (master + exceptions + instances), the map entry and the tombstone
                // (architecture §3).
                if (local.androidEventId != null) {
                    writer.deleteEvent(accountName, nativeCalendarId, local.protonEventId)
                }
                db.nativeEventMapDao().delete(accountName, calendarId, local.protonEventId)
                tombstoneDao.delete(accountName, calendarId, local.protonEventId)
                accumulator.eventsDeleted++
            } else {
                tombstoneDao.upsert(tombstone.copy(successfulRunsSince = runs))
            }
        }
    }

    private suspend fun deleteVanishedCalendar(state: CalendarSyncStateEntity) {
        logcat(TAG, LogPriority.INFO) {
            "calendar ${state.protonCalendarId} vanished server-side — deleting the native calendar"
        }
        if (state.androidCalendarId != null) {
            writer.deleteCalendar(accountName, state.androidCalendarId)
        }
        db.nativeEventMapDao().deleteAllForCalendar(accountName, state.protonCalendarId)
        db.tombstoneDao().deleteAllForCalendar(accountName, state.protonCalendarId)
        db.calendarSyncStateDao().deleteForCalendar(accountName, state.protonCalendarId)
    }

    // endregion

    private suspend fun finishRun(
        startedAt: Long,
        accumulator: RunAccumulator,
        result: SyncResult,
        calendarErrors: List<CalendarSyncError>,
    ): SyncResult {
        val counters =
            SyncCounters(
                eventsSynced = accumulator.eventsSynced,
                eventsDeleted = accumulator.eventsDeleted,
                decryptFailures = accumulator.decryptFailures,
                verifyFailures = accumulator.verifyFailures,
                apiErrors = accumulator.apiErrors,
                lastRunDurationMs = System.currentTimeMillis() - startedAt,
            )
        val finalResult =
            when {
                result.status == SyncResult.Status.FAILURE -> result
                calendarErrors.isNotEmpty() -> SyncResult.successWithNote(
                    // "reported a problem" rather than "failed": the list also carries the
                    // walk warnings of C3, where the run itself completed.
                    "${calendarErrors.size} calendar(s) reported a problem; the rest synced",
                )
                else -> SyncResult.success(
                    eventsSynced = counters.eventsSynced,
                    eventsDeleted = counters.eventsDeleted,
                )
            }
        if (finalResult.status != SyncResult.Status.FAILURE) {
            val previous = db.syncRunStatsDao().find(accountName)
            db.syncRunStatsDao().upsert(
                SyncRunStatsEntity(
                    accountName = accountName,
                    eventsSynced = (previous?.eventsSynced ?: 0) + counters.eventsSynced,
                    eventsDeleted = (previous?.eventsDeleted ?: 0) + counters.eventsDeleted,
                    decryptFailures = (previous?.decryptFailures ?: 0) + counters.decryptFailures,
                    verifyFailures = (previous?.verifyFailures ?: 0) + counters.verifyFailures,
                    apiErrors = (previous?.apiErrors ?: 0) + counters.apiErrors,
                    lastRunDurationMs = counters.lastRunDurationMs,
                    lastSuccessEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        statusProvider.onSyncCompleted(
            lastError =
                when {
                    finalResult.status == SyncResult.Status.FAILURE -> finalResult.note
                    calendarErrors.isNotEmpty() -> calendarErrors.first().message
                    else -> null
                },
            counters = counters,
            calendarErrors = calendarErrors,
        )
        return finalResult
    }

    private fun parseProtonColor(color: String?): Int? =
        color?.removePrefix("#")?.toIntOrNull(16)?.let { rgb -> (0xFF shl 24) or rgb }

    private fun safeError(e: Exception): String = e.message ?: e::class.simpleName ?: "unknown error"

    private class RunAccumulator {
        var eventsSynced = 0
        var eventsDeleted = 0
        var decryptFailures = 0
        var verifyFailures = 0
        var apiErrors = 0

        /**
         * Per-calendar degradations that are not failures — a run that synced everything it
         * fetched but could not complete the deletion pass (security-audit N-2/C3). Surfaced
         * through the same channel as per-calendar failures so the settings card renders them.
         */
        val calendarWarnings = mutableListOf<CalendarSyncError>()
    }

    /**
     * Outcome of the all-time id walk (security-audit N-2, release-gate C3). [Incomplete] is
     * deliberately not a partial [Complete]: the deletion pass is only safe to run against a
     * set the server actually finished enumerating.
     */
    private sealed interface EventIdWalk {
        data class Complete(val ids: Set<String>) : EventIdWalk

        /** [reason] is user-safe (no payload data) and is what the card renders. */
        data class Incomplete(val reason: String) : EventIdWalk
    }

    private sealed interface CalendarSettingsHandle {
        data object None : CalendarSettingsHandle

        data class Reminders(val reminders: List<digital.tonima.protonsync.calendar.Reminder>) :
            CalendarSettingsHandle
    }

    /**
     * The fetch window (sync-algorithm §4.2): FULL = the reference's "all" mode
     * [1970-01-01, 2037-12-31] in Unix seconds; bounded = past/future days around now,
     * extended back to the last-sync cursor so nothing changed since is missed.
     */
    data class SyncWindow(
        val startSeconds: Long,
        val endSeconds: Long,
    ) {
        companion object {
            val FULL = SyncWindow(0L, 2_145_916_799L)

            private const val PAST_DAYS = 180L
            private const val FUTURE_DAYS = 365L

            fun bounded(
                nowSeconds: Long,
                sinceSeconds: Long,
            ): SyncWindow =
                SyncWindow(
                    startSeconds = maxOf(nowSeconds - PAST_DAYS * 86_400L, sinceSeconds - 3_600L),
                    endSeconds = nowSeconds + FUTURE_DAYS * 86_400L,
                )
        }
    }

    companion object {
        private const val TAG = "ProtonSyncEngine"

        /** 90-day chunks, the reference's window unit (calendar-api.md §4.2). */
        private const val CHUNK_SECONDS = 7_776_000L
        private const val MIN_CHUNK_SECONDS = 86_400L
        private const val MAX_HALVINGS = 6

        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 200
        private const val IDS_PAGE_LIMIT = 100

        /**
         * Page ceiling for the all-time id walk (security-audit N-2 / C3): 100 ids per page ×
         * [MAX_IDS_PAGES] pages = 10 000 events per calendar. The reference's own ids walk has
         * no bound; every real calendar is orders of magnitude under this, so the cap only ever
         * fires for a hostile or broken server — where the run must stop instead of looping.
         */
        private const val MAX_IDS_PAGES = 100
    }
}
