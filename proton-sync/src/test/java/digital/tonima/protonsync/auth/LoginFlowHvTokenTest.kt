package digital.tonima.protonsync.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AuthCodes
import digital.tonima.protonsync.api.dto.AuthInfoResponse
import digital.tonima.protonsync.api.dto.SrpAuthRequest
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

/**
 * The human-verification branch of the login state machine (auth-flow.md §2.7, security-audit
 * §2.2.22). The flow itself is the subject: the challenge body is parsed, the URL is put
 * through [HvUrlPolicy] before anything is handed to the UI, the token is adopted on the
 * single-use session slot (never a static, LO-3), and an abandoned attempt discards it again so
 * it can never ride on a later auth call.
 *
 * SRP is a mocked seam (as in `LoginFlowTest`): the real client draws its ephemeral from a
 * self-seeding SecureRandom, which cannot be made deterministic on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class LoginFlowHvTokenTest {

    private lateinit var api: ProtonApiClient
    private lateinit var srpClient: SrpClient
    private lateinit var sessionManager: SessionManager
    private lateinit var flow: LoginFlow

    private val hvBody =
        """{"Code":9001,"Details":{"HumanVerificationToken":"hv-token-1","WebUrl":"https://hcaptcha.com/challenge"}}"""

    private fun hvBodyWithUrl(url: String) =
        """{"Code":9001,"Details":{"HumanVerificationToken":"hv-token-1","WebUrl":"$url"}}"""

    @Before
    fun setUp() {
        val app: Application = ApplicationProvider.getApplicationContext()
        api = mockk(relaxed = true)
        srpClient = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)

        coEvery { api.getAuthInfo(any()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = "modulus",
                salt = "salt",
                serverEphemeral = "ephemeral",
                srpSession = "srp-session",
                version = 4,
            )

        flow =
            LoginFlow(
                context = app,
                api = api,
                srpClient = srpClient,
                sessionManager = sessionManager,
                keyring = mockk<ProtonCryptoKeyring>(relaxed = true),
                statusProvider = ProtonSyncStatusProvider(),
            )
    }

    /** Drives the flow to the point where the auth call answers with a 9001 HV challenge. */
    private suspend fun submitUntilHv(body: String = hvBody): LoginFlow.LoginOutcome {
        every { srpClient.generateProofs(any(), any()) } returns
            SrpProofs(
                clientEphemeralBase64 = "ephemeral",
                clientProofBase64 = "proof",
                expectedServerProof = byteArrayOf(1, 2, 3),
            )
        coEvery { api.authenticate(any<SrpAuthRequest>()) } throws
            HttpException(
                Response.error<Unit>(
                    AuthCodes.HUMAN_VERIFICATION,
                    body.toResponseBody("application/json".toMediaType()),
                ),
            )
        return flow.submitCredentials("alice@proton.me", "correct horse".toCharArray())
    }

    @Test
    fun `an allowlisted challenge adopts the token and asks for the tab`() = runTest {
        val outcome = submitUntilHv()

        assertEquals(
            LoginFlow.LoginOutcome.RequiresHumanVerification("https://hcaptcha.com/challenge"),
            outcome,
        )
        verify(exactly = 1) { sessionManager.setHvToken("hv-token-1") }
    }

    @Test
    fun `a challenge on an untrusted host is refused before the token is adopted`() = runTest {
        val outcome = submitUntilHv(hvBodyWithUrl("https://evil.example/verify"))

        assertTrue((outcome as LoginFlow.LoginOutcome.Error).message.contains("untrusted host"))
        verify(exactly = 0) { sessionManager.setHvToken(any()) }
    }

    @Test
    fun `a challenge on a look-alike host is refused`() = runTest {
        val outcome = submitUntilHv(hvBodyWithUrl("https://proton.me.evil.example/verify"))

        assertTrue(outcome is LoginFlow.LoginOutcome.Error)
        verify(exactly = 0) { sessionManager.setHvToken(any()) }
    }

    @Test
    fun `an abandoned attempt discards the adopted token`() = runTest {
        submitUntilHv()
        // submitCredentials itself cancels any previous attempt (and therefore consumes a
        // stale token) — start the count at the point where the challenge token is live.
        clearMocks(sessionManager, answers = false)

        flow.cancel()

        // Exactly once: the abandoned attempt gave the token up, so nothing is left for a
        // later POST /auth/v4 to pick up.
        verify(exactly = 1) { sessionManager.consumeHvToken() }
    }

    @Test
    fun `resuming after the tab replays the auth call`() = runTest {
        submitUntilHv()

        flow.resumeWithHvToken()

        // The replay is the same SRP proof submission: the HV token itself is attached by the
        // header interceptor from the single-use session slot (auth-flow.md §2.7).
        coVerify(exactly = 2) { api.authenticate(any<SrpAuthRequest>()) }
    }

    @Test
    fun `resuming without a login in progress fails cleanly`() = runTest {
        val outcome = flow.resumeWithHvToken()

        assertTrue((outcome as LoginFlow.LoginOutcome.Error).message.contains("no login in progress"))
    }
}
