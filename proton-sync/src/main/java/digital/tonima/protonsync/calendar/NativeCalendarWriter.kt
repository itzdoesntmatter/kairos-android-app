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
import android.util.Log
import digital.tonima.protonsync.account.ProtonSyncAccount
import java.time.ZoneId
import java.util.concurrent.TimeUnit

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
 */
class NativeCalendarWriter(context: Context) {
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    // region Calendar rows

    /**
     * Creates or updates the native calendar row that hosts the mirror of one Proton calendar.
     * Identity is (ACCOUNT_NAME, ACCOUNT_TYPE, NAME), so re-login re-attaches existing rows
     * instead of duplicating them. Returns the native calendar id.
     */
    fun ensureCalendar(
        accountName: String,
        calendarName: String,
        displayName: String,
        color: Int? = null,
    ): Long {
        checkPermission()
        val existing = queryCalendars(accountName, calendarName).firstOrNull()
        if (existing != null) {
            val changed =
                diff(
                    existing.values,
                    calendarValues(displayName, color, accountName),
                    CALENDAR_MUTABLE_COLUMNS,
                    putNullOnAbsent = false,
                )
            if (changed.size() > 0) {
                resolver.update(calendarUri(existing.id, accountName), changed, null, null)
            }
            return existing.id
        }

        val insertValues =
            calendarValues(displayName, color, accountName).apply {
                put(Calendars.ACCOUNT_NAME, accountName)
                put(Calendars.ACCOUNT_TYPE, ProtonSyncAccount.ACCOUNT_TYPE)
                put(Calendars.NAME, calendarName)
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            }
        val uri =
            resolver.insert(asSyncAdapter(Calendars.CONTENT_URI, accountName), insertValues)
                ?: throw IllegalStateException(
                    "CalendarProvider refused to create the Proton calendar '$calendarName' for $accountName",
                )
        return ContentUris.parseId(uri)
    }

    /** Deletes one bridge calendar row plus everything inside it (events, attendees, instances). */
    fun deleteCalendar(
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
        return calendarExisted || eventIds.isNotEmpty()
    }

    /**
     * Full-account deletion: removes every calendar row (and their events) owned by
     * [accountName]. Belt-and-braces for logout — the primary strategy remains
     * AccountManager.removeAccountExplicitly, which makes the provider purge our rows itself.
     */
    fun deleteCalendarsForAccount(accountName: String): List<Long> {
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

    /**
     * Inserts or updates the native row for one Proton event inside [calendarId] and returns
     * the native event id. Identity: (calendar, SYNC_DATA1 = proton event id). Idempotent:
     * same payload twice writes once; an unchanged payload writes nothing on the second call.
     */
    fun upsertEvent(
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
            val result = resolver.applyBatch(CalendarContract.AUTHORITY, ops).first()
            return result.uri?.let { ContentUris.parseId(it) }
                ?: findEvent(calendarId, data.protonEventId)?.id
                ?: throw IllegalStateException(
                    "CalendarProvider accepted the insert of Proton event ${data.protonEventId} " +
                        "but the row cannot be located afterwards",
                )
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
        if (ops.isNotEmpty()) {
            resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        }
        return eventId
    }

    /**
     * Deletes one Proton event's native rows (Events + Instances + Attendees, I3). Returns
     * whether anything existed; deleting an absent event is a no-op returning false.
     */
    fun deleteEvent(
        accountName: String,
        calendarId: Long,
        protonEventId: String,
    ): Boolean {
        checkPermission()
        val eventIds =
            query(
                Events.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=?",
                arrayOf(calendarId.toString(), protonEventId),
            ).map { it.id }
        if (eventIds.isEmpty()) return false

        val ops = arrayListOf<ContentProviderOperation>()
        eventIds.forEach { ops += eventCleanupOps(it, accountName) }
        resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        return true
    }

    // endregion

    // region Mapping

    private fun calendarValues(
        displayName: String,
        color: Int?,
        accountName: String,
    ): ContentValues =
        ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName.ifBlank { FALLBACK_DISPLAY_NAME })
            color?.let { put(Calendars.CALENDAR_COLOR, it) }
            put(Calendars.OWNER_ACCOUNT, accountName)
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
                // expand correctly in the provider.
                put(Events.RRULE, recurrenceRule)
                put(Events.DURATION, rfc2445Duration(data.endMillis - data.startMillis))
            }
            put(Events.ALL_DAY, if (data.isAllDay) 1 else 0)
            put(
                Events.EVENT_TIMEZONE,
                if (data.isAllDay) UTC_TIME_ZONE else data.timezone.ifBlank { ZoneId.systemDefault().id },
            )
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            put(Events.SYNC_DATA1, data.protonEventId)
            if (supportsUid2445) {
                put(Events.UID_2445, data.protonEventId)
            }
            data.description?.let { put(Events.DESCRIPTION, it) }
            data.location?.let { put(Events.EVENT_LOCATION, it) }
        }

    private fun attendeeValues(attendee: Attendee): ContentValues =
        ContentValues().apply {
            put(Attendees.ATTENDEE_EMAIL, attendee.email)
            put(Attendees.ATTENDEE_STATUS, attendee.status)
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
        }

    /**
     * Only guarantees the RFC 5545 shape well enough to keep a garbage string out of the
     * provider (a malformed RRULE would fail the whole event insert): rules must start with
     * FREQ=. Full parsing/validation stays the provider's job; a rule we reject degrades the
     * event to a single instance (architecture §3).
     */
    private fun validateRecurrenceRule(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("FREQ=", ignoreCase = true)) {
            Log.w(TAG, "Dropping malformed RRULE '$trimmed' (missing FREQ=); event synced as single instance")
            return null
        }
        return trimmed
    }

    /** RFC 2445 dur-value in its simplest legal form ("P<n>S"), see RFC 2445 §4.3.6. */
    private fun rfc2445Duration(durationMillis: Long): String = "P${TimeUnit.MILLISECONDS.toSeconds(durationMillis)}S"

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
                rows += Row(it.getLong(it.getColumnIndexOrThrow(BaseColumns._ID)), values)
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

        /**
         * CalendarContract exposes no public constant for the attendees path segment (verified
         * against the android-37.2 android.jar: none of CalendarContract, Events or Attendees
         * declares CONTENT_DIRECTORY for it) — it is the "attendees" tail of
         * CalendarContract.Attendees.CONTENT_URI, used as a literal here.
         */
        const val ATTENDEES_PATH = "attendees"

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
                Calendars.VISIBLE,
                Calendars.SYNC_EVENTS,
                Calendars.CALENDAR_TIME_ZONE,
            )

        val EVENT_MUTABLE_COLUMNS =
            setOf(
                Events.TITLE,
                Events.DTSTART,
                Events.DTEND,
                Events.DURATION,
                Events.RRULE,
                Events.ALL_DAY,
                Events.EVENT_TIMEZONE,
                Events.DESCRIPTION,
                Events.EVENT_LOCATION,
                Events.STATUS,
                Events.SYNC_DATA1,
                Events.UID_2445,
            )
    }

    /** Whether UID_2445 may be used; disabled for the session when the provider rejects it. */
    @Volatile
    private var supportsUid2445: Boolean = Build.VERSION.SDK_INT >= API_LEVEL_WITH_UID_2445

    private fun eventMutableColumns(): Set<String> =
        if (supportsUid2445) EVENT_MUTABLE_COLUMNS else EVENT_MUTABLE_COLUMNS - Events.UID_2445
}
