package digital.tonima.protonsync.sync

import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.calendar.CalendarPermissionException
import digital.tonima.protonsync.status.ProtonSyncStatusProvider

/**
 * Runs one Proton sync pass through the real engine (wave 2b).
 *
 * Guards, in order:
 * 1. Calendar permissions — a permission-less run reports success and waits for the user to
 *    grant access (the scheduler re-asserts periodic work on every app start, which resumes
 *    the cadence). Retry cannot fix a user decision.
 * 2. Logged-out guard (security-audit S-6): with no Proton account the worker no-ops with a
 *    warning — scheduled work is cancelled at logout, but this makes a race between logout
 *    and an in-flight schedule harmless.
 * 3. The engine. FAILURE hands retry policy to WorkManager backoff (architecture §8); the
 *    engine's own per-event isolation guarantees a poisoned event never fails a run.
 */
class ProtonSyncWorker
    @JvmOverloads
    constructor(
        context: Context,
        params: WorkerParameters,
        private val engine: SyncEngine? = null,
        private val statusProvider: ProtonSyncStatusProvider? = null,
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
            if (!ProtonSyncAccount.hasAccount(applicationContext)) {
                Log.w(TAG, "No Proton account — skipping sync (logged out).")
                return Result.success()
            }

            val provider = statusProvider ?: ProtonSyncModule.statusProvider(applicationContext)
            val resolvedEngine = engine ?: ProtonSyncModule.buildEngine(applicationContext)
            // Incremental by default: the last-success cursor from the status provider bounds
            // the window; a zero cursor (fresh login) behaves as a full sync.
            val since = provider.state.value.lastSyncEpochMs ?: 0L
            val result =
                try {
                    resolvedEngine.syncIncremental(since)
                } catch (e: Exception) {
                    Log.e(TAG, "Proton sync engine crashed the run", e)
                    SyncResult.failure("engine exception: ${e.message}")
                }
            return when (result.status) {
                SyncResult.Status.SUCCESS, SyncResult.Status.SUCCESS_WITH_NOTE -> Result.success()
                SyncResult.Status.FAILURE -> Result.retry()
            }
        }

        private companion object {
            const val TAG = "ProtonSyncWorker"
        }
    }
