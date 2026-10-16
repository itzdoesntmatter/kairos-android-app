package digital.tonima.protonsync.calendar

import android.provider.CalendarContract

/**
 * Decrypted, provider-neutral shape of one Proton Calendar event: produced by the wave-2
 * Proton client, consumed by [NativeCalendarWriter].
 *
 * [protonEventId] is the reconciliation key — it becomes CalendarContract SYNC_DATA1 (sync
 * contract I2) and is what makes upserts idempotent across sync runs. The LOD-5 field list
 * omitted an identity field, but the update-vs-insert requirement needs one, so it is added
 * here per architecture spec §0.
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
     * Malformed rules are degraded to a single event by [NativeCalendarWriter].
     */
    val recurrenceRule: String? = null,
    val attendees: List<Attendee> = emptyList(),
) {
    init {
        require(protonEventId.isNotBlank()) { "protonEventId must not be blank" }
        require(startMillis <= endMillis) { "Event $protonEventId ends before it starts: $startMillis..$endMillis" }
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
