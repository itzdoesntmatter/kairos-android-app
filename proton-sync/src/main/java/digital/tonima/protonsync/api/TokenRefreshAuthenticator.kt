package digital.tonima.protonsync.api

import digital.tonima.protonsync.auth.TokenRefresher
import digital.tonima.protonsync.session.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 401 handling (auth-flow.md §3): refresh once per request chain, single-flight, and replay.
 *
 * - Auth endpoints are exempt from the refresh cycle (`/auth/v4`, `/auth/v4/info`,
 *   `/auth/v4/2fa`, `/auth/v4/refresh`) — a 401 there is a real auth failure.
 * - At most ONE refresh per request chain: the replayed request carries a marker tag; a
 *   second 401 on it aborts (surfaces as an error instead of looping).
 * - Single-flight + dedupe live in [TokenRefresher]; if another thread already rotated the
 *   session, the replay happens without a second refresh.
 * - On refresh failure the session is marked `needs_reauth` and the request fails — the
 *   caller must stop retrying (architecture §8).
 *
 * OkHttp's `Authenticator` interface is synchronous; `runBlocking` on the (bounded,
 * single-flight) refresh is the standard pattern for coroutine-backed session state and runs
 * on OkHttp's dispatcher threads, not the caller's.
 */
class TokenRefreshAuthenticator(
    private val sessionManager: SessionManager,
    private val tokenRefresher: TokenRefresher,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val original = response.request
        val eligible = response.code == 401 &&
            original.tag(RetriedTag::class.java) == null &&
            !isRefreshExempt(original.method, original.url.encodedPath)
        if (!eligible) return null

        val staleToken = original.header("Authorization")?.removePrefix("Bearer ")
        val refreshed = staleToken != null &&
            runBlocking { tokenRefresher.refreshAfterUnauthorized(staleToken) }
        val newToken = if (refreshed) sessionManager.currentAccessToken() else null
        return newToken?.let { token ->
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .tag(RetriedTag::class.java, RetriedTag)
                .build()
        }
    }

    private object RetriedTag

    companion object {
        /**
         * The auth endpoints the reference exempts from 401-refresh (auth-flow.md §3);
         * identical set to [ProtonHeadersInterceptor.isAuthExempt] plus the remaining
         * `/auth/v4` verb/path forms.
         */
        fun isRefreshExempt(method: String, path: String): Boolean =
            path == ProtonHeadersInterceptor.PATH_AUTH ||
                path == ProtonHeadersInterceptor.PATH_AUTH_INFO ||
                path == PATH_2FA ||
                path == PATH_REFRESH

        private const val PATH_2FA = "/auth/v4/2fa"
        private const val PATH_REFRESH = "/auth/v4/refresh"
    }
}
