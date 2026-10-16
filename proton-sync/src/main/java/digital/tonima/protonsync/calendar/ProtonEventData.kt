package digital.tonima.protonsync.calendar

import android.provider.CalendarContract

/**
 * Decrypted, provider-neutral shape of one Proton Calendar event: produced by the wave-2
 * engine (fetch → decrypt → parse), consumed by [NativeCalendarWriter].
 *
 * [protonEventId] is the reconciliation key — it becomes CalendarContract SYNC_DATA1 (sync
 * contract I2) and is what makes upserts idempotent across sync runs. [icalUid] is the
 * decrypted iCal UID, written to UID_2445 on API 35+ (architecture §3) — a DIFFERENT value
 * from [protonEventId]: identity comes from SYNC_DATA1, the iCal UID is payload metadata.
 */
data class ProtonEventData(
    val protonEventId: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timezone: String,
    val description: String? = null,
    val location: String? = null,
    val isAllDay: Boolean = false,
    /**
     * RFC 5545 RRULE text (e.g. "FREQ=WEEKLY;BYDAY=MO"), or null/blank for a single event.
     * Malformed rules are degraded to a single event by [NativeCalendarWriter] (via
     * [RruleValidator]) — never thrown at the provider mid-transaction (security-audit S-4).
     */
    val recurrenceRule: String? = null,
    /** RFC 5545 RDATE text (sync-algorithm §6.2), or null. */
    val recurrenceDates: String? = null,
    /** RFC 5545 EXRULE text, or null. */
    val exceptionRule: String? = null,
    /** iCal UID for the UID_2445 column (architecture §3). */
    val icalUid: String? = null,
    /** VEVENT STATUS → Events.STATUS_* (sync-algorithm §6.2: TENTATIVE→0, CONFIRMED→1, CANCELLED→2). */
    val status: Int = CalendarContract.Events.STATUS_CONFIRMED,
    /** TRANSP mapping (sync-algorithm §6.2): true = FREE (AVAILABILITY_FREE). */
    val isFree: Boolean = false,
    /** The account's own attendee status, android ATTENDEE_STATUS_* constant (calendar-api §3.6). */
    val selfAttendeeStatus: Int = CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
    val attendees: List<Attendee> = emptyList(),
    /** Proton EventNotification mapping (sync-algorithm §6.5). */
    val reminders: List<Reminder> = emptyList(),
    /**
     * Recurrence-exception rows: deleted instances of a recurring master (EXDATE) become
     * STATUS_CANCELED exception rows; modified instances arrive as separate events with
     * [originalSyncId] set (sync-algorithm §6.3). Both are written under the master's
     * SYNC_DATA1 identity so master deletion sweeps them (architecture §3).
     */
    val exceptions: List<EventException> = emptyList(),
    /** Master proton event id + RECURRENCE-ID when this row IS a modified instance. */
    val originalSyncId: String? = null,
    /** RECURRENCE-ID instant (originalInstanceTime), only set with [originalSyncId]. */
    val originalInstanceMillis: Long? = null,
    /** All-day flag of the recurrence-id date (originalAllDay), only with [originalSyncId]. */
    val originalAllDay: Boolean = false,
) {
    init {
        require(protonEventId.isNotBlank()) { "protonEventId must not be blank" }
        require(startMillis <= endMillis) { "Event $protonEventId ends before it starts: $startMillis..$endMillis" }
        require(originalSyncId == null || originalInstanceMillis != null) {
            "a recurrence-exception row needs its RECURRENCE-ID instant"
        }
    }
}

/**
 * One calendar attendee. [status] is a
 * [CalendarContract.AttendeesColumns.ATTENDEE_STATUS] value
 * ([CalendarContract.Attendees.ATTENDEE_STATUS_NONE] by default).
 */
data class Attendee(
    val email: String,
    val status: Int = CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
) {
    init {
        require(email.isNotBlank()) { "attendee email must not be blank" }
    }
}

/** One native Reminders row (sync-algorithm §6.5). */
data class Reminder(
    /** Minutes before the event start; negative values clamp to 0 (reference behaviour). */
    val minutes: Int,
    /** CalendarContract.Reminders.METHOD_ALERT or METHOD_EMAIL. */
    val method: Int,
)

/**
 * A deleted instance of a recurring master: written as a STATUS_CANCELED exception row
 * carrying the master's identity (sync-algorithm §6.3). [startMillis] is the EXDATE instant
 * (all-day dates normalized to UTC midnight, like every all-day time in the mapping).
 */
data class EventException(
    val startMillis: Long,
    val isAllDay: Boolean,
)
