package digital.tonima.protonsync.api

import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionStore
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProtonHeadersInterceptorTest {

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
    fun `every request carries the honest identity headers`() {
        val response = intercept("GET", "https://mail-api.proton.me/calendar/v1")

        assertEquals("Kairos_42", response.request.header("x-pm-appversion"))
        assertEquals("3", response.request.header("x-pm-apiversion"))
        assertEquals("application/vnd.protonmail.v1+json", response.request.header("Accept"))
        assertEquals("Kairos/1.0.0 (Android 37; test)", response.request.header("User-Agent"))
        // I6: the client identity never impersonates Proton's own identifiers.
        assertFalse(response.request.header("User-Agent")!!.contains("ProtonMail"))
        assertFalse(response.request.header("x-pm-appversion")!!.contains("ProtonMail"))
    }

    @Test
    fun `session headers are attached to non-auth endpoints when logged in`() {
        login()
        val response = intercept("GET", "https://mail-api.proton.me/calendar/v1")

        assertEquals("Bearer access-token", response.request.header("Authorization"))
        assertEquals("uid-1", response.request.header("x-pm-uid"))
    }

    @Test
    fun `auth info is exempt from session headers`() {
        login()
        val response = intercept("POST", "https://mail-api.proton.me/auth/v4/info")

        assertNull(response.request.header("Authorization"))
        assertNull(response.request.header("x-pm-uid"))
    }

    @Test
    fun `auth v4 is exempt from session headers`() {
        login()
        val response = intercept("POST", "https://mail-api.proton.me/auth/v4")

        assertNull(response.request.header("Authorization"))
        assertNull(response.request.header("x-pm-uid"))
    }

    @Test
    fun `two-factor and refresh keep their session headers`() {
        login()

        val twoFa = intercept("POST", "https://mail-api.proton.me/auth/v4/2fa")
        val refresh = intercept("POST", "https://mail-api.proton.me/auth/v4/refresh")

        assertEquals("Bearer access-token", twoFa.request.header("Authorization"))
        assertEquals("Bearer access-token", refresh.request.header("Authorization"))
    }

    @Test
    fun `no session headers while logged out`() {
        val response = intercept("GET", "https://mail-api.proton.me/calendar/v1")

        assertNull(response.request.header("Authorization"))
        assertNull(response.request.header("x-pm-uid"))
    }

    @Test
    fun `a pending HV token is replayed once on auth v4 and consumed`() {
        manager.setHvToken("hv-token")

        val first = intercept("POST", "https://mail-api.proton.me/auth/v4")
        val second = intercept("POST", "https://mail-api.proton.me/auth/v4")

        assertEquals("hv-token", first.request.header("x-pm-human-verification-token"))
        assertEquals("captcha", first.request.header("x-pm-human-verification-token-type"))
        assertNull(second.request.header("x-pm-human-verification-token"))
    }

    @Test
    fun `HV token is never attached to non-auth endpoints`() {
        manager.setHvToken("hv-token")

        val response = intercept("GET", "https://mail-api.proton.me/calendar/v1")

        assertNull(response.request.header("x-pm-human-verification-token"))
        assertEquals("hv-token", manager.consumeHvToken())
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

    private fun intercept(method: String, url: String): Response {
        val body = if (method == "POST") ByteArray(0).toRequestBody() else null
        val request = Request.Builder().url(url).method(method, body).build()
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
