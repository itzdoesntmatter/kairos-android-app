package digital.tonima.protonsync.auth

import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AuthCodes
import digital.tonima.protonsync.api.dto.RefreshTokenRequest
import digital.tonima.protonsync.session.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight session refresh (auth-flow.md §3, security-audit §2.3.13).
 *
 * Exactly one refresh may be in flight process-wide: two concurrent `auth/v4/refresh` calls
 * with the same refresh token would invalidate each other (Proton rotates the refresh token
 * on every refresh). The [Mutex] serializes callers; a caller whose 401 carried a token that
 * another thread has since replaced is told "done" without issuing a second refresh (the
 * auth-flow dedupe rule: compare the request's Bearer against the session's current token).
 *
 * On success the new refresh token is persisted atomically BEFORE it is used (write-then-swap
 * inside [SessionManager.onRefreshed]); the scope is re-validated and a narrowed scope marks
 * `needs_reauth` (security-audit §2.3.14).
 */
class TokenRefresher(
    private val api: ProtonApiClient,
    private val sessionManager: SessionManager,
) {

    private val refreshMutex = Mutex()

    /**
     * Ensures a usable access token, refreshing only when none is in memory (on-demand
     * refresh at process start / sync begin — architecture §4).
     */
    suspend fun refreshIfNeeded(): Boolean {
        if (sessionManager.currentAccessToken() != null) return true
        return refreshAfterUnauthorized(staleToken = null)
    }

    /**
     * Called by the OkHttp authenticator on a 401. [staleToken] is the Bearer the failing
     * request carried; when another thread has already rotated the session, the mutex-waiting
     * caller returns true without refreshing (single-flight + dedupe, auth-flow.md §3).
     */
    suspend fun refreshAfterUnauthorized(staleToken: String?): Boolean =
        refreshMutex.withLock {
            val current = sessionManager.currentAccessToken()
            if (current != null && current != staleToken) {
                // Another thread refreshed while we waited — tokens are already fresh.
                return@withLock true
            }
            refreshNow()
        }

    private suspend fun refreshNow(): Boolean {
        val refreshToken = sessionManager.currentRefreshToken()
        val uid = sessionManager.currentUid()
        val response =
            if (refreshToken == null || uid == null) {
                null
            } else {
                runCatching {
                    api.refreshToken(
                        RefreshTokenRequest(refreshToken = refreshToken, uid = uid),
                    )
                }.getOrNull()
            }
        val adopted = response != null &&
            response.code == AuthCodes.SUCCESS &&
            sessionManager.onRefreshed(response)
        return if (adopted) true else fail()
    }

    private fun fail(): Boolean {
        sessionManager.markNeedsReauth()
        return false
    }
}
