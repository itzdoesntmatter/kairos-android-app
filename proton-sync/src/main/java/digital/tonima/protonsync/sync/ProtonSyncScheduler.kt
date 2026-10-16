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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Single entry point for scheduling Proton sync work (architecture spec §7). All work names
 * are unique and live here as constants — they are part of the sync contract (§0) and are the
 * identity WorkManager uses for KEEP/REPLACE semantics across app restarts.
 *
 * Cadence (operator decision `cadence: configurable`, §7): the periodic interval comes from
 * `sync_interval_hours` ∈ {1, 6, 12, 24} (default 6 h) in the bridge DataStore. Changing the
 * setting re-enqueues with REPLACE so the new interval takes effect immediately.
 *
 * Boot/reschedule safety: WorkManager persists scheduled work across reboots by itself, so no
 * BootReceiver is needed. The app re-asserts [ensurePeriodic] from Application.onCreate — the
 * unique name + KEEP policy make the call idempotent, and the account guard keeps the schedule
 * empty for logged-out users (no wakeups, no 401 spam).
 *
 * Logout (security-audit S-6): [cancelAll] removes every unique work; the worker additionally
 * no-ops when the account is gone.
 */
object ProtonSyncScheduler {
    const val WORK_PERIODIC = "proton-sync-calendar-periodic"
    const val WORK_ONCE = "proton-sync-calendar-once"
    const val WORK_ON_CHANGE = "proton-sync-calendar-onchange"
    const val WORK_SESSION_REFRESH = "proton-sync-session-refresh"

    /**
     * Ensures the periodic sync exists at the configured cadence (KEEP: never replaces an
     * existing schedule). Fire-and-forget for Application.onCreate — the account check is
     * synchronous and cheap; the DataStore read lands on IO and then enqueues. No-op when no
     * Proton account is registered.
     */
    fun ensurePeriodic(context: Context) {
        if (!ProtonSyncAccount.hasAccount(context)) return
        CoroutineScope(Dispatchers.IO).launch {
            ensurePeriodicNow(context)
        }
    }

    /**
     * Awaitable variant of [ensurePeriodic] — used by the fire-and-forget path and by tests.
     * Returns the enqueued request, or null when logged out.
     */
    suspend fun ensurePeriodicNow(context: Context): PeriodicWorkRequest? {
        if (!ProtonSyncAccount.hasAccount(context)) return null
        val hours = ProtonSyncPreferences.syncIntervalHours(context).first()
        return enqueuePeriodic(context, hours, ExistingPeriodicWorkPolicy.KEEP)
    }

    /**
     * Re-enqueues the periodic work with the CURRENT DataStore interval using REPLACE — the
     * settings screen's frequency-selector path (a changed interval takes effect now).
     */
    suspend fun reapplyInterval(context: Context): PeriodicWorkRequest? {
        if (!ProtonSyncAccount.hasAccount(context)) return null
        val hours = ProtonSyncPreferences.syncIntervalHours(context).first()
        return enqueuePeriodic(context, hours, ExistingPeriodicWorkPolicy.REPLACE)
    }

    /** Builds and enqueues the periodic request for [intervalHours]. */
    fun enqueuePeriodic(
        context: Context,
        intervalHours: Int,
        policy: ExistingPeriodicWorkPolicy,
    ): PeriodicWorkRequest {
        val request =
            PeriodicWorkRequestBuilder<ProtonSyncWorker>(
                ProtonSyncPreferences.intervalMinutes(intervalHours),
                TimeUnit.MINUTES,
            )
                .setConstraints(syncConstraints())
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, policy, request)
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

    /**
     * The CalendarChangeObserver hook (architecture §1 item 6): a user edit of one of our
     * rows enqueues an onchange sync (REPLACE, debounced by the observer). No-op while
     * logged out (S-7: never schedule a doomed run).
     */
    fun enqueueOnChangeSync(context: Context): OneTimeWorkRequest? {
        if (!ProtonSyncAccount.hasAccount(context)) return null
        val request =
            OneTimeWorkRequestBuilder<ProtonSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(syncConstraints())
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_ON_CHANGE, ExistingWorkPolicy.REPLACE, request)
        return request
    }

    /** Logout step: cancel every unique sync work (security-audit S-6, §2.3.15e). */
    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_PERIODIC)
        workManager.cancelUniqueWork(WORK_ONCE)
        workManager.cancelUniqueWork(WORK_ON_CHANGE)
        workManager.cancelUniqueWork(WORK_SESSION_REFRESH)
    }

    private fun syncConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}
