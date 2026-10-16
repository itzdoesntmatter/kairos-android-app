package digital.tonima.protonsync.auth

import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.TwoFactorRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFactorUseCaseTest {

    private val api = mockk<ProtonApiClient>(relaxed = true)
    private val useCase = TwoFactorUseCase(api)

    @Test
    fun `a successful code returns the auth response`() = runTest {
        val success = AuthResponse(code = 1000, accessToken = "a", refreshToken = "r", uid = "u", scope = "full")
        coEvery { api.submitTwoFactor(any()) } returns success

        val result = useCase.submitCode("123456")

        assertEquals(TwoFactorUseCase.TwoFactorResult.Success(success), result)
        coVerify { api.submitTwoFactor(TwoFactorRequest(twoFactorCode = "123456")) }
    }

    @Test
    fun `code 8002 is an invalid code — retryable`() = runTest {
        coEvery { api.submitTwoFactor(any()) } returns AuthResponse(code = 8002)

        assertEquals(TwoFactorUseCase.TwoFactorResult.InvalidCode, useCase.submitCode("000000"))
    }

    @Test
    fun `any other failure is generic`() = runTest {
        coEvery { api.submitTwoFactor(any()) } returns AuthResponse(code = 1001)
        assertEquals(TwoFactorUseCase.TwoFactorResult.Failure, useCase.submitCode("000000"))

        coEvery { api.submitTwoFactor(any()) } throws RuntimeException("down")
        assertEquals(TwoFactorUseCase.TwoFactorResult.Failure, useCase.submitCode("000000"))
    }

    @Test
    fun `malformed codes are rejected before any network call`() = runTest {
        for (bad in listOf("12345", "abcdef", "1234567")) {
            val thrown =
                try {
                    useCase.submitCode(bad)
                    null
                } catch (e: IllegalArgumentException) {
                    e
                }
            assertTrue("expected rejection for '$bad'", thrown != null)
        }
        coVerify(exactly = 0) { api.submitTwoFactor(any()) }
    }
}
