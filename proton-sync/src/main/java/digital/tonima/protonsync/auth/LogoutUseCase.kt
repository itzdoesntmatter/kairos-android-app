package digital.tonima.protonsync.auth

import android.accounts.AccountManager
import android.content.Context
import androidx.work.WorkManager
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.sync.ProtonSyncScheduler
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.logcat

/**
 * Logout / session revocation (security-audit §2.3.15), in order:
 * 1. `DELETE /auth/v4` — best-effort server-side revocation. Offline logout still completes:
 *    a failure here never blocks local destruction (the audit's explicit requirement).
 * 2. Wipe `files/proton-session/` + clear in-memory tokens ([SessionManager.clear]).
 * 3. `AccountManager.removeAccountExplicitly` — the provider purges every calendar row the
 *    account owns, which is the row-deletion strategy on disconnect (architecture §2).
 * 4. Cancel all four unique sync works so no scheduled run fires against a dead session.
 *
 * Step 5 of the architecture's five-step list — clearing the Room sync-metadata DB — arrives
 * with the wave-2b `db/` package (the database does not exist in wave 2a); the seam is this
 * use case's single place to add it.
 */
class LogoutUseCase(
    private val context: Context,
    private val api: ProtonApiClient?,
    private val sessionManager: SessionManager,
) {

    suspend fun logout() {
        revokeServerSession()
        sessionManager.clear()
        removeAccount()
        cancelWorks()
    }

    private suspend fun revokeServerSession() {
        val api = api ?: return
        try {
            api.revokeSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort by contract: local material is destroyed regardless.
            logcat(TAG, LogPriority.WARN) {
                "server session revocation failed (continuing local logout): $e"
            }
        }
    }

    private fun removeAccount() {
        val manager = AccountManager.get(context)
        manager.getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE).forEach { account ->
            manager.removeAccountExplicitly(account)
        }
    }

    private fun cancelWorks() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ProtonSyncScheduler.WORK_PERIODIC)
        workManager.cancelUniqueWork(ProtonSyncScheduler.WORK_ONCE)
        workManager.cancelUniqueWork(ProtonSyncScheduler.WORK_ON_CHANGE)
        workManager.cancelUniqueWork(ProtonSyncScheduler.WORK_SESSION_REFRESH)
    }

    companion object {
        private const val TAG = "ProtonLogout"
    }
}
