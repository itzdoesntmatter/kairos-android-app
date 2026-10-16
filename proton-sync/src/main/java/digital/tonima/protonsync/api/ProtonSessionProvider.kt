package digital.tonima.protonsync.api

/**
 * What the header interceptor and authenticator need from the session, narrowed so tests can
 * fake it without a full [digital.tonima.protonsync.session.SessionManager].
 */
interface ProtonSessionProvider {
    /** Bearer token for the `Authorization` header, or null when logged out. */
    fun currentAccessToken(): String?

    /** Proton user ID for the `x-pm-uid` header, or null when logged out. */
    fun currentUid(): String?
}
