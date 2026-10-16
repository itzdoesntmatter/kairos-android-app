package digital.tonima.protonsync.calendar

import android.app.Application
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Wave-2b writer behaviour: recurrence-exception rows (sync-algorithm §6.3), reminders,
 * calendar-default reminder propagation (§2k), the security-audit S-3/S-4/S-5 fixes and the
 * UID_2445=icalUid split. Idempotency of the base upsert path is covered by the wave-1
 * NativeCalendarWriterTest; this file owns the wave-2b additions.
 */
@RunWith(RobolectricTestRunner::class)
class NativeCalendarWriterWave2Test {

    private lateinit var app: Application
    private lateinit var writer: NativeCalendarWriter
    private lateinit var provider: FakeCalendarProvider

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        )
        provider =
            Robolectric
                .buildContentProvider(FakeCalendarProvider::class.java)
                .create(CalendarContract.AUTHORITY)
                .get()
        writer = NativeCalendarWriter(app)
    }

    private fun event(
        protonEventId: String,
        recurrenceRule: String? = null,
        exceptions: List<EventException> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        isAllDay: Boolean = false,
        icalUid: String? = null,
        originalSyncId: String? = null,
        originalInstanceMillis: Long? = null,
        originalAllDay: Boolean = false,
    ) = ProtonEventData(
        protonEventId = protonEventId,
        title = "Event $protonEventId",
        startMillis = 1_000_000L,
        endMillis = 3_600_000L + 1_000_000L,
        timezone = "UTC",
        isAllDay = isAllDay,
        recurrenceRule = recurrenceRule,
        exceptions = exceptions,
        reminders = reminders,
        icalUid = icalUid,
        originalSyncId = originalSyncId,
        originalInstanceMillis = originalInstanceMillis,
        originalAllDay = originalAllDay,
    )

    private fun queryRow(uri: android.net.Uri, id: Long): Map<String, Any?>? {
        val cursor =
            app.contentResolver.query(
                uri,
                null,
                "_id=?",
                arrayOf(id.toString()),
                null,
            ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val result = mutableMapOf<String, Any?>()
            for (i in 0 until it.columnCount) result[it.getColumnName(i)] = it.getString(i)
            return result
        }
    }

    // region recurrence exceptions

    @Test
    fun `EXDATE exceptions become STATUS_CANCELED rows with the master identity`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        val masterId =
            writer.upsertEvent(
                "me@proton.me",
                calendarId,
                event(
                    protonEventId = "evt-series",
                    recurrenceRule = "FREQ=DAILY",
                    exceptions = listOf(EventException(startMillis = 5_000_000L, isAllDay = false)),
                ),
            )

        val exceptionCursor =
            app.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                null,
                "${CalendarContract.Events.ORIGINAL_SYNC_ID}=?",
                arrayOf("evt-series"),
                null,
            )!!
        exceptionCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("evt-series", it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.SYNC_DATA1)))
            assertEquals(5_000_000L, it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)))
            assertEquals(
                CalendarContract.Events.STATUS_CANCELED.toLong(),
                it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.STATUS)),
            )
            assertEquals(
                masterId.toLong(),
                it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.ORIGINAL_ID)),
            )
        }
    }

    @Test
    fun `stale exception rows are removed when the server un-deletes an instance`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        writer.upsertEvent(
            "me@proton.me",
            calendarId,
            event("evt-series", "FREQ=DAILY", exceptions = listOf(EventException(5_000_000L, false))),
        )

        // Second pass: the exclusion is gone → the stale exception row must disappear.
        writer.upsertEvent("me@proton.me", calendarId, event("evt-series", "FREQ=DAILY"))

        val exceptionCursor =
            app.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                null,
                "${CalendarContract.Events.ORIGINAL_SYNC_ID}=?",
                arrayOf("evt-series"),
                null,
            )!!
        exceptionCursor.use { assertEquals(0, it.count) }
    }

    @Test
    fun `modified instances carry ORIGINAL_SYNC_ID and ORIGINAL_INSTANCE_TIME`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        val instanceId =
            writer.upsertEvent(
                "me@proton.me",
                calendarId,
                event(
                    protonEventId = "evt-child",
                    originalSyncId = "evt-master",
                    originalInstanceMillis = 9_000_000L,
                    originalAllDay = false,
                ),
            )

        val row = queryRow(CalendarContract.Events.CONTENT_URI, instanceId)!!
        assertEquals("evt-master", row[CalendarContract.Events.ORIGINAL_SYNC_ID])
        assertEquals("9000000", row[CalendarContract.Events.ORIGINAL_INSTANCE_TIME])
        assertEquals("0", row[CalendarContract.Events.ORIGINAL_ALL_DAY])
    }

    @Test
    fun `deleting a master also sweeps its exception rows and instances`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        writer.upsertEvent(
            "me@proton.me",
            calendarId,
            event("evt-master", "FREQ=DAILY", exceptions = listOf(EventException(5_000_000L, false))),
        )
        writer.upsertEvent(
            "me@proton.me",
            calendarId,
            event("evt-child", originalSyncId = "evt-master", originalInstanceMillis = 9_000_000L),
        )

        val deleted = writer.deleteEvent("me@proton.me", calendarId, "evt-master")

        assertTrue(deleted)
        val remaining =
            app.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf("_id"),
                null,
                null,
                null,
            )!!
        remaining.use { assertEquals(0, it.count) }
    }

    // endregion

    // region reminders

    @Test
    fun `reminders are written on insert and replaced on change`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        val eventId =
            writer.upsertEvent(
                "me@proton.me",
                calendarId,
                event("evt-1", reminders = listOf(Reminder(30, CalendarContract.Reminders.METHOD_ALERT))),
            )

        val first =
            app.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString()),
                null,
            )!!
        first.use {
            assertTrue(it.moveToFirst())
            assertEquals(30, it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)))
        }

        // Changing the reminder set deletes + re-inserts.
        writer.upsertEvent(
            "me@proton.me",
            calendarId,
            event("evt-1", reminders = listOf(Reminder(10, CalendarContract.Reminders.METHOD_EMAIL))),
        )
        val second =
            app.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString()),
                null,
            )!!
        second.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(10, it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)))
            assertEquals(
                CalendarContract.Reminders.METHOD_EMAIL,
                it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.METHOD)),
            )
        }
    }

    @Test
    fun `calendar default reminders only fill events that lack them`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        val withReminder =
            writer.upsertEvent(
                "me@proton.me",
                calendarId,
                event("evt-has", reminders = listOf(Reminder(60, CalendarContract.Reminders.METHOD_ALERT))),
            )
        val withoutReminder = writer.upsertEvent("me@proton.me", calendarId, event("evt-none"))

        writer.applyDefaultReminders(
            "me@proton.me",
            calendarId,
            listOf(Reminder(15, CalendarContract.Reminders.METHOD_ALERT)),
        )

        val without =
            app.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(withoutReminder.toString()),
                null,
            )!!
        without.use {
            assertTrue(it.moveToFirst())
            assertEquals(15, it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)))
        }
        val with =
            app.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(withReminder.toString()),
                null,
            )!!
        with.use {
            assertTrue(it.moveToFirst())
            assertEquals(60, it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)))
        }
    }

    // endregion

    // region security-audit fixes

    @Test
    fun `S3 - a foreign or absent calendar is never swept`() {
        val before = provider.deleteCalls

        val deleted = writer.deleteCalendar("me@proton.me", calendarId = 999L)

        assertFalse(deleted)
        assertEquals(before, provider.deleteCalls)
    }

    @Test
    fun `S4 - garbage RRULE degrades to a single instance instead of failing`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")

        val eventId =
            writer.upsertEvent(
                "me@proton.me",
                calendarId,
                event("evt-bad-rule", recurrenceRule = "FREQ=WEEKLY;BYDAY=XX;;;"),
            )

        val row = queryRow(CalendarContract.Events.CONTENT_URI, eventId)!!
        assertNull(row[CalendarContract.Events.RRULE])
        assertTrue(row.containsKey(CalendarContract.Events.DTEND))
    }

    @Test
    fun `S5 - all-day recurring events use a whole-day duration`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        val eventId =
            writer.upsertEvent(
                "me@proton.me",
                calendarId,
                event(
                    protonEventId = "evt-allday-series",
                    isAllDay = true,
                    recurrenceRule = "FREQ=DAILY",
                ).copy(
                    startMillis = 0L,
                    endMillis = 86_400_000L * 3,
                ),
            )

        val row = queryRow(CalendarContract.Events.CONTENT_URI, eventId)!!
        assertEquals("P3D", row[CalendarContract.Events.DURATION])
        assertNull(row[CalendarContract.Events.DTEND])
    }

    @Test
    fun `S5 - timed recurring events use a seconds-based DST-safe duration`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        val eventId =
            writer.upsertEvent(
                "me@proton.me",
                calendarId,
                event("evt-timed-series", recurrenceRule = "FREQ=WEEKLY"),
            )

        val row = queryRow(CalendarContract.Events.CONTENT_URI, eventId)!!
        assertEquals("P3600S", row[CalendarContract.Events.DURATION])
        assertNull(row[CalendarContract.Events.DTEND])
    }

    @Test
    fun `UID_2445 carries the decrypted iCal UID while SYNC_DATA1 stays the proton id`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")
        val eventId = writer.upsertEvent("me@proton.me", calendarId, event("evt-1", icalUid = "ical-uid-99"))

        val row = queryRow(CalendarContract.Events.CONTENT_URI, eventId)!!
        assertEquals("evt-1", row[CalendarContract.Events.SYNC_DATA1])
        assertEquals("ical-uid-99", row[CalendarContract.Events.UID_2445])
    }

    @Test
    fun `S9 - visibility timezone and sync flags are insert-only`() {
        val calendarId = writer.ensureCalendar("me@proton.me", "cal-1", "Personal")

        // The user toggled the calendar off in the Calendar app; a re-ensure must NOT
        // re-force VISIBLE=1.
        val values = ContentValues().apply { put(CalendarContract.Calendars.VISIBLE, 0) }
        app.contentResolver.update(
            CalendarContract.Calendars.CONTENT_URI,
            values,
            "_id=?",
            arrayOf(calendarId.toString()),
        )

        writer.ensureCalendar("me@proton.me", "cal-1", "Personal")

        val row = queryRow(CalendarContract.Calendars.CONTENT_URI, calendarId)!!
        assertEquals("0", row[CalendarContract.Calendars.VISIBLE])
    }

    // endregion
}
