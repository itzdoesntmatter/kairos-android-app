package digital.tonima.protonsync.calendar

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.util.Log
import digital.tonima.protonsync.account.ProtonSyncAccount
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Mirrors Proton Calendar data into Android's native CalendarProvider, under the Kairos Proton
 * account owned by [ProtonSyncAccount].
 *
 * Contract (architecture spec §3, invariants I1–I3):
 * - Every write carries CALLER_IS_SYNCADAPTER=true + ACCOUNT_NAME + ACCOUNT_TYPE (I1), so the
 *   provider never marks our rows dirty and never tries to re-sync them.
 * - Row identity is SYNC_DATA1 = Proton event ID (I2): reconciliation never matches on
 *   title/time, which is what makes upserts idempotent across sync runs.
 * - Deletes touch Events, Instances AND Attendees explicitly (I3): some providers do not
 *   cascade from Events.
 * - Everything is idempotent: repeated calls with the same input converge on the same provider
 *   state (existing-row update vs insert, diff-skip when nothing changed, deletes of absent
 *   rows are no-ops).
 *
 * Recurrence (sync-algorithm §6.2-6.3): a recurring master row carries RRULE + DURATION (not
 * DTEND, so instances expand correctly); deleted instances (EXDATE) become STATUS_CANCELED
 * exception rows with ORIGINAL_SYNC_ID = master id; modified instances arrive as separate
 * event rows with ORIGINAL_SYNC_ID + ORIGINAL_INSTANCE_TIME set by the caller via
 * [ProtonEventData.originalSyncId].
 */
class NativeCalendarWriter(context: Context) : ProtonCalendarWriter {
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    // region Calendar rows

    override fun ensureCalendar(
        accountName: String,
        calendarName: String,
        displayName: String,
        color: Int?,
        accessLevel: Int,
    ): Long {
        checkPermission()
        val existing = queryCalendars(accountName, calendarName).firstOrNull()
        if (existing != null) {
            // CALENDAR_MUTABLE_COLUMNS deliberately excludes VISIBLE/SYNC_EVENTS/
            // CALENDAR_TIME_ZONE: those are the user's per-calendar choices in the Calendar
            // app and are set on insert only (security-audit S-9).
            val changed =
                diff(
                    existing.values,
                    calendarValues(displayName, color, accountName, accessLevel),
                    CALENDAR_MUTABLE_COLUMNS,
                    putNullOnAbsent = false,
                )
            if (changed.size() > 0) {
                resolver.update(calendarUri(existing.id, accountName), changed, null, null)
            }
            return existing.id
        }

        val insertValues =
            calendarValues(displayName, color, accountName, accessLevel).apply {
                put(Calendars.ACCOUNT_NAME, accountName)
                put(Calendars.ACCOUNT_TYPE, ProtonSyncAccount.ACCOUNT_TYPE)
                put(Calendars.NAME, calendarName)
            }
        val uri =
            resolver.insert(asSyncAdapter(Calendars.CONTENT_URI, accountName), insertValues)
                ?: throw IllegalStateException(
                    "CalendarProvider refused to create the Proton calendar '$calendarName' for $accountName",
                )
        return ContentUris.parseId(uri)
    }

    override fun deleteCalendar(
        accountName: String,
        calendarId: Long,
    ): Boolean {
        checkPermission()
        val calendarExisted =
            query(
                Calendars.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                "${Calendars._ID}=? AND ${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
                arrayOf(calendarId.toString(), accountName, ProtonSyncAccount.ACCOUNT_TYPE),
            ).isNotEmpty()
        // security-audit S-3: a calendar row that is not ours (or already gone) must never
        // sweep events — deleting a foreign calendar's events would be a data-loss bug.
        if (!calendarExisted) return false
        val eventIds = eventIdsInCalendar(calendarId)
        val ops = arrayListOf<ContentProviderOperation>()
        eventIds.forEach { ops += eventCleanupOps(it, accountName) }
        ops +=
            ContentProviderOperation
                .newDelete(calendarUri(calendarId, accountName))
                .build()
        if (ops.isNotEmpty()) {
            resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        }
        return true
    }

    /**
     * Full-account deletion: removes every calendar row (and their events) owned by
     * [accountName]. Belt-and-braces for logout — the primary strategy remains
     * AccountManager.removeAccountExplicitly, which makes the provider purge our rows itself.
     */
    override fun deleteCalendarsForAccount(accountName: String): List<Long> {
        checkPermission()
        val calendarIds =
            query(
                Calendars.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
                arrayOf(accountName, ProtonSyncAccount.ACCOUNT_TYPE),
            ).map { it.id }
        calendarIds.forEach { deleteCalendar(accountName, it) }
        return calendarIds
    }

    // endregion

    // region Events

    override fun upsertEvent(
        accountName: String,
        calendarId: Long,
        data: ProtonEventData,
    ): Long {
        checkPermission()
        val recurrenceRule = validateRecurrenceRule(data.recurrenceRule)
        // findEvent first: it may disable UID_2445 for the session, and eventValues must see
        // that flag before building the insert/update payload.
        val existing = findEvent(calendarId, data.protonEventId)
        val newValues = eventValues(calendarId, data, recurrenceRule)

        if (existing == null) {
            val ops =
                arrayListOf(
                    ContentProviderOperation
                        .newInsert(asSyncAdapter(Events.CONTENT_URI, accountName))
                        .withValues(newValues)
                        .build(),
                )
            data.attendees.forEach { ops += attendeeInsertWithBackReference(it, 0, accountName) }
            data.reminders.forEach { ops += reminderInsertWithBackReference(it, 0, accountName) }
            val result = resolver.applyBatch(CalendarContract.AUTHORITY, ops).first()
            val eventId =
                result.uri?.let { ContentUris.parseId(it) }
                    ?: findEvent(calendarId, data.protonEventId)?.id
                    ?: throw IllegalStateException(
                        "CalendarProvider accepted the insert of Proton event ${data.protonEventId} " +
                            "but the row cannot be located afterwards",
                    )
            reconcileExceptionRows(accountName, calendarId, eventId, data)
            return eventId
        }

        val (eventId, row) = existing
        val ops = arrayListOf<ContentProviderOperation>()
        val changed = diff(row, newValues, eventMutableColumns(), putNullOnAbsent = true)
        if (changed.size() > 0) {
            ops +=
                ContentProviderOperation
                    .newUpdate(eventUri(eventId, accountName))
                    .withValues(changed)
                    .build()
        }
        if (attendeesChanged(eventId, data.attendees)) {
            ops +=
                ContentProviderOperation
                    .newDelete(eventAttendeesUri(eventId, accountName))
                    .build()
            data.attendees.forEach { ops += attendeeInsert(it, eventId, accountName) }
        }
        if (remindersChanged(eventId, data.reminders)) {
            ops +=
                ContentProviderOperation
                    .newDelete(eventRemindersUri(eventId, accountName))
                    .build()
            data.reminders.forEach { ops += reminderInsert(it, eventId, accountName) }
        }
        if (ops.isNotEmpty()) {
            resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        }
        reconcileExceptionRows(accountName, calendarId, eventId, data)
        return eventId
    }

    /**
     * Deletes one Proton event's native rows — the master, its EXDATE exception rows and any
     * modified-instance rows (matched on ORIGINAL_SYNC_ID, sync-algorithm §7). Touches
     * Events + Instances + Attendees + Reminders (I3). Deleting an absent event is a no-op
     * returning false.
     */
    override fun deleteEvent(
        accountName: String,
        calendarId: Long,
        protonEventId: String,
    ): Boolean {
        checkPermission()
        val eventIds =
            query(
                Events.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                "(${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA1}=? OR ${Events.ORIGINAL_SYNC_ID}=?))",
                arrayOf(calendarId.toString(), protonEventId, protonEventId),
            ).map { it.id }
        if (eventIds.isEmpty()) return false

        val ops = arrayListOf<ContentProviderOperation>()
        eventIds.forEach { ops += eventCleanupOps(it, accountName) }
        resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        return true
    }

    override fun applyDefaultReminders(
        accountName: String,
        calendarId: Long,
        reminders: List<Reminder>,
    ) {
        checkPermission()
        if (reminders.isEmpty()) return
        val eventIds = eventIdsInCalendar(calendarId)
        val withReminders = eventIdsWithReminders(eventIds)
        val missing = eventIds - withReminders
        if (missing.isEmpty()) return
        val ops = arrayListOf<ContentProviderOperation>()
        missing.forEach { eventId ->
            reminders.forEach { reminder ->
                ops += reminderInsert(reminder, eventId, accountName)
            }
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, ops)
    }

    // endregion

    // region Mapping

    private fun calendarValues(
        displayName: String,
        color: Int?,
        accountName: String,
        accessLevel: Int,
    ): ContentValues =
        ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName.ifBlank { FALLBACK_DISPLAY_NAME })
            color?.let { put(Calendars.CALENDAR_COLOR, it) }
            put(Calendars.OWNER_ACCOUNT, accountName)
            put(Calendars.CALENDAR_ACCESS_LEVEL, accessLevel)
            // Insert-only columns (S-9): re-asserting them on update would overwrite the
            // user's visibility/timezone choices in the Calendar app.
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }

    private fun eventValues(
        calendarId: Long,
        data: ProtonEventData,
        recurrenceRule: String?,
    ): ContentValues =
        ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, data.title.ifBlank { UNTITLED_EVENT_TITLE })
            put(Events.DTSTART, data.startMillis)
            if (recurrenceRule == null) {
                put(Events.DTEND, data.endMillis)
            } else {
                // RFC 2445: a recurring master row uses DURATION, not DTEND, so instances
                // expand correctly in the provider. DST-safe per security-audit S-5.
                put(Events.RRULE, recurrenceRule)
                put(Events.DURATION, recurringDuration(data))
            }
            data.recurrenceDates?.let { put(Events.RDATE, it) }
            data.exceptionRule?.let { put(Events.EXRULE, it) }
            put(Events.ALL_DAY, if (data.isAllDay) 1 else 0)
            put(
                Events.EVENT_TIMEZONE,
                if (data.isAllDay) UTC_TIME_ZONE else data.timezone.ifBlank { ZoneId.systemDefault().id },
            )
            put(Events.STATUS, data.status)
            put(
                Events.AVAILABILITY,
                if (data.isFree) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY,
            )
            put(Events.SELF_ATTENDEE_STATUS, data.selfAttendeeStatus)
            put(Events.SYNC_DATA1, data.protonEventId)
            if (supportsUid2445) {
                put(Events.UID_2445, data.icalUid ?: data.protonEventId)
            }
            data.description?.let { put(Events.DESCRIPTION, it) }
            data.location?.let { put(Events.EVENT_LOCATION, it) }
            // Modified instance of a recurring master (sync-algorithm §6.2/§6.3): the row
            // references its master by proton event id + RECURRENCE-ID instant.
            data.originalSyncId?.let { originalSyncId ->
                put(Events.ORIGINAL_SYNC_ID, originalSyncId)
                put(Events.ORIGINAL_INSTANCE_TIME, data.originalInstanceMillis)
                put(Events.ORIGINAL_ALL_DAY, if (data.originalAllDay) 1 else 0)
            }
        }

    /**
     * Deleted instances (EXDATE) of a recurring master: one STATUS_CANCELED exception row per
     * exclusion, matched to the master by ORIGINAL_SYNC_ID (sync-algorithm §6.3). Stale
     * exception rows (instances the server un-deleted) are removed; new exclusions are
     * inserted. Runs for masters only — instance rows never carry their own exceptions.
     */
    private fun reconcileExceptionRows(
        accountName: String,
        calendarId: Long,
        masterId: Long,
        data: ProtonEventData,
    ) {
        if (data.originalSyncId != null) return
        val desired = data.exceptions.map { it.startMillis }.toSet()
        val existingRows =
            query(
                Events.CONTENT_URI,
                arrayOf(BaseColumns._ID, Events.ORIGINAL_INSTANCE_TIME),
                "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=? AND ${Events.ORIGINAL_SYNC_ID}=?",
                arrayOf(calendarId.toString(), data.protonEventId, data.protonEventId),
            )
        val ops = arrayListOf<ContentProviderOperation>()
        val seen = mutableSetOf<Long>()
        for (existingRow in existingRows) {
            val instanceTime = existingRow.values[Events.ORIGINAL_INSTANCE_TIME]?.toLongOrNull()
            if (instanceTime == null || instanceTime !in desired) {
                ops +=
                    ContentProviderOperation
                        .newDelete(eventUri(existingRow.id, accountName))
                        .build()
            } else {
                seen += instanceTime
            }
        }
        data.exceptions.filter { it.startMillis !in seen }.forEach { exception ->
            ops +=
                ContentProviderOperation
                    .newInsert(asSyncAdapter(Events.CONTENT_URI, accountName))
                    .withValues(
                        exceptionRowValues(
                            calendarId = calendarId,
                            masterId = masterId,
                            master = data,
                            exception = exception,
                        ),
                    )
                    .build()
        }
        if (ops.isNotEmpty()) {
            resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        }
    }

    private fun exceptionRowValues(
        calendarId: Long,
        masterId: Long,
        master: ProtonEventData,
        exception: EventException,
    ): ContentValues =
        ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, master.title.ifBlank { UNTITLED_EVENT_TITLE })
            put(Events.DTSTART, exception.startMillis)
            put(Events.STATUS, Events.STATUS_CANCELED)
            put(Events.ALL_DAY, if (exception.isAllDay || master.isAllDay) 1 else 0)
            put(
                Events.EVENT_TIMEZONE,
                if (exception.isAllDay || master.isAllDay) UTC_TIME_ZONE
                else master.timezone.ifBlank { ZoneId.systemDefault().id },
            )
            put(Events.SYNC_DATA1, master.protonEventId)
            put(Events.ORIGINAL_SYNC_ID, master.protonEventId)
            put(Events.ORIGINAL_INSTANCE_TIME, exception.startMillis)
            put(Events.ORIGINAL_ALL_DAY, if (exception.isAllDay) 1 else 0)
            put(Events.ORIGINAL_ID, masterId)
            val length = (master.endMillis - master.startMillis)
                .coerceAtLeast(if (master.isAllDay) 86_400_000L else 3_600_000L)
            if (master.recurrenceRule != null) {
                put(Events.DURATION, recurringDuration(master))
            } else {
                put(Events.DTEND, exception.startMillis + length)
            }
        }

    private fun attendeeValues(attendee: Attendee): ContentValues =
        ContentValues().apply {
            put(Attendees.ATTENDEE_EMAIL, attendee.email)
            put(Attendees.ATTENDEE_STATUS, attendee.status)
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
        }

    private fun reminderValues(reminder: Reminder): ContentValues =
        ContentValues().apply {
            put(Reminders.MINUTES, reminder.minutes)
            put(Reminders.METHOD, reminder.method)
        }

    /**
     * Structural RRULE gate (security-audit S-4): rules must parse as a real RFC 5545
     * recurrence (ical4j [RruleValidator]) — a garbage `FREQ=`-prefixed string would fail
     * the whole provider insert. A rejected rule degrades the event to a single instance
     * (architecture §3); it never throws mid-transaction.
     */
    private fun validateRecurrenceRule(raw: String?): String? {
        val rule = RruleValidator.validate(raw)
        if (raw != null && rule == null) {
            Log.w(TAG, "Dropping malformed RRULE '$raw'; event synced as single instance")
        }
        return rule
    }

    /**
     * RFC 2445 dur-value for recurring masters, DST-safe (security-audit S-5): all-day
     * events use whole days computed with calendar-day arithmetic (`P<n>D`); timed events
     * use the exact duration between the ZonedDateTime pair (`P<n>S`).
     */
    private fun recurringDuration(data: ProtonEventData): String {
        if (data.isAllDay) {
            val startDate =
                ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(data.startMillis),
                    ZoneId.of(UTC_TIME_ZONE),
                ).toLocalDate()
            val endDate =
                ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(data.endMillis),
                    ZoneId.of(UTC_TIME_ZONE),
                ).toLocalDate()
            val days = ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(1L)
            return "P${days}D"
        }
        val zone = ZoneId.of(data.timezone.ifBlank { ZoneId.systemDefault().id })
        val seconds =
            Duration.between(
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(data.startMillis), zone),
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(data.endMillis), zone),
            ).seconds.coerceAtLeast(0L)
        return "P${seconds}S"
    }

    // endregion

    // region Provider access

    private fun checkPermission() {
        val missing =
            CalendarPermissionException.REQUIRED_PERMISSIONS.filterNot {
                appContext.checkSelfPermission(it) == PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            throw CalendarPermissionException(missing.toSet())
        }
    }

    private fun findEvent(
        calendarId: Long,
        protonEventId: String,
    ): Row? {
        try {
            return queryEventRow(calendarId, protonEventId)
        } catch (e: IllegalArgumentException) {
            // Mirrors CalendarRepositoryImpl's "event_type" defensive pattern: some OEM
            // providers reject the API-35 UID_2445 column outright. Disable it for the
            // session and retry without it.
            if (!supportsUid2445) {
                throw e
            }
            supportsUid2445 = false
            Log.w(TAG, "CalendarProvider rejected the UID_2445 column; disabled for this session. ${e.message}")
            return queryEventRow(calendarId, protonEventId)
        }
    }

    private fun queryEventRow(
        calendarId: Long,
        protonEventId: String,
    ): Row? =
        query(
            Events.CONTENT_URI,
            arrayOf(BaseColumns._ID) + eventMutableColumns().toTypedArray(),
            "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=?",
            arrayOf(calendarId.toString(), protonEventId),
        ).firstOrNull()

    private fun eventIdsInCalendar(calendarId: Long): List<Long> =
        query(
            Events.CONTENT_URI,
            arrayOf(BaseColumns._ID),
            "${Events.CALENDAR_ID}=?",
            arrayOf(calendarId.toString()),
        ).map { it.id }

    private fun eventIdsWithReminders(eventIds: List<Long>): Set<Long> {
        if (eventIds.isEmpty()) return emptySet()
        val withReminders = mutableSetOf<Long>()
        eventIds.chunked(MAX_QUERY_IDS).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            query(
                Reminders.CONTENT_URI,
                arrayOf(Reminders.EVENT_ID),
                "${Reminders.EVENT_ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
            ).forEach { row ->
                row.values[Reminders.EVENT_ID]?.toLongOrNull()?.let { withReminders += it }
            }
        }
        return withReminders
    }

    private fun queryCalendars(
        accountName: String,
        calendarName: String,
    ): List<Row> =
        query(
            Calendars.CONTENT_URI,
            arrayOf(BaseColumns._ID) + CALENDAR_MUTABLE_COLUMNS.toTypedArray(),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
            arrayOf(accountName, ProtonSyncAccount.ACCOUNT_TYPE, calendarName),
        )

    private fun attendeesChanged(
        eventId: Long,
        attendees: List<Attendee>,
    ): Boolean {
        val existing = mutableSetOf<Pair<String, String>>()
        resolver
            .query(
                eventAttendeesBaseUri(eventId),
                arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS),
                null,
                null,
                null,
            )?.use { cursor ->
                val emailIndex = cursor.getColumnIndexOrThrow(Attendees.ATTENDEE_EMAIL)
                val statusIndex = cursor.getColumnIndexOrThrow(Attendees.ATTENDEE_STATUS)
                while (cursor.moveToNext()) {
                    existing += cursor.getString(emailIndex) to (cursor.getString(statusIndex) ?: "")
                }
            }
        val wanted = attendees.map { it.email to it.status.toString() }.toSet()
        return existing != wanted
    }

    private fun remindersChanged(
        eventId: Long,
        reminders: List<Reminder>,
    ): Boolean {
        val existing = mutableSetOf<Pair<String, String>>()
        resolver
            .query(
                eventRemindersBaseUri(eventId),
                arrayOf(Reminders.MINUTES, Reminders.METHOD),
                null,
                null,
                null,
            )?.use { cursor ->
                val minutesIndex = cursor.getColumnIndexOrThrow(Reminders.MINUTES)
                val methodIndex = cursor.getColumnIndexOrThrow(Reminders.METHOD)
                while (cursor.moveToNext()) {
                    existing += cursor.getString(minutesIndex) to (cursor.getString(methodIndex) ?: "")
                }
            }
        val wanted = reminders.map { it.minutes.toString() to it.method.toString() }.toSet()
        return existing != wanted
    }

    private fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<Row> {
        val cursor = resolver.query(uri, projection, selection, selectionArgs, null) ?: return emptyList()
        cursor.use {
            if (!it.moveToFirst()) return emptyList()
            val rows = mutableListOf<Row>()
            do {
                val values = mutableMapOf<String, String?>()
                for (i in 0 until it.columnCount) {
                    values[it.getColumnName(i)] = it.getString(i)
                }
                // Row identity: `_id` when projected (most queries), -1 for auxiliary
                // queries like eventIdsWithReminders that project other columns only.
                val idColumn = it.getColumnIndex(BaseColumns._ID)
                val id = if (idColumn >= 0) it.getLong(idColumn) else -1L
                rows += Row(id, values)
            } while (it.moveToNext())
            return rows
        }
    }

    private fun eventCleanupOps(
        eventId: Long,
        accountName: String,
    ): List<ContentProviderOperation> =
        listOf(
            ContentProviderOperation
                .newDelete(asSyncAdapter(Instances.CONTENT_URI, accountName))
                .withSelection("${Instances.EVENT_ID}=?", arrayOf(eventId.toString()))
                .build(),
            ContentProviderOperation
                .newDelete(eventAttendeesUri(eventId, accountName))
                .build(),
            ContentProviderOperation
                .newDelete(eventRemindersUri(eventId, accountName))
                .build(),
            ContentProviderOperation
                .newDelete(eventUri(eventId, accountName))
                .build(),
        )

    private fun attendeeInsertWithBackReference(
        attendee: Attendee,
        backReferenceIndex: Int,
        accountName: String,
    ): ContentProviderOperation =
        ContentProviderOperation
            .newInsert(
                asSyncAdapter(
                    Events.CONTENT_URI.buildUpon().appendPath(ATTENDEES_PATH).build(),
                    accountName,
                ),
            )
            .withValues(attendeeValues(attendee))
            .withValueBackReference(Attendees.EVENT_ID, backReferenceIndex)
            .build()

    private fun reminderInsertWithBackReference(
        reminder: Reminder,
        backReferenceIndex: Int,
        accountName: String,
    ): ContentProviderOperation =
        ContentProviderOperation
            .newInsert(
                asSyncAdapter(
                    Events.CONTENT_URI.buildUpon().appendPath(REMINDERS_PATH).build(),
                    accountName,
                ),
            )
            .withValues(reminderValues(reminder))
            .withValueBackReference(Reminders.EVENT_ID, backReferenceIndex)
            .build()

    private fun attendeeInsert(
        attendee: Attendee,
        eventId: Long,
        accountName: String,
    ): ContentProviderOperation =
        ContentProviderOperation
            .newInsert(eventAttendeesUri(eventId, accountName))
            .withValues(
                attendeeValues(attendee).apply { put(Attendees.EVENT_ID, eventId) },
            )
            .build()

    private fun reminderInsert(
        reminder: Reminder,
        eventId: Long,
        accountName: String,
    ): ContentProviderOperation =
        ContentProviderOperation
            .newInsert(eventRemindersUri(eventId, accountName))
            .withValues(
                reminderValues(reminder).apply { put(Reminders.EVENT_ID, eventId) },
            )
            .build()

    /**
     * Diff of [newValues] against the current [existing] row, restricted to [mutableColumns]:
     * returns only changed entries. When [putNullOnAbsent] is true, columns that disappear
     * from [newValues] are emitted as putNull (needed for events: RRULE/DURATION/DTEND must
     * be cleared when a recurring event becomes single); when false, absent columns are left
     * untouched (calendar rows: color=null means "keep the current color"). Empty result =
     * nothing to write.
     */
    private fun diff(
        existing: Map<String, String?>,
        newValues: ContentValues,
        mutableColumns: Set<String>,
        putNullOnAbsent: Boolean,
    ): ContentValues {
        val changed = ContentValues()
        for (column in mutableColumns) {
            val newValue = newValues.get(column)
            val oldValue = existing[column]
            when {
                newValue == null && oldValue == null -> Unit
                newValue == null && !putNullOnAbsent -> Unit
                newValue == null -> changed.putNull(column)
                oldValue != null && oldValue == newValue.toString() -> Unit
                else -> changed.putTyped(column, newValue)
            }
        }
        return changed
    }

    private fun ContentValues.putTyped(
        key: String,
        value: Any,
    ) {
        when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Byte -> put(key, value)
            is Short -> put(key, value)
            is Boolean -> put(key, value)
            is Float -> put(key, value)
            is Double -> put(key, value)
            else -> throw IllegalArgumentException("Unsupported ContentValues type ${value.javaClass} for $key")
        }
    }

    private fun calendarUri(
        calendarId: Long,
        accountName: String,
    ): Uri = asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), accountName)

    private fun eventUri(
        eventId: Long,
        accountName: String,
    ): Uri = asSyncAdapter(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), accountName)

    private fun eventAttendeesUri(
        eventId: Long,
        accountName: String,
    ): Uri = asSyncAdapter(eventAttendeesBaseUri(eventId), accountName)

    private fun eventAttendeesBaseUri(eventId: Long): Uri =
        Events.CONTENT_URI.buildUpon()
            .appendPath(eventId.toString())
            .appendPath(ATTENDEES_PATH)
            .build()

    private fun eventRemindersUri(
        eventId: Long,
        accountName: String,
    ): Uri = asSyncAdapter(eventRemindersBaseUri(eventId), accountName)

    private fun eventRemindersBaseUri(eventId: Long): Uri =
        Events.CONTENT_URI.buildUpon()
            .appendPath(eventId.toString())
            .appendPath(REMINDERS_PATH)
            .build()

    private fun asSyncAdapter(
        uri: Uri,
        accountName: String,
    ): Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, ProtonSyncAccount.ACCOUNT_TYPE)
            .build()

    // endregion

    private data class Row(
        val id: Long,
        val values: Map<String, String?>,
    )

    private companion object {
        const val TAG = "ProtonNativeWriter"

        const val FALLBACK_DISPLAY_NAME = "Proton"
        const val UNTITLED_EVENT_TITLE = "(No title)"
        const val UTC_TIME_ZONE = "UTC"

        const val MAX_QUERY_IDS = 500

        /**
         * CalendarContract exposes no public constant for the attendees/reminders path
         * segments (verified against the android-37.2 android.jar: none of CalendarContract,
         * Events, Attendees or Reminders declares CONTENT_DIRECTORY for them) — they are the
         * tails of CalendarContract.Attendees.CONTENT_URI / Reminders.CONTENT_URI, used as
         * literals here.
         */
        const val ATTENDEES_PATH = "attendees"
        const val REMINDERS_PATH = "reminders"

        /**
         * CalendarContract.EventsColumns.UID_2445 exists since API 35 (Android 15) — see the
         * CalendarContract docs for EventsColumns.UID_2445.
         */
        const val API_LEVEL_WITH_UID_2445 = 35

        val CALENDAR_MUTABLE_COLUMNS =
            setOf(
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.CALENDAR_COLOR,
                Calendars.OWNER_ACCOUNT,
                Calendars.CALENDAR_ACCESS_LEVEL,
            )

        val EVENT_MUTABLE_COLUMNS =
            setOf(
                Events.TITLE,
                Events.DTSTART,
                Events.DTEND,
                Events.DURATION,
                Events.RRULE,
                Events.RDATE,
                Events.EXRULE,
                Events.ALL_DAY,
                Events.EVENT_TIMEZONE,
                Events.DESCRIPTION,
                Events.EVENT_LOCATION,
                Events.STATUS,
                Events.AVAILABILITY,
                Events.SELF_ATTENDEE_STATUS,
                Events.SYNC_DATA1,
                Events.UID_2445,
                Events.ORIGINAL_SYNC_ID,
                Events.ORIGINAL_INSTANCE_TIME,
                Events.ORIGINAL_ALL_DAY,
            )
    }

    /** Whether UID_2445 may be used; disabled for the session when the provider rejects it. */
    @Volatile
    private var supportsUid2445: Boolean = Build.VERSION.SDK_INT >= API_LEVEL_WITH_UID_2445

    private fun eventMutableColumns(): Set<String> =
        if (supportsUid2445) EVENT_MUTABLE_COLUMNS else EVENT_MUTABLE_COLUMNS - Events.UID_2445
}
