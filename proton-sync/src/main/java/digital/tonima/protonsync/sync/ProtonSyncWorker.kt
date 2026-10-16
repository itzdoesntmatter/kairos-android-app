package digital.tonima.protonsync.sync

import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import digital.tonima.protonsync.calendar.CalendarPermissionException

/**
 * Runs one Proton sync pass. Wave-1 skeleton: permission precondition + the [SyncEngine] seam;
 * the default engine is [SyncEngine.NotImplemented], so the worker compiles and runs (reporting
 * success-with-note) before the Proton client exists.
 *
 * Permission handling: sync cannot proceed without calendar access, and retrying cannot fix a
 * user decision — so a permission-less run reports success and waits for the user to grant
 * access (the scheduler re-asserts periodic work on every app start, which resumes the cadence).
 */
class ProtonSyncWorker
    @JvmOverloads
    constructor(
        context: Context,
        params: WorkerParameters,
        private val engine: SyncEngine = SyncEngine.NotImplemented,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val missing =
                CalendarPermissionException.REQUIRED_PERMISSIONS.filter {
                    applicationContext.checkSelfPermission(it) != PERMISSION_GRANTED
                }
            if (missing.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Skipping Proton sync: missing calendar permissions $missing. " +
                        "Retry cannot fix this; periodic work resumes once the user grants access.",
                )
                return Result.success()
            }

            // Wave-1 skeleton runs a full sync. Wave 2 routes periodic runs to
            // syncIncremental(since) using the last-sync cursor from the sync-metadata DB.
            val result = engine.syncFull()
            return when (result.status) {
                SyncResult.Status.SUCCESS, SyncResult.Status.SUCCESS_WITH_NOTE -> Result.success()
                SyncResult.Status.FAILURE -> Result.retry()
            }
        }

        private companion object {
            const val TAG = "ProtonSyncWorker"
        }
    }
