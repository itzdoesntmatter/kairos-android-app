package digital.tonima.protonsync.api

import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionStore
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Gap-fill for [ProtonHeadersInterceptorTest] (specs/test-plan.md API-04): the auth-header
 * exemption rules are method-sensitive and the HV replay is single-use, so the edges that matter
 * are the non-POST verbs on `/auth/v4` (which must KEEP the session headers — the reference
 * exemption is `GET /auth/v4/info` + `POST /auth/v4` only, auth-flow.md §1) and the fact that a
 * non-auth call cannot burn the pending human-verification token.
 */
class AuthHeadersGapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: SessionManager
    private lateinit var interceptor: ProtonHeadersInterceptor

    @Before
    fun setUp() {
        manager = SessionManager(SessionStore(File(tempFolder.root, "session.dat"), FakeCrypto()))
        interceptor = ProtonHeadersInterceptor(
            sessionManager = manager,
            appVersionHeader = "Kairos_42",
            userAgent = "Kairos/1.0.0 (Android 37; test)",
        )
    }

    @Test
    fun `a GET on the auth path is not exempt — it keeps the session headers`() {
        login()

        val response = intercept("GET", "https://mail-api.proton.me/auth/v4")

        assertEquals("Bearer access-token", response.request.header("Authorization"))
        assertEquals("uid-1", response.request.header("x-pm-uid"))
    }

    @Test
    fun `the revoke call carries the session headers`() {
        // Logout revokes via DELETE /auth/v4 (auth-flow.md §4) — it must authenticate as the
        // session being revoked, so the exemption can never be path-only.
        login()

        val response = intercept("DELETE", "https://mail-api.proton.me/auth/v4")

        assertEquals("Bearer access-token", response.request.header("Authorization"))
        assertEquals("uid-1", response.request.header("x-pm-uid"))
    }

    @Test
    fun `a pending HV token survives a non-auth call and is consumed only by POST auth v4`() {
        manager.setHvToken("hv-token")

        val info = intercept("POST", "https://mail-api.proton.me/auth/v4/info")
        val calendar = intercept("GET", "https://mail-api.proton.me/calendar/v1")
        val auth = intercept("POST", "https://mail-api.proton.me/auth/v4")
        val replay = intercept("POST", "https://mail-api.proton.me/auth/v4")

        assertNull(info.request.header("x-pm-human-verification-token"))
        assertNull(calendar.request.header("x-pm-human-verification-token"))
        assertEquals("hv-token", auth.request.header("x-pm-human-verification-token"))
        assertEquals("captcha", auth.request.header("x-pm-human-verification-token-type"))
        assertNull(replay.request.header("x-pm-human-verification-token"))
    }

    @Test
    fun `an inbound Authorization header is replaced by the real session token`() {
        login()

        val response = intercept("GET", "https://mail-api.proton.me/calendar/v1", "Authorization" to "Bearer spoofed")

        assertEquals("Bearer access-token", response.request.header("Authorization"))
    }

    @Test
    fun `inbound identity headers are overwritten by the honest client identity`() {
        // I6 is an invariant over what leaves the process: a value already on the request (e.g.
        // set by a caller or a previous interceptor copy) must not survive.
        val response = intercept(
            "GET",
            "https://mail-api.proton.me/calendar/v1",
            "User-Agent" to "ProtonMail/5.0.0 (Android)",
            "x-pm-appversion" to "ProtonMail",
        )

        assertEquals("Kairos/1.0.0 (Android 37; test)", response.request.header("User-Agent"))
        assertEquals("Kairos_42", response.request.header("x-pm-appversion"))
    }

    @Test
    fun `while logged out an inbound Authorization header is left untouched — observation O-1`() {
        // Observation O-1 (LOD-12, not a defect at the current call graph): the interceptor only
        // ADDS session headers, it never strips a caller-supplied Authorization while logged out.
        // All callers are internal and never pre-set the header, so nothing leaks today; worth
        // knowing if a future flow ever forwards a request through this client.
        val response = intercept("GET", "https://mail-api.proton.me/calendar/v1", "Authorization" to "Bearer foreign")

        assertEquals("Bearer foreign", response.request.header("Authorization"))
        assertNull(response.request.header("x-pm-uid"))
    }

    private fun login() {
        manager.onAuthenticated(
            digital.tonima.protonsync.api.dto.AuthResponse(
                code = 1000,
                accessToken = "access-token",
                refreshToken = "refresh",
                uid = "uid-1",
                scope = "full",
            ),
        )
    }

    private fun intercept(method: String, url: String, vararg headers: Pair<String, String>): Response {
        val body = if (method == "POST" || method == "DELETE") ByteArray(0).toRequestBody() else null
        val builder = Request.Builder().url(url).method(method, body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        val chain = io.mockk.mockk<Interceptor.Chain>(relaxed = true)
        io.mockk.every { chain.request() } returns request
        io.mockk.every { chain.proceed(any()) } answers {
            val outgoing: Request = firstArg()
            Response.Builder()
                .request(outgoing)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        return interceptor.intercept(chain)
    }

    private class FakeCrypto : digital.tonima.protonsync.session.SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(7) + plaintext
        override fun decrypt(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)
    }
}
