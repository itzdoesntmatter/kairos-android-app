package digital.tonima.protonsync.auth

import android.accounts.AccountManager
import android.content.Context
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.crypto.KeysStore
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.db.SyncDatabase
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import digital.tonima.protonsync.sync.ProtonSyncScheduler
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.logcat

/**
 * Logout / session revocation (security-audit §2.3.15), all five steps in order, each
 * completing even when the previous one failed:
 * 1. `DELETE /auth/v4` — best-effort server-side revocation. Offline logout still completes:
 *    a failure here never blocks local destruction (the audit's explicit requirement).
 * 2. Wipe `files/proton-session/` (session.dat AND keys.dat) + clear in-memory tokens
 *    ([SessionManager.clear]) + close the keyring (unlocked material zeroized).
 * 3. `AccountManager.removeAccountExplicitly` — the provider purges every calendar row the
 *    account owns, which is the row-deletion strategy on disconnect (architecture §2).
 * 4. Clear the Room sync-metadata DB (event map, tombstones, calendar state, counters).
 * 5. Cancel all four unique sync works so no scheduled run fires against a dead session
 *    (security-audit S-6).
 * The status provider is reset to the logged-out state so the settings card flips
 * immediately.
 */
class LogoutUseCase(
    private val context: Context,
    private val api: ProtonApiClient?,
    private val sessionManager: SessionManager,
    private val keysStore: KeysStore? = null,
    private val keyring: ProtonCryptoKeyring? = null,
    private val statusProvider: ProtonSyncStatusProvider? = null,
    private val database: SyncDatabase? = null,
) {

    suspend fun logout() {
        revokeServerSession()
        sessionManager.clear()
        keysStore?.wipe()
        keyring?.close()
        removeAccount()
        cancelWorks()
        database?.clearAllTables()
        statusProvider?.onLoggedOut()
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
        ProtonSyncScheduler.cancelAll(context)
    }

    companion object {
        private const val TAG = "ProtonLogout"
    }
}
