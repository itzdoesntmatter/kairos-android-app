package digital.tonima.protonsync.calendar

import android.provider.CalendarContract
import digital.tonima.protonsync.api.dto.EventNotification

/**
 * Shared Proton → native reminder mapping (sync-algorithm §6.5), used by the per-event path
 * and the calendar-defaults path: minutes = −Duration.parse(Trigger).toMinutes() (−1 → 0),
 * method = Type 0 → EMAIL, else ALERT.
 */
fun mapProtonNotification(notification: EventNotification): Reminder? {
    val raw = notification.trigger
    val parsed =
        try {
            raw?.let { net.fortuna.ical4j.model.Dur(it) }
        } catch (e: Exception) {
            null
        }
    if (parsed == null) {
        // Parse failure → the reference's VAlarm fallback default (15 min, ALERT).
        return Reminder(minutes = 15, method = CalendarContract.Reminders.METHOD_ALERT)
    }
    // ical4j's Dur parses "-PT30M" as a positive 30 minutes (the sign is stripped by the
    // ISO-8601 parser), so apply the leading sign explicitly before negating.
    val absoluteMinutes = (parsed.weeks * 7 * 24 * 60) + (parsed.days * 24 * 60) + (parsed.hours * 60) + parsed.minutes
    val signed = if (raw?.startsWith("-") == true) -absoluteMinutes else absoluteMinutes
    val minutesBefore = -signed
    val normalized = if (minutesBefore == -1) 0 else minutesBefore.toInt()
    return Reminder(
        minutes = normalized,
        method = if (notification.type == 0) CalendarContract.Reminders.METHOD_EMAIL
        else CalendarContract.Reminders.METHOD_ALERT,
    )
}
