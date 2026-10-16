package digital.tonima.protonsync.auth

import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.TwoFactorRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Gap-fill for [TwoFactorUseCaseTest] (specs/test-plan.md AUTH-14): the wrong-code paths a real
 * Proton server produces — `Code == 8002` arriving in an HTTP **error** body (the shape the
 * reference parses in addition to 200 bodies, auth-flow.md §2.5) — plus the input-guard edges of
 * security-audit.md §2.2.9.
 */
class TwoFactorUseCaseGapTest {

    private val api = mockk<ProtonApiClient>(relaxed = true)
    private val useCase = TwoFactorUseCase(api)

    @Test
    fun `an 8002 rejection delivered as an HTTP error body is not retryable - FINDING F-2`() = runTest {
        // FINDING F-2 (LOD-12): auth-flow.md §2.5 documents 8002 as parsed from HTTP-error bodies
        // as well as 200 bodies (the reference does exactly that). A wrong TOTP from Proton is a
        // 4xx + {"Code":8002}; the landed client decodes typed bodies only, so the rejection
        // surfaces as retrofit2.HttpException and [TwoFactorUseCase] swallows it into the generic
        // Failure — the UI cannot offer the documented "wrong code, try again" retry. The retryable
        // invalid-code path is therefore unreachable in production. Owner: code-agent (LOD-12 F-2).
        coEvery { api.submitTwoFactor(any()) } throws HttpException(
            Response.error<AuthResponse>(
                400,
                """{"Code":8002}""".toResponseBody("application/json".toMediaType()),
            ),
        )

        assertEquals(TwoFactorUseCase.TwoFactorResult.Failure, useCase.submitCode("000000"))
    }

    @Test
    fun `an invalid code is reported without an internal retry`() = runTest {
        coEvery { api.submitTwoFactor(any()) } returns AuthResponse(code = 8002)

        assertEquals(TwoFactorUseCase.TwoFactorResult.InvalidCode, useCase.submitCode("654321"))

        coVerify(exactly = 1) { api.submitTwoFactor(TwoFactorRequest(twoFactorCode = "654321")) }
    }

    @Test
    fun `a successful 2fa response never carries the submitted code`() = runTest {
        // security-audit §2.2.12: the code is transient. The result object handed to the caller
        // must not retain it on either path.
        coEvery { api.submitTwoFactor(any()) } returns
            AuthResponse(code = 1000, accessToken = "a", refreshToken = "r", uid = "u", scope = "full")

        val success = useCase.submitCode("424242")

        val carried = (success as TwoFactorUseCase.TwoFactorResult.Success).response
        assertTrue(!carried.toString().contains("424242"))
        assertTrue(!success.toString().contains("424242"))
    }

    @Test
    fun `non-ASCII digits pass the six-character guard - FINDING F-3`() = runTest {
        // FINDING F-3 (LOD-12): auth-flow.md §2.5 and security-audit §2.2.9 require a SIX-DIGIT
        // (i.e. [0-9]) code; `Char.isDigit()` accepts every Unicode digit, so Arabic-Indic and
        // fullwidth forms are forwarded to the server verbatim (here: proven by the recorded
        // request). Not a bypass — the server rejects them — but the guard does not implement the
        // documented ASCII rule. Owner: code-agent (LOD-12 F-3).
        val arabicIndic = "\u0661\u0662\u0663\u0664\u0665\u0666"
        coEvery { api.submitTwoFactor(any()) } returns
            AuthResponse(code = 1000, accessToken = "a", refreshToken = "r", uid = "u", scope = "full")

        val result = useCase.submitCode(arabicIndic)

        assertTrue(result is TwoFactorUseCase.TwoFactorResult.Success)
        coVerify { api.submitTwoFactor(TwoFactorRequest(twoFactorCode = arabicIndic)) }
    }

    @Test
    fun `blank and padded codes are refused before any network call`() = runTest {
        for (bad in listOf("", " 12345", "123 456", "123456\n")) {
            val refused = try {
                useCase.submitCode(bad)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue("expected rejection for '$bad'", refused)
        }
        coVerify(exactly = 0) { api.submitTwoFactor(any()) }
    }
}
