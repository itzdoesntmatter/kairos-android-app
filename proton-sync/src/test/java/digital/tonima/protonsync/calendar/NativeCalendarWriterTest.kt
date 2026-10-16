package digital.tonima.protonsync.calendar

import android.Manifest
import android.app.Application
import android.net.Uri
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.test.core.app.ApplicationProvider
import digital.tonima.protonsync.account.ProtonSyncAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises [NativeCalendarWriter] against a real ContentResolver with an in-memory
 * CalendarProvider stand-in (see [FakeCalendarProvider]): insert/update/delete idempotency,
 * column mapping, and the I1/I2/I3 sync invariants.
 */
@RunWith(RobolectricTestRunner::class)
class NativeCalendarWriterTest {
    private lateinit var context: Application
    private lateinit var provider: FakeCalendarProvider
    private lateinit var writer: NativeCalendarWriter

    private val accountName = "alice@proton.me"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider =
            Robolectric
                .buildContentProvider(FakeCalendarProvider::class.java)
                .create(CalendarContract.AUTHORITY)
                .get()
        grantPermissions()
        writer = NativeCalendarWriter(context)
    }

    // region Calendar rows

    @Test
    fun `ensureCalendar creates the bridge calendar row`() {
        val id = writer.ensureCalendar(accountName, "cal-123", "Work", color = 255)

        val row = rows(Calendars.CONTENT_URI, null).single()
        assertEquals(id.toString(), row[BaseColumns._ID])
        assertEquals("Work", row[Calendars.CALENDAR_DISPLAY_NAME])
        assertEquals("cal-123", row[Calendars.NAME])
        assertEquals(accountName, row[Calendars.ACCOUNT_NAME])
        assertEquals(ProtonSyncAccount.ACCOUNT_TYPE, row[Calendars.ACCOUNT_TYPE])
        assertEquals(accountName, row[Calendars.OWNER_ACCOUNT])
        assertEquals("1", row[Calendars.VISIBLE])
        assertEquals("1", row[Calendars.SYNC_EVENTS])
        assertEquals(Calendars.CAL_ACCESS_OWNER.toString(), row[Calendars.CALENDAR_ACCESS_LEVEL])
        assertEquals("255", row[Calendars.CALENDAR_COLOR])
    }

    @Test
    fun `ensureCalendar is idempotent and updates the existing row in place`() {
        val first = writer.ensureCalendar(accountName, "cal-123", "Work", 255)
        val second = writer.ensureCalendar(accountName, "cal-123", "Work", 255)
        assertEquals(first, second)
        assertEquals(1, rows(Calendars.CONTENT_URI).size)

        val third = writer.ensureCalendar(accountName, "cal-123", "Work (renamed)", 255)
        assertEquals(first, third)
        assertEquals(1, rows(Calendars.CONTENT_URI).size)
        assertEquals("Work (renamed)", rows(Calendars.CONTENT_URI).single()[Calendars.CALENDAR_DISPLAY_NAME])
    }

    @Test
    fun `ensureCalendar falls back to a Proton display name when blank`() {
        writer.ensureCalendar(accountName, "cal-123", "  ")
        assertEquals("Proton", rows(Calendars.CONTENT_URI).single()[Calendars.CALENDAR_DISPLAY_NAME])
    }

    // endregion

    // region Event mapping

    @Test
    fun `upsertEvent inserts a timed event with the mapped native columns`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        val data =
            eventData(
                attendees =
                    listOf(
                        Attendee("bob@proton.me", Attendees.ATTENDEE_STATUS_ACCEPTED),
                        Attendee("carol@proton.me"),
                    ),
            )

        val eventId = writer.upsertEvent(accountName, calendarId, data)

        val row = singleEvent()
        assertEquals(eventId.toString(), row[BaseColumns._ID])
        assertEquals(calendarId.toString(), row[Events.CALENDAR_ID])
        assertEquals("Standup", row[Events.TITLE])
        assertEquals("1700000000000", row[Events.DTSTART])
        assertEquals("1700001800000", row[Events.DTEND])
        assertEquals("0", row[Events.ALL_DAY])
        assertEquals("America/Sao_Paulo", row[Events.EVENT_TIMEZONE])
        assertEquals("Daily sync", row[Events.DESCRIPTION])
        assertEquals("Zoom", row[Events.EVENT_LOCATION])
        assertEquals(Events.STATUS_CONFIRMED.toString(), row[Events.STATUS])
        assertEquals("proton-event-1", row[Events.SYNC_DATA1])
        assertEquals("proton-event-1", row[Events.UID_2445])
        assertNull(row[Events.RRULE])
        assertNull(row[Events.DURATION])

        val attendeeRows = rows(Attendees.CONTENT_URI, null)
        assertEquals(
            setOf("bob@proton.me", "carol@proton.me"),
            attendeeRows.map { it[Attendees.ATTENDEE_EMAIL] }.toSet(),
        )
        assertEquals(setOf(eventId.toString()), attendeeRows.map { it[Attendees.EVENT_ID] }.toSet())
        val bob = attendeeRows.first { it[Attendees.ATTENDEE_EMAIL] == "bob@proton.me" }
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED.toString(), bob[Attendees.ATTENDEE_STATUS])
        assertEquals(Attendees.RELATIONSHIP_ATTENDEE.toString(), bob[Attendees.ATTENDEE_RELATIONSHIP])
        assertEquals(Attendees.TYPE_REQUIRED.toString(), bob[Attendees.ATTENDEE_TYPE])

        // The provider materialised an instance row for the event, like the real one does.
        assertEquals(
            1,
            rows(Instances.CONTENT_URI, null, "${Instances.EVENT_ID}=?", arrayOf(eventId.toString())).size,
        )
    }

    @Test
    fun `upsertEvent maps all-day events to UTC regardless of the payload timezone`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        writer.upsertEvent(accountName, calendarId, eventData(timezone = "America/Sao_Paulo", allDay = true))

        val row = singleEvent()
        assertEquals("1", row[Events.ALL_DAY])
        assertEquals("UTC", row[Events.EVENT_TIMEZONE])
    }

    @Test
    fun `upsertEvent writes recurring events with an RFC 2445 duration and no end time`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        writer.upsertEvent(
            accountName,
            calendarId,
            eventData(start = 1_700_000_000_000L, end = 1_700_003_600_000L, recurrence = "FREQ=WEEKLY;BYDAY=MO"),
        )

        val row = singleEvent()
        assertEquals("FREQ=WEEKLY;BYDAY=MO", row[Events.RRULE])
        assertEquals("P3600S", row[Events.DURATION])
        assertNull(row[Events.DTEND])
    }

    @Test
    fun `upsertEvent degrades an unparseable recurrence rule to a single event`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        writer.upsertEvent(accountName, calendarId, eventData(recurrence = "NOT-A-RRULE"))

        val row = singleEvent()
        assertNull(row[Events.RRULE])
        assertNull(row[Events.DURATION])
        assertNotNull(row[Events.DTEND])
    }

    @Test
    fun `upsertEvent keeps the row identity when fields change`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        val id =
            writer.upsertEvent(
                accountName,
                calendarId,
                eventData(title = "Old", attendees = listOf(Attendee("bob@proton.me"))),
            )
        writer.upsertEvent(
            accountName,
            calendarId,
            eventData(title = "New", attendees = listOf(Attendee("carol@proton.me"))),
        )

        val row = singleEvent()
        assertEquals(id.toString(), row[BaseColumns._ID])
        assertEquals("New", row[Events.TITLE])
        assertEquals(1, rows(Events.CONTENT_URI).size)
        assertEquals(
            listOf("carol@proton.me"),
            rows(Attendees.CONTENT_URI).map { it[Attendees.ATTENDEE_EMAIL] },
        )
    }

    @Test
    fun `upsertEvent clears the recurrence columns when an event becomes single`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        val id = writer.upsertEvent(accountName, calendarId, eventData(recurrence = "FREQ=WEEKLY"))
        writer.upsertEvent(accountName, calendarId, eventData(recurrence = null))

        val row = singleEvent()
        assertEquals(id.toString(), row[BaseColumns._ID])
        assertNull(row[Events.RRULE])
        assertNull(row[Events.DURATION])
        assertNotNull(row[Events.DTEND])
    }

    // endregion

    // region Idempotency and deletes

    @Test
    fun `upsertEvent is idempotent - an unchanged second call writes nothing`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        val data = eventData(attendees = listOf(Attendee("bob@proton.me")))

        val first = writer.upsertEvent(accountName, calendarId, data)
        val writesAfterFirst = provider.writtenUris.size
        val updatesAfterFirst = provider.updateCalls

        val second = writer.upsertEvent(accountName, calendarId, data)

        assertEquals(first, second)
        assertEquals(writesAfterFirst, provider.writtenUris.size)
        assertEquals(updatesAfterFirst, provider.updateCalls)
        assertEquals(1, rows(Events.CONTENT_URI).size)
    }

    @Test
    fun `deleteEvent removes the event its attendees and its instances and is idempotent`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        writer.upsertEvent(accountName, calendarId, eventData(attendees = listOf(Attendee("bob@proton.me"))))

        assertTrue(writer.deleteEvent(accountName, calendarId, "proton-event-1"))
        assertEquals(0, rows(Events.CONTENT_URI).size)
        assertEquals(0, rows(Attendees.CONTENT_URI).size)
        assertEquals(0, rows(Instances.CONTENT_URI).size)

        assertFalse(writer.deleteEvent(accountName, calendarId, "proton-event-1"))
    }

    @Test
    fun `deleteCalendar removes the calendar row and everything inside it`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        writer.upsertEvent(
            accountName,
            calendarId,
            eventData(id = "e-1", attendees = listOf(Attendee("bob@proton.me"))),
        )
        writer.upsertEvent(accountName, calendarId, eventData(id = "e-2", title = "Other"))

        assertTrue(writer.deleteCalendar(accountName, calendarId))
        assertEquals(0, rows(Calendars.CONTENT_URI).size)
        assertEquals(0, rows(Events.CONTENT_URI).size)
        assertEquals(0, rows(Attendees.CONTENT_URI).size)
        assertEquals(0, rows(Instances.CONTENT_URI).size)

        assertFalse(writer.deleteCalendar(accountName, calendarId))
    }

    @Test
    fun `deleteCalendarsForAccount removes every calendar of the Proton account`() {
        val first = writer.ensureCalendar(accountName, "cal-1", "One")
        val second = writer.ensureCalendar(accountName, "cal-2", "Two")
        writer.upsertEvent(accountName, first, eventData(id = "e-1"))
        writer.upsertEvent(accountName, second, eventData(id = "e-2"))

        assertEquals(listOf(first, second), writer.deleteCalendarsForAccount(accountName))

        assertEquals(0, rows(Calendars.CONTENT_URI).size)
        assertEquals(0, rows(Events.CONTENT_URI).size)
    }

    // endregion

    // region Invariants and preconditions

    @Test
    fun `every provider write carries the sync-adapter parameters`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        writer.upsertEvent(accountName, calendarId, eventData(attendees = listOf(Attendee("bob@proton.me"))))
        writer.upsertEvent(accountName, calendarId, eventData(title = "Renamed"))
        writer.deleteEvent(accountName, calendarId, "proton-event-1")

        assertTrue(provider.writtenUris.isNotEmpty())
        provider.writtenUris.forEach { uri ->
            assertEquals("true", uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER))
            assertEquals(accountName, uri.getQueryParameter(Calendars.ACCOUNT_NAME))
            assertEquals(ProtonSyncAccount.ACCOUNT_TYPE, uri.getQueryParameter(Calendars.ACCOUNT_TYPE))
        }
    }

    @Test
    fun `every entry point throws CalendarPermissionException without calendar permissions`() {
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")
        revokePermissions()
        val writesBefore = provider.writtenUris.size
        val data = eventData()

        assertPermissionException { writer.ensureCalendar(accountName, "cal-2", "Other") }
        assertPermissionException { writer.upsertEvent(accountName, calendarId, data) }
        assertPermissionException { writer.deleteEvent(accountName, calendarId, data.protonEventId) }
        assertPermissionException { writer.deleteCalendar(accountName, calendarId) }
        assertPermissionException { writer.deleteCalendarsForAccount(accountName) }

        assertEquals(writesBefore, provider.writtenUris.size)
    }

    @Test
    fun `upsertEvent survives providers that reject the UID 2445 column`() {
        provider.rejectUid2445Column = true
        val calendarId = writer.ensureCalendar(accountName, "cal-1", "Proton")

        val id = writer.upsertEvent(accountName, calendarId, eventData())

        val row = singleEvent()
        assertEquals(id.toString(), row[BaseColumns._ID])
        assertEquals("proton-event-1", row[Events.SYNC_DATA1])
        assertNull(row[Events.UID_2445])
    }

    @Test
    fun `ProtonEventData rejects invalid shapes`() {
        assertThrows(IllegalArgumentException::class.java) { eventData(id = " ") }
        assertThrows(IllegalArgumentException::class.java) { eventData(start = 10L, end = 9L) }
        assertThrows(IllegalArgumentException::class.java) { Attendee(" ") }
    }

    // endregion

    private fun grantPermissions() {
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }

    private fun revokePermissions() {
        shadowOf(context).denyPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }

    private fun assertPermissionException(block: () -> Any?) {
        val exception = assertThrows(CalendarPermissionException::class.java) { block() }
        assertTrue(exception.missingPermissions.contains(Manifest.permission.WRITE_CALENDAR))
        assertTrue(exception.message!!.contains("calendar permissions"))
    }

    private fun singleEvent(): Map<String, String?> = rows(Events.CONTENT_URI, null).single()

    private fun rows(
        uri: Uri,
        projection: Array<String>? = null,
        selection: String? = null,
        args: Array<String>? = null,
    ): List<Map<String, String?>> =
        context.contentResolver.query(uri, projection, selection, args, null)!!.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).associate {
                            cursor.getColumnName(it) to cursor.getString(it)
                        },
                    )
                }
            }
        }

    private fun eventData(
        id: String = "proton-event-1",
        title: String = "Standup",
        start: Long = 1_700_000_000_000L,
        end: Long = 1_700_001_800_000L,
        timezone: String = "America/Sao_Paulo",
        description: String? = "Daily sync",
        location: String? = "Zoom",
        allDay: Boolean = false,
        recurrence: String? = null,
        attendees: List<Attendee> = emptyList(),
    ) = ProtonEventData(id, title, start, end, timezone, description, location, allDay, recurrence, attendees)
}
