package digital.tonima.protonsync.session

import digital.tonima.protonsync.api.ProtonSessionProvider
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.RefreshTokenResponse

/**
 * The single in-memory authority over the current Proton session (D5): the access token lives
 * ONLY here (never persisted — security-audit.md §2.4.18), while the refresh token + UID +
 * scope are mirrored into [SessionStore] on every change.
 *
 * Threading: all fields are `@Volatile` and the state transitions are trivially atomic, so
 * the OkHttp authenticator, the token refresher and the UI can read a consistent snapshot
 * from any thread. Persisted writes are serialized by the callers ([TokenRefresher]'s
 * single-flight mutex; [onAuthenticated] runs once per login).
 */
class SessionManager(
    private val store: SessionStore,
    private val scopeValidator: ScopeValidator = ScopeValidator(),
) : ProtonSessionProvider {

    @Volatile private var accessToken: String? = null
    @Volatile private var uid: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var scope: String? = null
    @Volatile private var state: SessionState = SessionState.LOGGED_OUT

    /**
     * Pending human-verification token (auth-flow.md §2.7). In-memory only, replayed on the
     * next `POST /auth/v4` by the header interceptor and cleared after use — the reference
     * app's static-field pattern is explicitly NOT replicated (security-audit LO-3).
     */
    @Volatile private var hvToken: String? = null

    fun setHvToken(token: String) {
        hvToken = token
    }

    /** Returns and clears the pending HV token — single-use by design. */
    fun consumeHvToken(): String? {
        val token = hvToken
        hvToken = null
        return token
    }

    /** Reads the persisted session back into memory. Call once at process start. */
    fun initialize() {
        val stored = store.load() ?: return
        uid = stored.uid
        refreshToken = stored.refreshToken
        scope = stored.scope
        state = SessionState.ACTIVE
        // The access token is rebuilt lazily by the first 401 → refresh cycle.
    }

    /** Adopts a successful `auth/v4` response. Returns false when the response is unusable. */
    fun onAuthenticated(response: AuthResponse): Boolean =
        adoptTokens(response.accessToken, response.refreshToken, response.uid, response.scope)

    /**
     * Rotates tokens after a successful refresh (auth-flow.md §3): the new refresh token is
     * persisted atomically BEFORE it is used, and scope is re-validated per security-audit
     * §2.3.14. Returns false (leaving the old tokens untouched) on a narrowed scope.
     */
    fun onRefreshed(response: RefreshTokenResponse): Boolean =
        adoptTokens(response.accessToken, response.refreshToken, response.uid, response.scope)

    private fun adoptTokens(
        access: String?,
        refresh: String?,
        newUid: String?,
        newScope: String?,
    ): Boolean {
        if (access == null || refresh == null || newUid == null || newScope == null) return false
        // Security-audit §2.3.14: never proceed with reduced privileges.
        if (!scopeValidator.isValid(newScope)) return false
        accessToken = access
        uid = newUid
        refreshToken = refresh
        scope = newScope
        state = SessionState.ACTIVE
        store.save(StoredSession(uid = newUid, refreshToken = refresh, scope = newScope))
        return true
    }

    /** Refresh failed and the session is unrecoverable — the UI must offer re-login. */
    fun markNeedsReauth() {
        state = SessionState.NEEDS_REAUTH
    }

    /** Wipes memory and disk state (logout step 2, security-audit §2.3.15). */
    fun clear() {
        accessToken = null
        uid = null
        refreshToken = null
        scope = null
        hvToken = null
        state = SessionState.LOGGED_OUT
        store.wipe()
    }

    override fun currentAccessToken(): String? = accessToken

    override fun currentUid(): String? = uid

    fun currentRefreshToken(): String? = refreshToken

    fun currentScope(): String? = scope

    fun currentState(): SessionState = state

    /** True when the headers interceptor may attach `Authorization` (an auth endpoint call). */
    fun hasSession(): Boolean = accessToken != null && uid != null
}

enum class SessionState {
    /** No session material anywhere. */
    LOGGED_OUT,

    /** Session valid (or at least refreshable) and usable. */
    ACTIVE,

    /** Refresh rejected the session; only re-login can recover. */
    NEEDS_REAUTH,
}

/**
 * Calendar-access scope check (security-audit §2.3.14, applied after auth AND after every
 * refresh): a valid session must cover calendar access. Proton scopes are whitespace-separated
 * tokens; `full` implies everything, `calendar` is the narrow grant we need. Anything else is
 * treated as a narrowed scope → `needs_reauth`, never silent reduced privileges.
 */
class ScopeValidator {

    fun isValid(scope: String): Boolean {
        val tokens = scope.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.any { it.equals("full", ignoreCase = true) } ||
            tokens.any { it.equals("calendar", ignoreCase = true) }
    }
}
