package digital.tonima.protonsync.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import digital.tonima.protonsync.account.ProtonSyncAccount
import java.util.concurrent.TimeUnit

/**
 * Single entry point for scheduling Proton sync work (architecture spec §7). All work names
 * are unique and live here as constants — they are part of the sync contract (§0) and are the
 * identity WorkManager uses for KEEP/REPLACE semantics across app restarts.
 *
 * Boot/reschedule safety: WorkManager persists scheduled work across reboots by itself, so no
 * BootReceiver is needed. The app re-asserts [ensurePeriodic] from Application.onCreate — the
 * unique name + KEEP policy make the call idempotent, and the account guard keeps the schedule
 * empty for logged-out users (no wakeups, no 401 spam).
 */
object ProtonSyncScheduler {
    const val WORK_PERIODIC = "proton-sync-calendar-periodic"
    const val WORK_ONCE = "proton-sync-calendar-once"
    const val WORK_ON_CHANGE = "proton-sync-calendar-onchange"
    const val WORK_SESSION_REFRESH = "proton-sync-session-refresh"

    /**
     * Ensures the periodic sync exists (KEEP: never replaces an existing schedule). Returns the
     * request that was enqueued, or null when no Proton account is registered — syncing a
     * logged-out user would only produce wakeups and 401s.
     */
    fun ensurePeriodic(context: Context): PeriodicWorkRequest? {
        if (!ProtonSyncAccount.hasAccount(context)) return null
        val request =
            PeriodicWorkRequestBuilder<ProtonSyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(syncConstraints())
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        return request
    }

    /**
     * Enqueues (or replaces) an expedited one-shot sync — login completion and the manual
     * "Sync now" button. REPLACE keeps at most one pending immediate sync.
     */
    fun enqueueImmediateSync(context: Context): OneTimeWorkRequest {
        val request =
            OneTimeWorkRequestBuilder<ProtonSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(syncConstraints())
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.REPLACE, request)
        return request
    }

    private fun syncConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /**
     * 15 minutes — WorkManager's floor for periodic work. LOD-5 sets this cadence; the
     * architecture draft's 6 h suggestion (matching the reference app) is a later tuning call
     * once real-world battery behaviour is observed.
     */
    private const val PERIODIC_INTERVAL_MINUTES = 15L
}
