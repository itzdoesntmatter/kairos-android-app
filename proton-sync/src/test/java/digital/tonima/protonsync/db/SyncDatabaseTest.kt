package digital.tonima.protonsync.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO round-trips against an in-memory Room instance (storage.md schema semantics,
 * architecture §10). Also enforces the content rule: every column is metadata (IDs,
 * timestamps, counters) — the schema has no content columns by construction
 * (security-audit §5.2/§7.10).
 */
@RunWith(RobolectricTestRunner::class)
class SyncDatabaseTest {

    private lateinit var db: SyncDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, SyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `event map round-trips and binds the android id`() = runTest {
        val dao = db.nativeEventMapDao()
        dao.upsert(
            listOf(
                NativeEventMapEntity(
                    accountName = "a@proton.me",
                    protonCalendarId = "cal-1",
                    protonEventId = "evt-1",
                    androidEventId = null,
                    lastModified = 100L,
                    isException = false,
                    parentProtonEventId = null,
                ),
            ),
        )

        assertEquals(1, dao.byCalendar("a@proton.me", "cal-1").size)
        assertNull(dao.find("a@proton.me", "cal-1", "evt-1")!!.androidEventId)

        dao.bindAndroidEventId("a@proton.me", "cal-1", "evt-1", 42L)

        assertEquals(42L, dao.find("a@proton.me", "cal-1", "evt-1")!!.androidEventId)
    }

    @Test
    fun `calendar state records success and errors`() = runTest {
        val dao = db.calendarSyncStateDao()
        val state =
            CalendarSyncStateEntity(
                accountName = "a@proton.me",
                protonCalendarId = "cal-1",
                androidCalendarId = null,
                displayName = "Personal",
                color = 0xFF336699.toInt(),
                lastSyncEpochMs = 0L,
                lastError = null,
                errorCount = 0,
            )
        dao.upsert(state)

        dao.bindAndroidCalendarId("a@proton.me", "cal-1", 7L)
        dao.recordError("a@proton.me", "cal-1", "boom")

        val after = dao.find("a@proton.me", "cal-1")!!
        assertEquals(7L, after.androidCalendarId)
        assertEquals("boom", after.lastError)
        assertEquals(1, after.errorCount)

        dao.markSuccess("a@proton.me", "cal-1", 123_456L)

        val final = dao.find("a@proton.me", "cal-1")!!
        assertEquals(123_456L, final.lastSyncEpochMs)
        assertNull(final.lastError)
        assertEquals(0, final.errorCount)
    }

    @Test
    fun `tombstones round-trip per calendar`() = runTest {
        val dao = db.tombstoneDao()
        dao.upsert(
            TombstoneEntity(
                accountName = "a@proton.me",
                protonCalendarId = "cal-1",
                protonEventId = "evt-gone",
                successfulRunsSince = 1,
                createdAtEpochMs = 5L,
            ),
        )
        dao.upsert(
            TombstoneEntity(
                accountName = "a@proton.me",
                protonCalendarId = "cal-2",
                protonEventId = "evt-other",
                successfulRunsSince = 0,
                createdAtEpochMs = 5L,
            ),
        )

        assertEquals(1, dao.byCalendar("a@proton.me", "cal-1").size)
        dao.delete("a@proton.me", "cal-1", "evt-gone")
        assertEquals(0, dao.byCalendar("a@proton.me", "cal-1").size)
        assertEquals(1, dao.byCalendar("a@proton.me", "cal-2").size)
    }

    @Test
    fun `sync stats accumulate across runs`() = runTest {
        val dao = db.syncRunStatsDao()
        dao.upsert(
            SyncRunStatsEntity(
                accountName = "a@proton.me",
                eventsSynced = 10,
                eventsDeleted = 2,
                decryptFailures = 1,
                verifyFailures = 0,
                apiErrors = 0,
                lastRunDurationMs = 500L,
                lastSuccessEpochMs = 1L,
            ),
        )

        val loaded = dao.find("a@proton.me")!!

        assertEquals(10, loaded.eventsSynced)
        assertEquals(2, loaded.eventsDeleted)
        assertNotNull(loaded)
    }

    @Test
    fun `deleteAllForAccount clears every table`() = runTest {
        db.nativeEventMapDao().upsert(
            listOf(
                NativeEventMapEntity("a@proton.me", "cal-1", "evt-1", 1L, 0L, false, null),
            ),
        )
        db.calendarSyncStateDao().upsert(
            CalendarSyncStateEntity("a@proton.me", "cal-1", 1L, "x", 0, 0L, null, 0),
        )
        db.tombstoneDao().upsert(TombstoneEntity("a@proton.me", "cal-1", "evt-1", 0, 0L))

        db.nativeEventMapDao().deleteAllForAccount("a@proton.me")
        db.calendarSyncStateDao().deleteAllForAccount("a@proton.me")
        db.tombstoneDao().deleteAllForAccount("a@proton.me")

        assertEquals(0, db.nativeEventMapDao().byCalendar("a@proton.me", "cal-1").size)
        assertNull(db.calendarSyncStateDao().find("a@proton.me", "cal-1"))
        assertEquals(0, db.tombstoneDao().byCalendar("a@proton.me", "cal-1").size)
    }
}
