package digital.tonima.protonsync.sync

import android.provider.CalendarContract
import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.EventNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The iCal part → CalendarContract column mapping (sync-algorithm §6.2-6.5, architecture
 * §3): timed/all-day times, timezones, RRULE/DURATION handling, EXDATE → exception rows,
 * attendees (RFC 9073 participants + legacy ATTENDEE), reminders (Proton DTO + VAlarm
 * fallback), status/transparency and the degrade paths (unparseable text, insane dates).
 * Fixtures are minimal VCALENDAR property sets exactly like the decryptor's assembly emits.
 */
class IcalEventParserTest {

    private val parser = IcalEventParser(ZoneId.of("UTC"))

    private fun detail(
        id: String = "evt-1",
        startTime: Long? = null,
        endTime: Long? = null,
        fullDay: Int = 0,
        uid: String? = null,
        sharedEventId: String? = null,
        notifications: List<EventNotification> = emptyList(),
    ) = CalendarEventDetail(
        id = id,
        startTime = startTime,
        endTime = endTime,
        fullDay = fullDay,
        uid = uid,
        sharedEventId = sharedEventId,
        notifications = notifications,
    )

    @Test
    fun `timed event maps summary times and utc timezone`() {
        val ical = "SUMMARY:Team sync\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z\r\nUID:uid-42"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertEquals("Team sync", event.title)
        assertEquals("uid-42", event.icalUid)
        assertEquals("UTC", event.timezone)
        assertFalse(event.isAllDay)
        assertTrue(event.startMillis > 0)
        assertEquals(3_600_000L, event.endMillis - event.startMillis)
    }

    @Test
    fun `all-day event uses utc midnight and the ALL_DAY flag`() {
        val ical = "SUMMARY:Vacation\r\nDTSTART;VALUE=DATE:20261016\r\nDTEND;VALUE=DATE:20261017\r\nUID:allday-1"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertTrue(event.isAllDay)
        assertEquals("UTC", event.timezone)
        assertEquals(86_400_000L, event.endMillis - event.startMillis)
        val utcMidnight = event.startMillis % 86_400_000L
        assertEquals(0L, utcMidnight)
    }

    @Test
    fun `recurring event keeps its RRULE and RDATE`() {
        val ical = "SUMMARY:Weekly\r\nDTSTART:20261016T090000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;BYDAY=FR\r\nRDATE:20261023T090000Z"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertEquals("FREQ=WEEKLY;BYDAY=FR", event.recurrenceRule)
        assertEquals("20261023T090000Z", event.recurrenceDates)
    }

    @Test
    fun `unparseable RRULE degrades to a single instance`() {
        val ical = "SUMMARY:Bad\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z\r\nRRULE:FREQ=WEEKLY;BYDAY=XX;;;"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertNull(event.recurrenceRule)
    }

    @Test
    fun `EXDATE values become deleted-instance exception rows`() {
        val ical = "SUMMARY:Series\r\nDTSTART;VALUE=DATE:20261016\r\nDURATION:P1D\r\nRRULE:FREQ=DAILY\r\nEXDATE;VALUE=DATE:20261018,20261019"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertEquals(2, event.exceptions.size)
        assertTrue(event.exceptions.all { it.isAllDay })
        assertEquals(3_600_000L * 24, event.exceptions[1].startMillis - event.exceptions[0].startMillis)
    }

    @Test
    fun `modified instance carries the master identity via SharedEventID`() {
        val ical = "SUMMARY:Moved instance\r\nDTSTART:20261018T140000Z\r\nDTEND:20261018T150000Z\r\nRECURRENCE-ID:20261016T090000Z"

        val event = parser.parse(ical, detail(id = "evt-child", sharedEventId = "evt-master"), "me@proton.me")

        assertEquals("evt-master", event.originalSyncId)
        assertTrue(event.originalInstanceMillis != null)
        assertFalse(event.originalAllDay)
    }

    @Test
    fun `attendees map PARTSTAT to android statuses`() {
        val ical =
            "SUMMARY:Invite\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z\r\n" +
                "ATTENDEE;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:bob@proton.me\r\n" +
                "ATTENDEE;PARTSTAT=DECLINED:mailto:carol@proton.me"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertEquals(2, event.attendees.size)
        val bob = event.attendees.first { it.email == "bob@proton.me" }
        val carol = event.attendees.first { it.email == "carol@proton.me" }
        assertEquals(CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED, bob.status)
        assertEquals(CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED, carol.status)
    }

    @Test
    fun `proton notifications map to reminders`() {
        val ical = "SUMMARY:Reminder test\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z"
        val detail = detail(notifications = listOf(EventNotification(type = 0, trigger = "-PT30M")))

        val event = parser.parse(ical, detail, "me@proton.me")

        assertEquals(1, event.reminders.size)
        assertEquals(30, event.reminders.single().minutes)
        assertEquals(CalendarContract.Reminders.METHOD_EMAIL, event.reminders.single().method)
    }

    @Test
    fun `vAlarm fallback when the DTO carries no notifications`() {
        val ical =
            "SUMMARY:Alarm test\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z\r\n" +
                "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nEND:VALARM"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertEquals(1, event.reminders.size)
        assertEquals(15, event.reminders.single().minutes)
    }

    @Test
    fun `cancelled status and transparent time map to provider values`() {
        val ical =
            "SUMMARY:Gone\r\nSTATUS:CANCELLED\r\nTRANSP:TRANSPARENT\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z"

        val event = parser.parse(ical, detail(), "me@proton.me")

        assertEquals(CalendarContract.Events.STATUS_CANCELED, event.status)
        assertTrue(event.isFree)
    }

    @Test
    fun `unparseable text falls back to server time metadata`() {
        val event = parser.parse("NOT ICAL AT ALL", detail(startTime = 1000L, endTime = 2000L, uid = "srv-uid"), "me@proton.me")

        assertEquals("(No title)", event.title)
        assertEquals(1_000_000L, event.startMillis)
        assertEquals(2_000_000L, event.endMillis)
        assertEquals("srv-uid", event.icalUid)
    }

    @Test
    fun `insane dates fall back to server time metadata`() {
        val ical = "SUMMARY:Way back\r\nDTSTART:18991231T235959Z\r\nDTEND:19000101T000000Z"

        val event = parser.parse(ical, detail(startTime = 1000L, endTime = 2000L), "me@proton.me")

        assertEquals(1_000_000L, event.startMillis)
    }

    @Test
    fun `placeholder carries only the unambiguous title and time metadata`() {
        val event = parser.placeholder(detail(startTime = 1000L, endTime = 2000L, uid = "u"), "me@proton.me")

        assertEquals(IcalEventParser.PLACEHOLDER_TITLE, event.title)
        assertEquals(1_000_000L, event.startMillis)
        assertEquals(2_000_000L, event.endMillis)
        assertNull(event.description)
        assertNull(event.recurrenceRule)
        assertTrue(event.attendees.isEmpty())
    }
}
