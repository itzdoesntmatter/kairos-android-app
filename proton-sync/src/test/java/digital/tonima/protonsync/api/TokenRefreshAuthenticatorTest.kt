package digital.tonima.protonsync.api

import digital.tonima.protonsync.auth.TokenRefresher
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TokenRefreshAuthenticatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: SessionManager
    private lateinit var api: ProtonApiClient
    private lateinit var refresher: TokenRefresher
    private lateinit var authenticator: TokenRefreshAuthenticator

    @Before
    fun setUp() {
        manager = SessionManager(SessionStore(File(tempFolder.root, "session.dat"), FakeCrypto()))
        api = mockk(relaxed = true)
        refresher = TokenRefresher(api, manager)
        authenticator = TokenRefreshAuthenticator(manager, refresher)
        login()
    }

    @Test
    fun `a 401 refreshes and replays with the new token exactly once`() = runTest {
        coEvery { api.refreshToken(any()) } returns
            digital.tonima.protonsync.api.dto.RefreshTokenResponse(
                code = 1000, accessToken = "fresh", refreshToken = "fresh-refresh", uid = "uid", scope = "full",
            )
        val failing = unauthorizedRequest()

        val replayed = authenticator.authenticate(null, failing)

        assertEquals("Bearer fresh", replayed!!.header("Authorization"))
        // The replay is tagged: a second 401 on it must abort, not refresh again.
        assertNull(authenticator.authenticate(null, unauthorizedResponse(replayed)))
    }

    @Test
    fun `auth endpoints are exempt from the refresh cycle`() = runTest {
        assertNull(authenticator.authenticate(null, unauthorizedRequest(path = "/auth/v4/info")))
        assertNull(authenticator.authenticate(null, unauthorizedRequest(path = "/auth/v4")))
        assertNull(authenticator.authenticate(null, unauthorizedRequest(path = "/auth/v4/2fa")))
        assertNull(authenticator.authenticate(null, unauthorizedRequest(path = "/auth/v4/refresh")))
    }

    @Test
    fun `refresh failure aborts the request chain`() = runTest {
        coEvery { api.refreshToken(any()) } throws RuntimeException("boom")

        assertNull(authenticator.authenticate(null, unauthorizedRequest()))
    }

    @Test
    fun `a request whose token was already rotated is replayed without a second refresh`() = runTest {
        // Another thread rotated the session after this request failed with the old token.
        manager.onRefreshed(
            digital.tonima.protonsync.api.dto.RefreshTokenResponse(
                code = 1000, accessToken = "rotated", refreshToken = "rotated-r", uid = "uid", scope = "full",
            ),
        )
        val failing = unauthorizedRequest(authorization = "Bearer old-token")

        val replayed = authenticator.authenticate(null, failing)

        assertEquals("Bearer rotated", replayed!!.header("Authorization"))
        io.mockk.coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    @Test
    fun `non-401 responses are ignored`() = runTest {
        val response =
            Response.Builder()
                .request(Request.Builder().url("https://mail-api.proton.me/calendar/v1").build())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("boom")
                .build()

        assertNull(authenticator.authenticate(null, response))
    }

    private fun login() {
        manager.onAuthenticated(
            digital.tonima.protonsync.api.dto.AuthResponse(
                code = 1000, accessToken = "access", refreshToken = "refresh", uid = "uid", scope = "full",
            ),
        )
    }

    /** Builds a 401 carrying the session's CURRENT token — the normal failing-request shape. */
    private fun unauthorizedRequest(
        path: String = "/calendar/v1",
        authorization: String = "Bearer ${manager.currentAccessToken()}",
    ): Response {
        val request =
            Request.Builder()
                .url("https://mail-api.proton.me$path")
                .header("Authorization", authorization)
                .build()
        return unauthorizedResponse(request)
    }

    private fun unauthorizedResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

    private class FakeCrypto : digital.tonima.protonsync.session.SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(7) + plaintext
        override fun decrypt(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)
    }
}
