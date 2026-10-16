package digital.tonima.protonsync.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the auth family (`auth-flow.md` §1.2 / §2). JSON field names are Proton's
 * PascalCase wire names, annotated explicitly — the serialization layer uses no global naming
 * strategy, so a rename here is a deliberate wire-format change.
 *
 * `Code == 1000` is Proton's success convention on every response (auth-flow.md §1).
 */
object AuthCodes {
    const val SUCCESS = 1000

    /** Human verification required on an auth call; body carries `Details` (auth-flow.md §2.7). */
    const val HUMAN_VERIFICATION = 9001

    /** Invalid two-factor code on `auth/v4/2fa` (auth-flow.md §2.5). */
    const val INVALID_TWO_FACTOR_CODE = 8002
}

/** POST /auth/v4/info — the request is a flat JSON object with these two keys. */
@Serializable
data class AuthInfoRequest(
    @SerialName("Username") val username: String,
    @SerialName("Intent") val intent: String = "Proton",
)

/**
 * Response of POST /auth/v4/info (auth-flow.md §1.2). [modulus] is clearsigned base64; the
 * SRP client extracts and pins it before use. All fields are consumed by the login flow.
 */
@Serializable
data class AuthInfoResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Modulus") val modulus: String = "",
    @SerialName("Salt") val salt: String = "",
    @SerialName("ServerEphemeral") val serverEphemeral: String = "",
    @SerialName("SRPSession") val srpSession: String = "",
    @SerialName("Version") val version: Int = 0,
)

/** POST /auth/v4 — SRP proof submission (auth-flow.md §1.2). */
@Serializable
data class SrpAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("ClientEphemeral") val clientEphemeral: String,
    @SerialName("ClientProof") val clientProof: String,
    @SerialName("SRPSession") val srpSession: String,
)

/**
 * Response of POST /auth/v4 (auth-flow.md §1.2). On success all token fields are present and
 * [code] is [AuthCodes.SUCCESS]; when TOTP is required the token fields are absent and [twoFa]
 * describes the challenge. [passwordMode] == 2 means two-password mode (mailbox password for
 * key unlock). `KeySalt` is the auth fallback for the key-salt chain (auth-flow.md §2.6).
 */
@Serializable
data class AuthResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("RefreshToken") val refreshToken: String? = null,
    @SerialName("TokenType") val tokenType: String? = null,
    @SerialName("Uid") val uid: String? = null,
    @SerialName("Scope") val scope: String? = null,
    @SerialName("ServerProof") val serverProof: String? = null,
    @SerialName("PasswordMode") val passwordMode: Int = 0,
    @SerialName("KeySalt") val keySalt: String? = null,
    @SerialName("2FA") val twoFa: TwoFactorInfo? = null,
)

/** Two-factor challenge info (auth-flow.md §2.5). [enabled] is a bitmask: 1 = TOTP, 2 = FIDO2. */
@Serializable
data class TwoFactorInfo(
    @SerialName("Enabled") val enabled: Int = 0,
    @SerialName("TOTP") val totp: Int = 0,
    @SerialName("FIDO2") val fido2: Fido2Info? = null,
) {
    val totpEnabled: Boolean get() = totp == 1
    val fido2Only: Boolean get() = enabled != 0 && totp != 1

    companion object {
        const val BIT_TOTP = 1
        const val BIT_FIDO2 = 2
    }
}

@Serializable
data class Fido2Info(
    @SerialName("RegisteredKeys") val registeredKeys: List<Fido2RegisteredKey> = emptyList(),
)

/**
 * Shape not fully captured by the RE pass (auth-flow.md VERIFY-NEEDED #4); the list alone is
 * documented. Consumed only to detect FIDO2-only accounts, which v1 rejects outright.
 */
@Serializable
data class Fido2RegisteredKey(
    @SerialName("AttestationFormat") val attestationFormat: String? = null,
    @SerialName("CredentialID") val credentialId: String? = null,
    @SerialName("Name") val name: String? = null,
)

/** POST /auth/v4/2fa — exactly a six-digit TOTP code (auth-flow.md §2.5). */
@Serializable
data class TwoFactorRequest(
    @SerialName("TwoFactorCode") val twoFactorCode: String,
)

/**
 * POST /auth/v4/refresh (auth-flow.md §3). `ResponseType`/`GrantType`/`RedirectURI` are the
 * documented wire defaults; `RedirectURI` keeps the legacy `protonmail.ch` value the protocol
 * expects.
 */
@Serializable
data class RefreshTokenRequest(
    @SerialName("RefreshToken") val refreshToken: String,
    @SerialName("UID") val uid: String,
    @SerialName("ResponseType") val responseType: String = "token",
    @SerialName("GrantType") val grantType: String = "refresh_token",
    @SerialName("RedirectURI") val redirectURI: String = "https://protonmail.ch",
)

/** Response of POST /auth/v4/refresh (auth-flow.md §3). */
@Serializable
data class RefreshTokenResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("RefreshToken") val refreshToken: String? = null,
    @SerialName("TokenType") val tokenType: String? = null,
    @SerialName("Uid") val uid: String? = null,
    @SerialName("Scope") val scope: String? = null,
)

/**
 * Details block of a 9001 human-verification error body (auth-flow.md §2.7). The token is
 * replayed on the next `auth/v4` call via the `x-pm-human-verification-token` headers and
 * cleared after use — never persisted.
 */
@Serializable
data class HumanVerificationDetails(
    @SerialName("HumanVerificationToken") val token: String? = null,
    @SerialName("WebUrl") val webUrl: String? = null,
    @SerialName("HumanVerificationMethods") val methods: List<String> = emptyList(),
)
