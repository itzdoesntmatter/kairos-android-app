package digital.tonima.protonsync.auth

import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AuthCodes
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.TwoFactorRequest

/**
 * TOTP submission for `auth/v4/2fa` (auth-flow.md §2.5, security-audit §2.2.9).
 *
 * The six-digit code is transient: it exists only in this call's parameter, is never logged,
 * and never enters persisted state. `Code == 8002` is surfaced as [TwoFactorResult.InvalidCode]
 * (retryable — the user re-reads their authenticator) while any other failure is a generic
 * auth failure, so the UI does not leak which factor failed (security-audit §2.2.9).
 */
class TwoFactorUseCase(private val api: ProtonApiClient) {

    suspend fun submitCode(code: String): TwoFactorResult {
        require(code.length == 6 && code.all { it.isDigit() }) {
            "two-factor code must be exactly six digits"
        }
        val response = try {
            api.submitTwoFactor(TwoFactorRequest(twoFactorCode = code))
        } catch (e: Exception) {
            return TwoFactorResult.Failure
        }
        return when {
            response.code == AuthCodes.SUCCESS -> TwoFactorResult.Success(response)
            response.code == AuthCodes.INVALID_TWO_FACTOR_CODE -> TwoFactorResult.InvalidCode
            else -> TwoFactorResult.Failure
        }
    }

    sealed interface TwoFactorResult {
        /** The 2FA exchange completed the login — [response] carries the session tokens. */
        data class Success(val response: AuthResponse) : TwoFactorResult

        /** `Code == 8002` — the code was wrong; the user may retry (auth-flow.md §2.5). */
        data object InvalidCode : TwoFactorResult

        /** Any other failure — surfaced generically, without revealing which factor failed. */
        data object Failure : TwoFactorResult
    }
}
