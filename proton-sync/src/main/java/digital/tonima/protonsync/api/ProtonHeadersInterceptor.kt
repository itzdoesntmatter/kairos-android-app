package digital.tonima.protonsync.api

import digital.tonima.protonsync.session.SessionManager
import logcat.LogPriority
import logcat.logcat
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Common Proton request headers (auth-flow.md §1, architecture §6).
 *
 * Every request carries the honest client identity (invariant I6, security-audit §4.5):
 * `x-pm-appversion: Kairos_<versionCode>`, a truthful `User-Agent`, `x-pm-apiversion: 3` and
 * the Proton v1 Accept header. `Authorization: Bearer …` + `x-pm-uid` are attached only when
 * a session exists, and never on the two endpoints the protocol exempts (`/auth/v4/info` and
 * `POST /auth/v4` — auth-flow.md §1).
 *
 * Human verification (auth-flow.md §2.7): a pending HV token is replayed on the NEXT
 * `POST /auth/v4` and consumed (single-use, in-memory — never persisted, security-audit LO-3).
 *
 * Never impersonates Proton's own client identifiers — that is a hard rule, not style (I6).
 */
class ProtonHeadersInterceptor(
    private val sessionManager: SessionManager,
    private val appVersionHeader: String,
    private val userAgent: String,
    private val debugRequestLogging: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
            .header(HEADER_APP_VERSION, appVersionHeader)
            .header(HEADER_API_VERSION, API_VERSION)
            .header("Accept", ACCEPT_HEADER)
            .header("User-Agent", userAgent)

        val authExempt = isAuthExempt(request.method, request.url.encodedPath)
        if (!authExempt) {
            val accessToken = sessionManager.currentAccessToken()
            val uid = sessionManager.currentUid()
            if (accessToken != null && uid != null) {
                builder.header("Authorization", "Bearer $accessToken")
                builder.header(HEADER_UID, uid)
            }
        } else if (request.method == "POST" && request.url.encodedPath == PATH_AUTH) {
            sessionManager.consumeHvToken()?.let { token ->
                builder.header(HEADER_HV_TOKEN, token)
                builder.header(HEADER_HV_TOKEN_TYPE, "captcha")
            }
        }

        val finalRequest = builder.build()
        if (debugRequestLogging) {
            // Diag breadcrumb: the FINAL outgoing request after all header attachment
            // (bodies never logged; Authorization withheld even in diag builds).
            val safeHeaders =
                finalRequest.headers
                    .filterNot { it.first.equals("Authorization", ignoreCase = true) }
                    .joinToString("; ") { "${it.first}=${it.second}" }
            logcat("ProtonLoginFlow", LogPriority.INFO) {
                "request ${finalRequest.method} ${finalRequest.url} headers=$safeHeaders" +
                    " bodyLen=" + (finalRequest.body?.contentLength() ?: 0)
            }
        }
        return chain.proceed(finalRequest)
    }

    companion object {
        const val HEADER_APP_VERSION = "x-pm-appversion"
        const val HEADER_API_VERSION = "x-pm-apiversion"
        const val HEADER_UID = "x-pm-uid"
        const val HEADER_HV_TOKEN = "x-pm-human-verification-token"
        const val HEADER_HV_TOKEN_TYPE = "x-pm-human-verification-token-type"
        const val API_VERSION = "3"
        const val ACCEPT_HEADER = "application/vnd.protonmail.v1+json"

        internal const val PATH_AUTH = "/auth/v4"
        internal const val PATH_AUTH_INFO = "/auth/v4/info"

        /** The two auth-header-exempt endpoints (auth-flow.md §1). */
        fun isAuthExempt(method: String, path: String): Boolean =
            path == PATH_AUTH_INFO || (path == PATH_AUTH && method == "POST")
    }
}
