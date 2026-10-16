package digital.tonima.protonsync.sync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Worker contract (architecture §8, security-audit S-6): permission gate, logged-out no-op,
 * the real-engine routing (incremental with the last-success cursor) and FAILURE → retry.
 */
@RunWith(RobolectricTestRunner::class)
class ProtonSyncWorkerTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProtonSyncModule.reset()
    }

    @Test
    fun `a granted worker runs the engine incrementally and reports success`() =
        runTest {
            grantPermissions()
            registerAccount()
            val engine = CountingEngine()

            val result = worker(engine).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, engine.incrementalCalls)
            assertEquals(0, engine.fullCalls)
        }

    @Test
    fun `without calendar permissions the engine is never called`() =
        runTest {
            registerAccount()
            val engine = CountingEngine()

            val result = worker(engine).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(0, engine.incrementalCalls)
        }

    @Test
    fun `a logged-out worker no-ops without calling the engine - S6`() =
        runTest {
            grantPermissions()
            val engine = CountingEngine()

            val result = worker(engine).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(0, engine.incrementalCalls)
        }

    @Test
    fun `an engine failure asks WorkManager to retry`() =
        runTest {
            grantPermissions()
            registerAccount()

            val result = worker(FailingEngine()).doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `the last-sync cursor is passed to the engine`() =
        runTest {
            grantPermissions()
            registerAccount()
            val statusProvider = ProtonSyncStatusProvider()
            val engine = TrackingEngine()

            worker(engine, statusProvider).doWork()

            assertEquals(0L, engine.receivedSince)
        }

    private fun worker(
        engine: SyncEngine,
        statusProvider: ProtonSyncStatusProvider = ProtonSyncStatusProvider(),
    ): ProtonSyncWorker = ProtonSyncWorker(context, mockk<WorkerParameters>(relaxed = true), engine, statusProvider)

    private fun grantPermissions() {
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }

    private fun registerAccount() {
        AccountManager.get(context).addAccountExplicitly(
            Account("alice@proton.me", ProtonSyncAccount.ACCOUNT_TYPE),
            null,
            null,
        )
    }

    private class CountingEngine : SyncEngine {
        var fullCalls: Int = 0
        var incrementalCalls: Int = 0

        override suspend fun syncFull(): SyncResult {
            fullCalls++
            return SyncResult.success()
        }

        override suspend fun syncIncremental(since: Long): SyncResult {
            incrementalCalls++
            return SyncResult.success()
        }
    }

    private class TrackingEngine : SyncEngine {
        var receivedSince: Long? = null

        override suspend fun syncFull(): SyncResult = SyncResult.success()

        override suspend fun syncIncremental(since: Long): SyncResult {
            receivedSince = since
            return SyncResult.success()
        }
    }

    private class FailingEngine : SyncEngine {
        override suspend fun syncFull(): SyncResult = SyncResult.failure("engine exploded")

        override suspend fun syncIncremental(since: Long): SyncResult = SyncResult.failure("engine exploded")
    }
}
