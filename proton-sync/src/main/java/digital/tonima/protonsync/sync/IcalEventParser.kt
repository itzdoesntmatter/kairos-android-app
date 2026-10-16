package digital.tonima.protonsync.sync

import android.provider.CalendarContract
import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.EventNotification
import digital.tonima.protonsync.calendar.Attendee
import digital.tonima.protonsync.calendar.EventException
import digital.tonima.protonsync.calendar.ProtonEventData
import digital.tonima.protonsync.calendar.Reminder
import digital.tonima.protonsync.calendar.mapProtonNotification
import logcat.LogPriority
import logcat.logcat
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Dur
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.Participant
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.Attendee as IcalAttendee
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.ExRule
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Transp
import net.fortuna.ical4j.model.property.Uid
import java.io.StringReader
import java.net.URI
import java.time.Duration as JavaDuration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import kotlin.jvm.optionals.getOrNull

/**
 * Maps one decrypted event part assembly (crypto-envelope.md §3) into the native
 * CalendarContract column mapping of sync-algorithm.md §6.2-§6.5 / architecture §3.
 *
 * Parsing runs through ical4j (the reference's parser family, sync-algorithm §4.4): the
 * assembled property lines are wrapped into a minimal VCALENDAR — PRODID is OUR identity,
 * never Proton's (security-audit §3.5.f) — and the builder's property parsers do the
 * date/RRULE/parameter work.
 *
 * Degrade contract (architecture §8): an unparseable event never aborts the run. When the
 * iCal text cannot be parsed at all, or its dates are insane (security-audit §3.5c: year
 * bounds 1970-2100, finite millis, end after start), the parser falls back to the
 * server-sent StartTime/EndTime metadata; an unparseable RRULE drops the rule (single
 * instance) rather than failing the write.
 */
class IcalEventParser(
    private val defaultZoneId: ZoneId = ZoneId.systemDefault(),
) {

    /** Maps an assembled part (property lines joined) into [ProtonEventData]. */
    fun parse(
        assembledIcal: String,
        detail: CalendarEventDetail,
        accountEmail: String,
    ): ProtonEventData {
        val event =
            try {
                parseCalendar(assembledIcal).firstOrNull()
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) {
                    "event ${detail.id}: iCal parse failed (${e.message}) — falling back to time metadata"
                }
                null
            }
        if (event == null) return metadataFallback(detail, accountEmail)

        val (start, startTz, allDay) = readStart(event)
        val end = readEnd(event, start, detail)
        if (start != null && end != null && (end <= start || start < MIN_MILLIS || end > MAX_MILLIS)) {
            logcat(TAG, LogPriority.WARN) {
                "event ${detail.id}: insane date range — falling back to time metadata"
            }
            return metadataFallback(detail, accountEmail)
        }

        val rrule = validatedRule(event.getProperties<RRule<LocalDateTime>>(Property.RRULE).firstOrNull()?.value)

        return ProtonEventData(
            protonEventId = detail.id,
            title = event.getSummary()?.value.orEmpty().ifBlank { UNTITLED_TITLE },
            startMillis = start ?: (detail.startTime?.times(1000L) ?: 0L),
            endMillis = end ?: (detail.endTime?.times(1000L) ?: start ?: 0L),
            timezone = startTz ?: (if (allDay) UTC else defaultZoneId.id),
            description = event.getDescription()?.value,
            location = event.getLocation()?.value,
            isAllDay = allDay,
            recurrenceRule = rrule,
            recurrenceDates = event.getProperties<RDate<LocalDateTime>>(Property.RDATE).firstOrNull()?.value,
            exceptionRule = event.getProperties<ExRule<LocalDateTime>>(Property.EXRULE).firstOrNull()?.value,
            icalUid = event.getProperty<Uid>(Property.UID).map { it.value }.getOrNull() ?: detail.uid,
            status = mapStatus(event.getStatus()),
            isFree = mapTransparency(event.getTransparency().getOrNull()),
            selfAttendeeStatus = selfAttendeeStatus(detail, accountEmail),
            attendees = mapAttendees(event),
            reminders = mapReminders(detail.notifications, event),
            exceptions = mapExceptions(event, allDay),
            originalSyncId = originalSyncId(detail),
            originalInstanceMillis = readRecurrenceId(event)?.first,
            originalAllDay = readRecurrenceId(event)?.second ?: false,
        )
    }

    /**
     * The all-parts-decryption-failed placeholder (crypto-envelope.md §3, security-audit
     * §3.4.d): visually unambiguous, time metadata ONLY, never decrypted fragments. Date
     * sanity applies exactly as on the parse path (§3.5c / N-4).
     */
    fun placeholder(
        detail: CalendarEventDetail,
        accountEmail: String,
    ): ProtonEventData = fallbackData(detail, accountEmail, PLACEHOLDER_TITLE)

    // region parse helpers

    private fun parseCalendar(assembled: String): List<VEvent> {
        val text =
            buildString {
                appendLine("BEGIN:VCALENDAR")
                appendLine("VERSION:2.0")
                // security-audit §3.5.f: our assembled VCALENDAR carries OUR PRODID.
                appendLine("PRODID:-//Kairós//Sync for Proton 1.0//EN")
                appendLine("BEGIN:VEVENT")
                append(assembled)
                appendLine()
                appendLine("END:VEVENT")
                appendLine("END:VCALENDAR")
            }
        val calendar: Calendar = CalendarBuilder().build(StringReader(text))
        val events = calendar.getComponents<VEvent>()
        if (events.isEmpty()) throw IllegalArgumentException("no VEVENT in parsed part")
        return events
    }

    /** (millis, timezone id, allDay) — null millis when DTSTART is absent/unreadable. */
    private fun readStart(event: VEvent): Triple<Long?, String?, Boolean> {
        // Star projection: VALUE=DATE parses into LocalDate and Z-suffixed date-times into
        // ZonedDateTime — both are Temporal, neither is the LocalDateTime the property's
        // default type parameter implies (ical4j 4 stores them by declared type only).
        val dtStart = event.getProperty<DtStart<*>>(Property.DTSTART).orElse(null)
            ?: return Triple(null, null, false)
        val temporal = try { dtStart.date as Temporal } catch (e: Exception) { return Triple(null, null, false) }
        val tzid = dtStart.getParameter<net.fortuna.ical4j.model.Parameter>(Parameter.TZID).map { it.value }.getOrNull()
        return convertTemporal(temporal, tzid)
    }

    private fun readEnd(
        event: VEvent,
        start: Long?,
        detail: CalendarEventDetail,
    ): Long? {
        val dtEnd = event.getProperty<DtEnd<*>>(Property.DTEND).orElse(null)
        if (dtEnd != null) {
            val temporal = try { dtEnd.date as Temporal } catch (e: Exception) { null }
            val tzid = dtEnd.getParameter<net.fortuna.ical4j.model.Parameter>(Parameter.TZID).map { it.value }.getOrNull()
            return temporal?.let { convertTemporal(it, tzid).first }
        }
        val duration = event.getDuration()?.duration
        if (duration != null && start != null) {
            val millis = durationToMillis(duration, start)
            if (millis != null) return start + millis
        }
        // No DTEND, no usable DURATION: the server's EndTime metadata is the reference
        // fallback for the slot bounds.
        return detail.endTime?.times(1000L)
    }

    private fun convertTemporal(
        temporal: Temporal,
        tzid: String?,
    ): Triple<Long?, String?, Boolean> =
        when (temporal) {
            is LocalDate -> {
                val millis = temporal.atStartOfDay(ZoneId.of(UTC)).toInstant().toEpochMilli()
                Triple(millis, UTC, true)
            }
            is ZonedDateTime -> Triple(temporal.toInstant().toEpochMilli(), temporal.zone.id, false)
            is OffsetDateTime -> Triple(temporal.toInstant().toEpochMilli(), defaultZoneId.id, false)
            is LocalDateTime -> {
                val zone = resolveZone(tzid)
                Triple(temporal.atZone(zone).toInstant().toEpochMilli(), zone.id, false)
            }
            else -> Triple(null, null, false)
        }

    private fun resolveZone(tzid: String?): ZoneId =
        tzid?.let {
            try {
                ZoneId.of(it)
            } catch (e: Exception) {
                defaultZoneId
            }
        } ?: defaultZoneId

    /** DST-safe duration (security-audit S-5); whole-day durations stay whole days. */
    private fun durationToMillis(
        duration: TemporalAmount,
        startMillis: Long,
    ): Long? {
        if (duration is Dur) {
            val days = (duration.weeks * 7L) + duration.days
            if (days > 0 && duration.hours == 0 && duration.minutes == 0 && duration.seconds == 0) {
                val startDate = Instant.ofEpochMilli(startMillis).atZone(ZoneId.of(UTC)).toLocalDate()
                val endDate = startDate.plusDays(days)
                return JavaDuration.between(
                    startDate.atStartOfDay(ZoneId.of(UTC)),
                    endDate.atStartOfDay(ZoneId.of(UTC)),
                ).toMillis()
            }
            return ((days * 24 + duration.hours) * 60 + duration.minutes) * 60_000L +
                duration.seconds * 1000L
        }
        if (duration is JavaDuration) {
            // Pure java.time.Duration is DST-free by construction and safe to convert directly.
            return duration.toMillis()
        }
        return null
    }

    private fun validatedRule(raw: String?): String? {
        val rule = digital.tonima.protonsync.calendar.RruleValidator.validate(raw)
        if (raw != null && rule == null) {
            logcat(TAG, LogPriority.WARN) {
                "dropping unparseable RRULE '$raw' — event synced as a single instance"
            }
        }
        return rule
    }

    private fun mapStatus(status: Status?): Int =
        // sync-algorithm §6.2: TENTATIVE→0, CONFIRMED→1, CANCELLED→2, default 0 (reference).
        when (status?.value) {
            Status.VALUE_CONFIRMED -> CalendarContract.Events.STATUS_CONFIRMED
            Status.VALUE_CANCELLED -> CalendarContract.Events.STATUS_CANCELED
            else -> CalendarContract.Events.STATUS_TENTATIVE
        }

    private fun mapTransparency(transp: Transp?): Boolean =
        transp?.value == Transp.VALUE_TRANSPARENT

    /** sync-algorithm §6.4 PARTSTAT mapping: ACCEPTED→1, DECLINED→2, TENTATIVE→4, else 3. */
    private fun mapAttendees(event: VEvent): List<Attendee> {
        val attendees = mutableListOf<Attendee>()
        for (participant in event.participants) {
            val email = participantEmail(participant) ?: continue
            val status =
                when (participant.getStatus().map { it.value }.getOrNull()) {
                    "ACCEPTED" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
                    "DECLINED" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                    "TENTATIVE" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
                    else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                }
            attendees += Attendee(email = email, status = status)
        }
        // Payloads may keep the legacy ATTENDEE property instead of the RFC 9073 Participant
        // component; ical4j exposes either shape depending on content conversion. PARTSTAT
        // maps exactly like the participant path (sync-algorithm §6.4).
        for (property in event.getProperties<IcalAttendee>(Property.ATTENDEE)) {
            val email = property.calAddress?.schemeSpecificPart ?: continue
            if (attendees.none { it.email == email }) {
                val partStat =
                    property.getParameter<net.fortuna.ical4j.model.Parameter>(Parameter.PARTSTAT)
                        .map { it.value }.getOrNull()
                val status =
                    when (partStat?.uppercase()) {
                        "ACCEPTED" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
                        "DECLINED" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                        "TENTATIVE" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
                        else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                    }
                attendees += Attendee(email = email, status = status)
            }
        }
        return attendees
    }

    private fun participantEmail(participant: Participant): String? {
        val address = participant.getCalendarAddress().getOrNull() ?: return null
        val uri = try { URI(address.value) } catch (e: Exception) { return null }
        return uri.schemeSpecificPart
    }

    /** Proton attendee status ints → android SELF_ATTENDEE_STATUS (calendar-api §3.6). */
    private fun selfAttendeeStatus(
        detail: CalendarEventDetail,
        accountEmail: String,
    ): Int {
        val self = detail.effectiveAttendees.firstOrNull { it.token == accountEmail }
            ?: return CalendarContract.Attendees.ATTENDEE_STATUS_NONE
        return when (self.status) {
            1 -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
            2 -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
            3 -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
            else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
        }
    }

    /** sync-algorithm §6.5: Proton notifications + VAlarm fallback. */
    private fun mapReminders(
        notifications: List<EventNotification>,
        event: VEvent,
    ): List<Reminder> {
        if (notifications.isNotEmpty()) {
            return notifications.mapNotNull { mapProtonNotification(it) }
        }
        return event.alarms.mapNotNull { alarm ->
            val trigger = alarm.trigger.getOrNull()?.duration
            val minutes = when (trigger) {
                is JavaDuration -> trigger.seconds / 60
                is Dur -> (trigger.weeks * 7 * 24 * 60) + (trigger.days * 24 * 60) +
                    (trigger.hours * 60) + trigger.minutes
                else -> 15
            }.toInt().let { if (it < 0) 15 else it }
            val method =
                when (alarm.action.getOrNull()?.value) {
                    "EMAIL" -> CalendarContract.Reminders.METHOD_EMAIL
                    else -> CalendarContract.Reminders.METHOD_ALERT
                }
            Reminder(minutes = minutes, method = method)
        }
    }

    /** EXDATE values → STATUS_CANCELED exception rows (sync-algorithm §6.3). */
    private fun mapExceptions(
        event: VEvent,
        masterAllDay: Boolean,
    ): List<EventException> {
        val exceptions = mutableListOf<EventException>()
        for (exDate in event.getProperties<ExDate<*>>(Property.EXDATE)) {
            // Like DTSTART: VALUE=DATE lists hold LocalDate elements, date-times hold
            // LocalDateTime/ZonedDateTime — all Temporal.
            for (temporal in exDate.dates) {
                val tzid = exDate.getParameter<net.fortuna.ical4j.model.Parameter>(Parameter.TZID).map { it.value }.getOrNull()
                val (millis, _, allDay) = convertTemporal(temporal, tzid)
                if (millis != null) {
                    exceptions += EventException(startMillis = millis, isAllDay = allDay || masterAllDay)
                }
            }
        }
        return exceptions
    }

    private fun readRecurrenceId(event: VEvent): Pair<Long, Boolean>? {
        val recurrenceId = event.getProperty<RecurrenceId<*>>(Property.RECURRENCE_ID).orElse(null)
            ?: return null
        val temporal = try { recurrenceId.date as Temporal } catch (e: Exception) { return null }
        val tzid = recurrenceId.getParameter<net.fortuna.ical4j.model.Parameter>(Parameter.TZID).map { it.value }.getOrNull()
        val (millis, _, allDay) = convertTemporal(temporal, tzid)
        return millis?.let { it to allDay }
    }

    /**
     * Modified instances of a recurring event arrive as separate server events whose
     * SharedEventID points at the master (calendar-api §3) — those become native rows with
     * ORIGINAL_SYNC_ID (sync-algorithm §6.2/§6.3).
     */
    private fun originalSyncId(detail: CalendarEventDetail): String? =
        detail.sharedEventId?.takeIf { it.isNotBlank() && it != detail.id }

    private fun metadataFallback(
        detail: CalendarEventDetail,
        accountEmail: String,
    ): ProtonEventData = fallbackData(detail, accountEmail, UNTITLED_TITLE)

    /**
     * The one builder behind both metadata-only paths — [metadataFallback] (unparseable or
     * insane iCal) and [placeholder] (nothing decrypted). Both carry the server's time
     * metadata and nothing decrypted, and both run the §3.5c date-sanity check the parse path
     * runs (security-audit finding N-4): an undatable event is flagged [ProtonEventData.isUndated]
     * so the engine skips it instead of writing a fabricated row.
     */
    private fun fallbackData(
        detail: CalendarEventDetail,
        accountEmail: String,
        title: String,
    ): ProtonEventData {
        val dates = fallbackDates(detail)
        return ProtonEventData(
            protonEventId = detail.id,
            title = title,
            startMillis = dates?.startMillis ?: 0L,
            endMillis = dates?.endMillis ?: 0L,
            timezone = if (detail.fullDay == 1) UTC else defaultZoneId.id,
            isAllDay = detail.fullDay == 1,
            icalUid = detail.uid,
            selfAttendeeStatus = selfAttendeeStatus(detail, accountEmail),
            originalSyncId = originalSyncId(detail),
            isUndated = dates == null,
        )
    }

    /**
     * security-audit §3.5c / N-4 applied to the server's raw time metadata: seconds are
     * bounds-checked BEFORE the millis multiply (so an extreme value cannot overflow into a
     * negative instant), the result must sit inside the 1970-2100 window, and the end must not
     * precede the start.
     *
     * A usable `StartTime` is required — an event with no place on the timeline is left for the
     * engine to skip rather than being pinned to an invented instant. A missing or unusable
     * `EndTime` degrades to a zero-length event (the reference's fallback tolerance), never to
     * an inverted range.
     */
    private fun fallbackDates(detail: CalendarEventDetail): FallbackDates? {
        val start = saneMillis(detail.startTime) ?: return null
        val end = saneMillis(detail.endTime)
        val safeEnd = if (end != null && end > start) end else start
        return FallbackDates(startMillis = start, endMillis = safeEnd)
    }

    /** Seconds → millis inside the §3.5c window, or null when the value is not sane. */
    private fun saneMillis(seconds: Long?): Long? {
        val value = seconds ?: return null
        // 1970-01-01 .. 2100-01-01 in seconds; checking here means the ×1000 below cannot
        // overflow a Long (Long.MAX_VALUE / 1000 is ~9.2 × 10^15 seconds, year ~292 million).
        if (value < MIN_SECONDS || value > MAX_SECONDS) return null
        val millis = value * 1000L
        return if (millis in MIN_MILLIS..MAX_MILLIS) millis else null
    }

    /** A sanitized fallback range (§3.5c). */
    private data class FallbackDates(
        val startMillis: Long,
        val endMillis: Long,
    )

    // endregion

    companion object {
        private const val TAG = "ProtonIcalParser"
        private const val UTC = "UTC"

        /** security-audit §3.4.d — the unambiguous cannot-decrypt placeholder title. */
        const val PLACEHOLDER_TITLE = "[Encrypted event — cannot decrypt]"

        private const val UNTITLED_TITLE = "(No title)"

        /** security-audit §3.5c date sanity: events outside 1970-2100 are rejected. */
        private const val MIN_MILLIS = -1L
        private val MAX_MILLIS = LocalDate.of(2100, 1, 1).atStartOfDay(ZoneId.of(UTC)).toInstant().toEpochMilli()

        /** The same window in seconds — checked before the ×1000 multiply (N-4). */
        private const val MIN_SECONDS = -1L
        private val MAX_SECONDS = MAX_MILLIS / 1000L
    }
}
