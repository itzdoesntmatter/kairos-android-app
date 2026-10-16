package digital.tonima.kairos.service

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.BaseColumns
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import digital.tonima.kairos.core.model.KairosProtonSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import android.content.Context

/**
 * The CalendarChangeObserver Proton hook (architecture §1 item 6, security-audit ME-1/ME-2):
 * only DIRTY rows of OUR account type trigger the onchange sync (origin filter — the
 * sync's own CALLER_IS_SYNCADAPTER writes carry DIRTY=0), the min-interval gate caps
 * triggers at one per 15 minutes, and foreign rows / base-URI notifications never trigger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProtonSyncObserverGateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeCalendarProvider

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(StubWorkerFactory)
                .build(),
        )
        // CalendarChangeObserver is a process-wide singleton; start every test from a clean slate.
        CalendarChangeObserver::class.java.getDeclaredField(
            "initialized",
        ).apply { isAccessible = true }.setBoolean(null, false)
        resetGate()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        AccountManager.get(app).addAccountExplicitly(
            Account("alice@proton.me", KairosProtonSync.ACCOUNT_TYPE),
            null,
            null,
        )
        provider =
            Robolectric
                .buildContentProvider(FakeCalendarProvider::class.java)
                .create(CalendarContract.AUTHORITY)
                .get()
    }

    @Test
    fun `a dirty row of our account type enqueues the onchange sync`() {
        provider.addEvent(id = 1L, calendarId = 10L, dirty = 1)
        provider.addCalendar(id = 10L, accountType = KairosProtonSync.ACCOUNT_TYPE)
        CalendarChangeObserver.init(app)

        notifyChanged(CalendarContract.Events.CONTENT_URI.buildUpon().appendPath("1").build())
        idle()

        assertTrue(protonWorkInfos().isNotEmpty())
        assertEquals(1, protonWorkInfos().size)
    }

    @Test
    fun `the sync's own writes - dirty 0 - never trigger the hook`() {
        provider.addEvent(id = 1L, calendarId = 10L, dirty = 0)
        provider.addCalendar(id = 10L, accountType = KairosProtonSync.ACCOUNT_TYPE)
        CalendarChangeObserver.init(app)

        notifyChanged(CalendarContract.Events.CONTENT_URI.buildUpon().appendPath("1").build())
        idle()

        assertTrue(protonWorkInfos().isEmpty())
    }

    @Test
    fun `rows of a foreign account type never trigger the hook`() {
        provider.addEvent(id = 1L, calendarId = 10L, dirty = 1)
        provider.addCalendar(id = 10L, accountType = "com.google")
        CalendarChangeObserver.init(app)

        notifyChanged(CalendarContract.Events.CONTENT_URI.buildUpon().appendPath("1").build())
        idle()

        assertTrue(protonWorkInfos().isEmpty())
    }

    @Test
    fun `a base-URI notification never triggers the hook`() {
        provider.addEvent(id = 1L, calendarId = 10L, dirty = 1)
        provider.addCalendar(id = 10L, accountType = KairosProtonSync.ACCOUNT_TYPE)
        CalendarChangeObserver.init(app)

        notifyChanged(CalendarContract.Events.CONTENT_URI)
        idle()

        assertTrue(protonWorkInfos().isEmpty())
    }

    @Test
    fun `the min-interval gate caps triggers at one per 15 minutes`() {
        provider.addEvent(id = 1L, calendarId = 10L, dirty = 1)
        provider.addCalendar(id = 10L, accountType = KairosProtonSync.ACCOUNT_TYPE)
        CalendarChangeObserver.init(app)

        notifyChanged(CalendarContract.Events.CONTENT_URI.buildUpon().appendPath("1").build())
        idle()
        notifyChanged(CalendarContract.Events.CONTENT_URI.buildUpon().appendPath("1").build())
        idle()

        // The second change lands within the 15-minute window: still a single work enqueue
        // (REPLACE would collapse them anyway, but the gate must not re-stamp).
        assertEquals(1, protonWorkInfos().size)
    }

    private fun resetGate() {
        app.getSharedPreferences("kairos_proton_onchange_gate", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun notifyChanged(uri: Uri) {
        app.contentResolver.notifyChange(uri, null)
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    private fun protonWorkInfos(): List<WorkInfo> =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork("proton-sync-calendar-onchange")
            .get()

    private object StubWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker =
            object : Worker(appContext, workerParameters) {
                override fun doWork() = Result.success()
            }
    }

    /**
     * Minimal provider for the observer's two queries: the dirty-row probe (with the
     * ACCOUNT_TYPE subquery over Calendars) and the Instances → EVENT_ID mapping.
     */
    private class FakeCalendarProvider : ContentProvider() {
        private val events = mutableMapOf<Long, MutableMap<String, Any?>>()
        private val calendars = mutableMapOf<Long, MutableMap<String, Any?>>()

        fun addEvent(
            id: Long,
            calendarId: Long,
            dirty: Int,
        ) {
            events[id] =
                mutableMapOf(
                    BaseColumns._ID to id,
                    CalendarContract.Events.CALENDAR_ID to calendarId,
                    CalendarContract.Events.DIRTY to dirty,
                )
        }

        fun addCalendar(
            id: Long,
            accountType: String,
        ) {
            calendars[id] =
                mutableMapOf(
                    BaseColumns._ID to id,
                    CalendarContract.Calendars.ACCOUNT_TYPE to accountType,
                )
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val matrix = MatrixCursor(arrayOf(BaseColumns._ID))
            val isEventsUri = uri.pathSegments.firstOrNull() == "events"
            val isCalendarsUri = uri.pathSegments.firstOrNull() == "calendars"
            when {
                isCalendarsUri -> {
                    // ACCOUNT_TYPE=? (args last = accountType): rows whose stored type matches.
                    val accountType = selectionArgs?.lastOrNull()
                    calendars.values.forEach {
                        if (it[CalendarContract.Calendars.ACCOUNT_TYPE] == accountType) {
                            matrix.addRow(arrayOf<Any?>(it[BaseColumns._ID]))
                        }
                    }
                }
                isEventsUri && selection != null && selection.contains(CalendarContract.Events.DIRTY) -> {
                    // The dirty probe, args [eventId, accountType]: DIRTY=1 AND _id=? AND
                    // calendar_id IN (SELECT _id FROM Calendars WHERE account_type=?). The
                    // event matches only when its calendar's stored type equals the arg.
                    val eventId = selectionArgs?.firstOrNull()?.toLongOrNull()
                    val accountType = selectionArgs?.getOrNull(1)
                    val row = events[eventId]
                    if (row != null && row[CalendarContract.Events.DIRTY] == 1) {
                        val calendarId = row[CalendarContract.Events.CALENDAR_ID]
                        val calendar = calendars[calendarId]
                        if (calendar != null &&
                            calendar[CalendarContract.Calendars.ACCOUNT_TYPE] == accountType
                        ) {
                            matrix.addRow(arrayOf<Any?>(row[BaseColumns._ID]))
                        }
                    }
                }
                else -> Unit
            }
            return matrix
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
