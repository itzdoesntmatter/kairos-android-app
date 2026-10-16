package digital.tonima.protonsync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.CalendarEventIdsResponse
import digital.tonima.protonsync.api.dto.CalendarEventListResponse
import digital.tonima.protonsync.api.dto.CalendarInfo
import digital.tonima.protonsync.api.dto.CalendarKey
import digital.tonima.protonsync.api.dto.CalendarKeyResponse
import digital.tonima.protonsync.api.dto.CalendarListResponse
import digital.tonima.protonsync.api.dto.CalendarPassphrase
import digital.tonima.protonsync.api.dto.CalendarPassphraseResponse
import digital.tonima.protonsync.api.dto.CalendarSettingsResponse
import digital.tonima.protonsync.api.dto.EventPart
import digital.tonima.protonsync.api.dto.MemberPassphrase
import digital.tonima.protonsync.calendar.ProtonCalendarWriter
import digital.tonima.protonsync.calendar.ProtonEventData
import digital.tonima.protonsync.calendar.Reminder
import digital.tonima.protonsync.crypto.EventPartDecryptor
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.db.CalendarSyncStateEntity
import digital.tonima.protonsync.db.NativeEventMapEntity
import digital.tonima.protonsync.db.SyncDatabase
import digital.tonima.protonsync.db.TombstoneEntity
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ProtonSyncEngine behaviour tests: fetch → decrypt → reconcile → write, tombstone state
 * machine, per-calendar isolation (I4), the placeholder degrade path, vanished-calendar
 * cleanup and the LastEditTime skip rule. The API client, keyring and writer are fakes —
 * the engine's own orchestration is what's under test (the writer gets its own suite, the
 * API its MockWebServer-style interceptor suites from wave 2a).
 */
@RunWith(RobolectricTestRunner::class)
class ProtonSyncEngineTest {

    private lateinit var db: SyncDatabase
    private lateinit var api: ProtonApiClient
    private lateinit var sessionManager: SessionManager
    private lateinit var keyring: ProtonCryptoKeyring
    private lateinit var writer: FakeWriter
    private lateinit var statusProvider: ProtonSyncStatusProvider
    private lateinit var engine: ProtonSyncEngine

    private val plainEventId = "evt-1"

    private fun plainDetail(id: String = plainEventId, lastEditTime: Long? = 100L) =
        CalendarEventDetail(
            id = id,
            lastEditTime = lastEditTime,
            startTime = 1_000L,
            endTime = 2_000L,
            sharedEvents =
                listOf(
                    EventPart(
                        type = 2,
                        data = "SUMMARY:Test\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z",
                    ),
                ),
        )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, SyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        api = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        keyring = mockk(relaxed = true)
        writer = FakeWriter()
        statusProvider = ProtonSyncStatusProvider()

        coEvery { sessionManager.currentRefreshToken() } returns "refresh-token"
        coEvery { keyring.hasUserKey() } returns true
        coEvery { keyring.calendarKey("cal-1") } returns null
        coEvery { keyring.decryptArmoredWithRing(any()) } returns "calendar-passphrase"

        coEvery { api.getCalendars() } returns
            CalendarListResponse(
                code = 1000,
                calendars = listOf(CalendarInfo(id = "cal-1", name = "Personal", type = 0)),
            )
        coEvery { api.getCalendarKeys("cal-1") } returns
            CalendarKeyResponse(
                code = 1000,
                keys =
                    listOf(
                        CalendarKey(
                            id = "ck-1",
                            calendarId = "cal-1",
                            privateKey = "armored-calendar-key",
                            passphraseId = "pp-1",
                            flags = 3,
                        ),
                    ),
            )
        coEvery { api.getCalendarPassphrase("cal-1") } returns
            CalendarPassphraseResponse(
                code = 1000,
                passphrases =
                    listOf(
                        CalendarPassphrase(
                            id = "pp-1",
                            flags = 3,
                            memberPassphrases =
                                listOf(MemberPassphrase(memberId = "m-1", passphrase = "armored-passphrase")),
                        ),
                    ),
            )
        coEvery { api.getCalendarSettings("cal-1") } returns CalendarSettingsResponse(code = 1000, settings = null)
        coEvery { api.getEvents(any(), any(), any(), any(), any(), any(), any(), any()) } answers { call ->
            val type = call.invocation.args[6] as Int
            if (type == 0) CalendarEventListResponse(code = 1000, events = listOf(plainDetail()))
            else CalendarEventListResponse(code = 1000, events = emptyList())
        }
        coEvery { api.getEventIds("cal-1", any(), any()) } returns
            CalendarEventIdsResponse(code = 1000, ids = listOf(plainEventId))

        engine =
            ProtonSyncEngine(
                accountName = "me@proton.me",
                api = api,
                sessionManager = sessionManager,
                keyring = keyring,
                decryptor = EventPartDecryptor(TestCrypto, keyring),
                parser = IcalEventParser(),
                writer = writer,
                db = db,
                statusProvider = statusProvider,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun incrementalSince() = System.currentTimeMillis() - 1_000L

    @Test
    fun `happy path - fetches decrypts writes and maps one event`() = runTest {
        val result = engine.syncIncremental(incrementalSince())

        assertEquals(SyncResult.Status.SUCCESS, result.status)
        assertEquals(1, writer.upserts.size)
        assertEquals("Test", writer.upserts.single().title)
        assertEquals(1, result.eventsSynced)

        val map = db.nativeEventMapDao().byCalendar("me@proton.me", "cal-1")
        assertEquals(1, map.size)
        assertEquals(plainEventId, map.single().protonEventId)
        assertEquals(200L, map.single().androidEventId)
        assertEquals(100L, map.single().lastModified)

        assertEquals(1, statusProvider.state.value.counters.eventsSynced)
        assertEquals(digital.tonima.protonsync.status.SyncState.IDLE, statusProvider.state.value.syncState)
    }

    @Test
    fun `unchanged event is skipped - no provider write`() = runTest {
        db.nativeEventMapDao().upsert(
            listOf(
                NativeEventMapEntity(
                    accountName = "me@proton.me",
                    protonCalendarId = "cal-1",
                    protonEventId = plainEventId,
                    androidEventId = 200L,
                    lastModified = 999L,
                    isException = false,
                    parentProtonEventId = null,
                ),
            ),
        )

        engine.syncIncremental(incrementalSince())

        assertTrue(writer.upserts.isEmpty())
    }

    @Test
    fun `tombstone state machine deletes after two successful runs`() = runTest {
        db.nativeEventMapDao().upsert(
            listOf(
                NativeEventMapEntity(
                    accountName = "me@proton.me",
                    protonCalendarId = "cal-1",
                    protonEventId = "evt-gone",
                    androidEventId = 42L,
                    lastModified = 5L,
                    isException = false,
                    parentProtonEventId = null,
                ),
            ),
        )
        coEvery { api.getEventIds("cal-1", any(), any()) } returns
            // The windowed body fetch (base getEvents stub) delivers plainEventId — only
            // evt-gone is actually missing server-side, so the ids pass must include it.
            CalendarEventIdsResponse(code = 1000, ids = listOf(plainEventId))

        // Run 1: tombstone created, nothing deleted yet.
        engine.syncIncremental(incrementalSince())
        var tombstone = db.tombstoneDao().byCalendar("me@proton.me", "cal-1").single()
        assertEquals(0, tombstone.successfulRunsSince)
        assertTrue(writer.deletedEvents.isEmpty())

        // Run 2: still held.
        engine.syncIncremental(incrementalSince())
        tombstone = db.tombstoneDao().byCalendar("me@proton.me", "cal-1").single()
        assertEquals(1, tombstone.successfulRunsSince)
        assertTrue(writer.deletedEvents.isEmpty())

        // Run 3: two successful runs have passed — deleted for real.
        val result = engine.syncIncremental(incrementalSince())
        assertTrue(writer.deletedEvents.contains("evt-gone"))
        assertEquals(1, result.eventsDeleted)
        assertTrue(db.tombstoneDao().byCalendar("me@proton.me", "cal-1").isEmpty())
        // evt-gone's map row is gone; the fetched plainEventId row legitimately remains.
        val remaining = db.nativeEventMapDao().byCalendar("me@proton.me", "cal-1")
        assertTrue(remaining.none { it.protonEventId == "evt-gone" })
    }

    @Test
    fun `a re-appeared event drops its tombstone`() = runTest {
        db.nativeEventMapDao().upsert(
            listOf(
                NativeEventMapEntity("me@proton.me", "cal-1", "evt-x", 42L, 5L, false, null),
            ),
        )
        coEvery { api.getEventIds("cal-1", any(), any()) } returns
            // The body fetch (base stub) delivers plainEventId; only evt-x is missing.
            CalendarEventIdsResponse(code = 1000, ids = listOf(plainEventId))
        engine.syncIncremental(incrementalSince())
        assertEquals(1, db.tombstoneDao().byCalendar("me@proton.me", "cal-1").size)

        coEvery { api.getEventIds("cal-1", any(), any()) } returns
            CalendarEventIdsResponse(code = 1000, ids = listOf("evt-x", plainEventId))
        engine.syncIncremental(incrementalSince())

        assertTrue(db.tombstoneDao().byCalendar("me@proton.me", "cal-1").isEmpty())
        // evt-x keeps its original map row (never re-fetched — only its tombstone drops)
        // and the fetched plainEventId has its own: the map holds both.
        assertEquals(2, db.nativeEventMapDao().byCalendar("me@proton.me", "cal-1").size)
    }

    @Test
    fun `one failing calendar never aborts the others - partial sync I4`() = runTest {
        coEvery { api.getCalendars() } returns
            CalendarListResponse(
                code = 1000,
                calendars =
                    listOf(
                        CalendarInfo(id = "cal-1", name = "Personal", type = 0),
                        CalendarInfo(id = "cal-broken", name = "Broken", type = 1),
                    ),
            )
        coEvery { api.getCalendarKeys("cal-broken") } throws IllegalStateException("keys endpoint exploded")
        // A relaxed mock returns a child mock for ByteArray?, so the unlock shortcut would
        // fire and the failure path would never run — force the not-yet-unlocked state.
        coEvery { keyring.calendarKey("cal-broken") } returns null

        val result = engine.syncIncremental(incrementalSince())

        assertEquals(SyncResult.Status.SUCCESS_WITH_NOTE, result.status)
        assertEquals(1, writer.upserts.size)
        assertEquals(1, statusProvider.state.value.calendarErrors.size)
        assertEquals("cal-broken", statusProvider.state.value.calendarErrors.single().calendarId)
        // cal-1's state is recorded as healthy.
        assertNull(db.calendarSyncStateDao().find("me@proton.me", "cal-1")!!.lastError)
    }

    @Test
    fun `all-parts-decryption-failure writes the placeholder`() = runTest {
        coEvery { api.getEvents(any(), any(), any(), any(), any(), any(), any(), any()) } answers { call ->
            val type = call.invocation.args[6] as Int
            if (type == 0) {
                CalendarEventListResponse(
                    code = 1000,
                    events =
                        listOf(
                            CalendarEventDetail(
                                id = "evt-enc",
                                lastEditTime = 100L,
                                startTime = 5_000L,
                                endTime = 6_000L,
                                addressKeyPacket = "AQID",
                                sharedEvents =
                                    listOf(
                                        EventPart(type = 1, data = "AQID"),
                                    ),
                            ),
                        ),
                )
            } else {
                CalendarEventListResponse(code = 1000, events = emptyList())
            }
        }
        coEvery { api.getEventIds("cal-1", any(), any()) } returns
            CalendarEventIdsResponse(code = 1000, ids = listOf("evt-enc"))

        engine.syncIncremental(incrementalSince())

        assertEquals(1, writer.upserts.size)
        assertEquals(IcalEventParser.PLACEHOLDER_TITLE, writer.upserts.single().title)
        assertEquals(5_000_000L, writer.upserts.single().startMillis)
        assertEquals(1, statusProvider.state.value.counters.decryptFailures)
    }

    @Test
    fun `a calendar that vanished server-side is deleted locally`() = runTest {
        db.calendarSyncStateDao().upsert(
            CalendarSyncStateEntity(
                accountName = "me@proton.me",
                protonCalendarId = "gone-cal",
                androidCalendarId = 77L,
                displayName = "Old",
                color = 0,
                lastSyncEpochMs = 0L,
                lastError = null,
                errorCount = 0,
            ),
        )

        engine.syncIncremental(incrementalSince())

        assertTrue(writer.deletedCalendars.contains(77L))
        assertNull(db.calendarSyncStateDao().find("me@proton.me", "gone-cal"))
    }

    @Test
    fun `no session fails the run instead of retrying forever`() = runTest {
        coEvery { sessionManager.currentRefreshToken() } returns null

        val result = engine.syncFull()

        assertEquals(SyncResult.Status.FAILURE, result.status)
        assertEquals(digital.tonima.protonsync.status.SyncState.NEEDS_REAUTH, statusProvider.state.value.syncState)
        coVerify(exactly = 0) { api.getCalendars() }
    }

    private class FakeWriter : ProtonCalendarWriter {
        val upserts = mutableListOf<ProtonEventData>()
        val deletedEvents = mutableListOf<String>()
        val deletedCalendars = mutableListOf<Long>()
        val defaultReminderBatches = mutableListOf<List<Reminder>>()

        override fun ensureCalendar(
            accountName: String,
            calendarName: String,
            displayName: String,
            color: Int?,
            accessLevel: Int,
        ): Long = 100L

        override fun deleteCalendar(
            accountName: String,
            calendarId: Long,
        ): Boolean {
            deletedCalendars += calendarId
            return true
        }

        override fun deleteCalendarsForAccount(accountName: String): List<Long> = emptyList()

        override fun upsertEvent(
            accountName: String,
            calendarId: Long,
            data: ProtonEventData,
        ): Long {
            upserts += data
            return 200L
        }

        override fun deleteEvent(
            accountName: String,
            calendarId: Long,
            protonEventId: String,
        ): Boolean {
            deletedEvents += protonEventId
            return true
        }

        override fun applyDefaultReminders(
            accountName: String,
            calendarId: Long,
            reminders: List<Reminder>,
        ) {
            defaultReminderBatches += reminders
        }
    }

    /** A trivially unusable crypto fake — engine tests only exercise the decrypt FAILURE paths. */
    private object TestCrypto : digital.tonima.protonsync.crypto.ProtonCrypto {
        override fun canParseKey(armoredKey: String): Boolean = true

        override fun unlock(
            armoredKey: String,
            passphrase: ByteArray,
        ): ByteArray = throw digital.tonima.protonsync.crypto.CryptoDecryptException("test crypto cannot unlock")

        override fun decryptArmoredText(
            armoredMessage: String,
            privateKey: ByteArray,
        ): String = throw digital.tonima.protonsync.crypto.CryptoDecryptException("test")

        override fun decryptArmoredData(
            armoredMessage: String,
            privateKey: ByteArray,
        ): ByteArray = throw digital.tonima.protonsync.crypto.CryptoDecryptException("test")

        override fun decryptBinary(
            keyPacket: ByteArray,
            dataPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray = throw digital.tonima.protonsync.crypto.CryptoDecryptException("test")

        override fun decryptSessionKey(
            keyPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray = throw digital.tonima.protonsync.crypto.CryptoDecryptException("test")

        override fun decryptDataWithSessionKey(
            dataPacket: ByteArray,
            sessionKey: ByteArray,
        ): ByteArray = throw digital.tonima.protonsync.crypto.CryptoDecryptException("test")

        override fun verifyDetached(
            data: ByteArray,
            armoredSignature: String,
            senderArmoredKeys: List<String>,
        ): Boolean = false

        override fun fingerprint(armoredKey: String): String = "test"
    }
}
