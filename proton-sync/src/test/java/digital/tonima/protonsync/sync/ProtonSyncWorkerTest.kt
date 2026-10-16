package digital.tonima.protonsync.sync

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ProtonSyncWorkerTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a granted worker runs the engine and reports success`() =
        runTest {
            grantPermissions()
            val engine = CountingEngine()

            val result = worker(engine).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, engine.fullCalls)
        }

    @Test
    fun `without calendar permissions the engine is never called`() =
        runTest {
            val engine = CountingEngine()

            val result = worker(engine).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(0, engine.fullCalls)
        }

    @Test
    fun `the wave-1 stub engine reports success with a note`() =
        runTest {
            grantPermissions()

            assertEquals(
                ListenableWorker.Result.success(),
                worker(SyncEngine.NotImplemented).doWork(),
            )
        }

    @Test
    fun `an engine failure asks WorkManager to retry`() =
        runTest {
            grantPermissions()

            val result = worker(FailingEngine()).doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    private fun worker(engine: SyncEngine): ProtonSyncWorker =
        ProtonSyncWorker(context, mockk<WorkerParameters>(relaxed = true), engine)

    private fun grantPermissions() {
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }

    private class CountingEngine : SyncEngine {
        var fullCalls: Int = 0

        override suspend fun syncFull(): SyncResult {
            fullCalls++
            return SyncResult.success()
        }

        override suspend fun syncIncremental(since: Long): SyncResult = SyncResult.success()
    }

    private class FailingEngine : SyncEngine {
        override suspend fun syncFull(): SyncResult = SyncResult.failure("engine exploded")

        override suspend fun syncIncremental(since: Long): SyncResult = SyncResult.failure("engine exploded")
    }
}
