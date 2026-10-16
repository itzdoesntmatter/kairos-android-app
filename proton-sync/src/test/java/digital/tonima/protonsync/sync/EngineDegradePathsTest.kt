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
import digital.tonima.protonsync.crypto.CryptoDecryptException
import digital.tonima.protonsync.crypto.EventPartDecryptor
import digital.tonima.protonsync.crypto.ProtonCrypto
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.db.NativeEventMapEntity
import digital.tonima.protonsync.db.SyncDatabase
import digital.tonima.protonsync.db.TombstoneEntity
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import digital.tonima.protonsync.status.SyncState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine's two bounded-degrade paths.
 *
 * **C3 / N-2 — the all-time id walk.** The walk is the deletion authority; before this fix it
 * looped `while (true)` on full pages, so a server answering with a stable tail (or a cycle)
 * spun forever. Pinned here: a non-progressing walk stops after the second page, a walk that
 * does keep progressing stops at the 100-page cap, both defer the deletion pass instead of
 * running it against a partial id set, and both surface the degradation in the run result and
 * the status card.
 *
 * **N-4 — undated events.** An event whose payload cannot be decrypted AND whose server time
 * metadata has no usable start is skipped rather than written at a fabricated instant.
 *
 * Fixture: real Room (in-memory) + real status provider, faked API/keyring/crypto/writer — the
 * engine's own control flow is what is under test.
 */
@RunWith(RobolectricTestRunner::class)
class EngineDegradePathsTest {

    private lateinit var db: SyncDatabase
    private lateinit var api: ProtonApiClient
    private lateinit var writer: RecordingWriter
    private lateinit var statusProvider: ProtonSyncStatusProvider
    private lateinit var engine: ProtonSyncEngine

    private val account = "me@proton.me"
    private val calendarId = "cal-1"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, SyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        api = mockk(relaxed = true)
        val sessionManager: SessionManager = mockk(relaxed = true)
        val keyring: ProtonCryptoKeyring = mockk(relaxed = true)
        writer = RecordingWriter()
        statusProvider = ProtonSyncStatusProvider()

        coEvery { sessionManager.currentRefreshToken() } returns "refresh-token"
        coEvery { keyring.hasUserKey() } returns true
        coEvery { keyring.calendarKey(calendarId) } returns null
        coEvery { keyring.decryptArmoredWithRing(any()) } returns "calendar-passphrase"

        coEvery { api.getCalendars() } returns
            CalendarListResponse(code = 1000, calendars = listOf(CalendarInfo(id = calendarId, name = "Personal", type = 0)))
        coEvery { api.getCalendarKeys(calendarId) } returns
            CalendarKeyResponse(
                code = 1000,
                keys =
                    listOf(
                        CalendarKey(
                            id = "ck-1",
                            calendarId = calendarId,
                            privateKey = "armored-calendar-key",
                            passphraseId = "pp-1",
                            flags = 3,
                        ),
                    ),
            )
        coEvery { api.getCalendarPassphrase(calendarId) } returns
            CalendarPassphraseResponse(
                code = 1000,
                passphrases =
                    listOf(
                        CalendarPassphrase(
                            id = "pp-1",
                            flags = 3,
                            memberPassphrases = listOf(MemberPassphrase(memberId = "m-1", passphrase = "armored")),
                        ),
                    ),
            )
        coEvery { api.getCalendarSettings(calendarId) } returns CalendarSettingsResponse(code = 1000, settings = null)
        coEvery { api.getEvents(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            CalendarEventListResponse(code = 1000, events = emptyList())

        engine =
            ProtonSyncEngine(
                accountName = account,
                api = api,
                sessionManager = sessionManager,
                keyring = keyring,
                decryptor = EventPartDecryptor(FailingCrypto, keyring),
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

    private fun fullPage(ids: List<String>) = CalendarEventIdsResponse(code = 1000, ids = ids)

    private fun stalledPage(): List<String> = (1..IDS_LIMIT).map { "evt-$it" }

    private suspend fun seedTombstonedLocalEvent(protonEventId: String) {
        db.nativeEventMapDao().upsert(
            listOf(
                NativeEventMapEntity(
                    accountName = account,
                    protonCalendarId = calendarId,
                    protonEventId = protonEventId,
                    androidEventId = 777L,
                    lastModified = 1L,
                    isException = false,
                    parentProtonEventId = null,
                ),
            ),
        )
        db.tombstoneDao().upsert(
            TombstoneEntity(
                accountName = account,
                protonCalendarId = calendarId,
                protonEventId = protonEventId,
                successfulRunsSince = TombstoneEntity.SUCCESSFUL_RUNS_REQUIRED - 1,
                createdAtEpochMs = 0L,
            ),
        )
    }

    @Test
    fun `a stalled id walk stops at the second page and defers the deletion pass`() = runTest {
        coEvery { api.getEventIds(calendarId, any(), any()) } returns fullPage(stalledPage())

        val result = engine.syncFull()

        // One page, then a repeat of the same cursor: no more requests are made.
        coVerify(exactly = 2) { api.getEventIds(calendarId, any(), any()) }
        assertEquals(SyncResult.Status.SUCCESS_WITH_NOTE, result.status)
        assertTrue(writer.deletedEvents.isEmpty())

        val state = statusProvider.state.value
        assertEquals(SyncState.ERROR, state.syncState)
        val warning = state.calendarErrors.single().message
        assertTrue(warning.contains("stopped making progress"))
        assertTrue(warning.contains("deletion pass deferred"))
    }

    @Test
    fun `a walk that keeps progressing stops at the page cap`() = runTest {
        coEvery { api.getEventIds(calendarId, any(), any()) } answers { call ->
            val afterId = call.invocation.args[2] as String?
            val base = afterId ?: "first"
            fullPage((1..IDS_LIMIT).map { "$base-$it" })
        }

        val result = engine.syncFull()

        coVerify(exactly = MAX_ID_PAGES) { api.getEventIds(calendarId, any(), any()) }
        assertEquals(SyncResult.Status.SUCCESS_WITH_NOTE, result.status)
        // 100 pages × 100 ids: the walk never ran a deletion pass on a truncated set.
        assertTrue(writer.deletedEvents.isEmpty())
        assertTrue(statusProvider.state.value.calendarErrors.single().message.contains("page cap"))
    }

    @Test
    fun `a complete walk still deletes a tombstoned event`() = runTest {
        // The event is gone server-side and its tombstone already survived one run.
        coEvery { api.getEventIds(calendarId, any(), any()) } returns fullPage(listOf("evt-alive"))
        seedTombstonedLocalEvent("evt-gone")

        val result = engine.syncFull()

        assertEquals(SyncResult.Status.SUCCESS, result.status)
        assertEquals(listOf("evt-gone"), writer.deletedEvents)
        assertEquals(1, result.eventsDeleted)
    }

    @Test
    fun `an incomplete walk leaves the tombstoned event alone`() = runTest {
        coEvery { api.getEventIds(calendarId, any(), any()) } returns fullPage(stalledPage())
        seedTombstonedLocalEvent("evt-gone")

        val result = engine.syncFull()

        assertEquals(SyncResult.Status.SUCCESS_WITH_NOTE, result.status)
        assertTrue(writer.deletedEvents.isEmpty())
        assertEquals(0, result.eventsDeleted)
    }

    @Test
    fun `a failing ids fetch defers the deletion pass without failing the calendar`() = runTest {
        coEvery { api.getEventIds(calendarId, any(), any()) } throws java.io.IOException("network down")
        seedTombstonedLocalEvent("evt-gone")

        val result = engine.syncFull()

        assertEquals(SyncResult.Status.SUCCESS_WITH_NOTE, result.status)
        assertTrue(writer.deletedEvents.isEmpty())
        // The calendar itself succeeded; the walk is reported through the status card.
        assertTrue(
            statusProvider.state.value.calendarErrors.single().message.contains("ids fetch failed"),
        )
    }

    @Test
    fun `an event with no usable date metadata is skipped, not written`() = runTest {
        // Decryption fails (the crypto fake cannot open anything), so the metadata-only
        // placeholder path runs — and the metadata here has no sane start.
        coEvery { api.getEvents(any(), any(), any(), any(), any(), any(), any(), any()) } answers { call ->
            val type = call.invocation.args[6] as Int
            if (type == 0) {
                CalendarEventListResponse(
                    code = 1000,
                    events =
                        listOf(
                            CalendarEventDetail(
                                id = "evt-hostile",
                                lastEditTime = 100L,
                                startTime = Long.MAX_VALUE,
                                endTime = Long.MAX_VALUE,
                                sharedEvents = listOf(EventPart(type = 1, data = "QUJD")),
                            ),
                        ),
                )
            } else {
                CalendarEventListResponse(code = 1000, events = emptyList())
            }
        }
        coEvery { api.getEventIds(calendarId, any(), any()) } returns fullPage(emptyList())

        val result = engine.syncFull()

        assertTrue(writer.upserts.isEmpty())
        assertEquals(0, result.eventsSynced)
        // Counted, not silent: the loss shows up in the run's failure counter.
        assertTrue(statusProvider.state.value.counters.decryptFailures > 0)
    }

    /** Records what the engine asked the provider layer to write. */
    private class RecordingWriter : ProtonCalendarWriter {
        val upserts = mutableListOf<ProtonEventData>()
        val deletedEvents = mutableListOf<String>()

        override fun ensureCalendar(
            accountName: String,
            calendarName: String,
            displayName: String,
            color: Int?,
            accessLevel: Int,
        ): Long = 1L

        override fun deleteCalendar(
            accountName: String,
            calendarId: Long,
        ): Boolean = true

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
        ) = Unit
    }

    /** Cannot open anything: every decrypt path fails, which is what these tests exploit. */
    private object FailingCrypto : ProtonCrypto {
        override fun canParseKey(armoredKey: String): Boolean = true

        override fun unlock(
            armoredKey: String,
            passphrase: ByteArray,
        ): ByteArray = throw CryptoDecryptException("test crypto cannot unlock")

        override fun decryptArmoredText(
            armoredMessage: String,
            privateKey: ByteArray,
        ): String = throw CryptoDecryptException("test")

        override fun decryptArmoredData(
            armoredMessage: String,
            privateKey: ByteArray,
        ): ByteArray = throw CryptoDecryptException("test")

        override fun decryptBinary(
            keyPacket: ByteArray,
            dataPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray = throw CryptoDecryptException("test")

        override fun decryptSessionKey(
            keyPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray = throw CryptoDecryptException("test")

        override fun decryptDataWithSessionKey(
            dataPacket: ByteArray,
            sessionKey: ByteArray,
        ): ByteArray = throw CryptoDecryptException("test")

        override fun verifyDetached(
            data: ByteArray,
            armoredSignature: String,
            senderArmoredKeys: List<String>,
        ): Boolean = false

        override fun fingerprint(armoredKey: String): String = "test"
    }

    private companion object {
        const val IDS_LIMIT = 100

        /**
         * Mirrors `ProtonSyncEngine.MAX_IDS_PAGES` (private): 100 pages × 100 ids = 10 000
         * events per calendar. Kept as a literal here so a silent change to the production cap
         * fails this test rather than following it.
         */
        const val MAX_ID_PAGES = 100
    }
}
