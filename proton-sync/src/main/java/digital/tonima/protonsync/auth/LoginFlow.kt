package digital.tonima.protonsync.auth

import android.content.Context
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AuthCodes
import digital.tonima.protonsync.api.dto.AuthInfoRequest
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.HumanVerificationDetails
import digital.tonima.protonsync.api.dto.SrpAuthRequest
import digital.tonima.protonsync.api.dto.TwoFactorInfo
import digital.tonima.protonsync.api.dto.TwoFactorRequest
import digital.tonima.protonsync.crypto.KeyPasswordDeriver
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import digital.tonima.protonsync.sync.ProtonSyncScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat
import retrofit2.HttpException
import java.net.URI

/**
 * The in-app login state machine (auth-flow.md §2, architecture §4): SRP-6a credentials →
 * optional TOTP → optional human verification → session adoption → key unlock → account
 * registration → initial sync. Drive it from the settings ViewModel; every step is
 * suspendable and unit-testable against a fake API client.
 *
 * Security posture implemented here (security-audit §2):
 * - Passwords enter as CharArray and every derived buffer is zeroized when the transaction
 *   ends; nothing password-derived is ever logged or persisted (only the re-encrypted
 *   unlocked key material lands in keys.dat, via [ProtonCryptoKeyring]).
 * - The server proof is verified constant-time before the session is adopted — a mismatch
 *   aborts with an explicit MITM message (auth-flow.md §2.4).
 * - FIDO2-only accounts abort with the reference's explicit message; TOTP only in v1
 *   (operator decision `twofa: totp-only`).
 * - Human verification: the WebUrl host must be on the allowlist {proton.me, protonmail.com,
 *   hcaptcha.com} before it is ever returned to the UI (auth-flow.md §2.7); the token is
 *   replayed by the header interceptor and consumed on the next POST /auth/v4.
 * - Two-password mode (PasswordMode == 2): the mailbox password is prompted and used for
 *   key unlock only, never persisted (auth-flow.md §2.5).
 *
 * Known v1 gap, documented: the reference's clock-skew warning (auth-flow.md §2.2) needs
 * the raw `Date` response header, which the Retrofit DTO path does not expose — the warning
 * is advisory only and skipped; the check can ride on a headers-observing call adapter if
 * it ever matters.
 */
class LoginFlow(
    private val context: Context,
    private val api: ProtonApiClient,
    private val srpClient: SrpClient,
    private val sessionManager: SessionManager,
    private val keyring: ProtonCryptoKeyring,
    private val statusProvider: ProtonSyncStatusProvider,
) {

    sealed interface LoginOutcome {
        /** Login completed: session adopted, keys unlocked, account registered, sync queued. */
        data class Success(val email: String) : LoginOutcome

        /** TOTP challenge — the UI shows the six-digit field (auth-flow.md §2.5). */
        data class RequiresTwoFactor(val info: TwoFactorInfo) : LoginOutcome

        /** Two-password mode: prompt for the mailbox password (auth-flow.md §2.5). */
        data object RequiresMailboxPassword : LoginOutcome

        /** Human verification required: open [url] in a Custom Tab (auth-flow.md §2.7). */
        data class RequiresHumanVerification(val url: String) : LoginOutcome

        /** FIDO2-only account — v1 supports TOTP only (operator decision). */
        data object Fido2Only : LoginOutcome

        /** The TOTP code was rejected (Code 8002) — retryable. */
        data object InvalidTwoFactorCode : LoginOutcome

        /** Any other failure. Messages are safe to surface (no secrets). */
        data class Error(val message: String) : LoginOutcome
    }

    /** In-flight SRP transaction kept between the login / 2FA / HV steps. */
    private data class PendingLogin(
        val username: String,
        val password: CharArray,
        val mailboxPassword: CharArray?,
        val proofs: SrpProofs,
        val srpSession: String,
        val authKeySalt: String?,
        val twoFactorInfo: TwoFactorInfo?,
        val passwordMode: Int,
    ) {
        fun wipe() {
            password.fill('\u0000')
            mailboxPassword?.fill('\u0000')
        }
    }

    private var pending: PendingLogin? = null

    /** Cancels any in-flight login transaction and zeroizes its secrets. */
    fun cancel() {
        pending?.wipe()
        pending = null
    }

    suspend fun submitCredentials(
        email: String,
        password: CharArray,
    ): LoginOutcome {
        cancel()
        val username = canonicalizeUsername(email)
        val passwordBytes = CharArrayCodec.toUtf8Bytes(password)

        val info =
            try {
                api.getAuthInfo(AuthInfoRequest(username = username))
            } catch (e: Exception) {
                return failOutcome("could not reach Proton: ${safeMessage(e)}")
            }
        if (info.code != AuthCodes.SUCCESS) {
            return failOutcome("auth/v4/info rejected (code ${info.code})")
        }

        val proofs =
            try {
                srpClient.generateProofs(
                    SrpChallenge(
                        username = username,
                        modulus = info.modulus,
                        salt = info.salt,
                        serverEphemeral = info.serverEphemeral,
                        srpSession = info.srpSession,
                        version = info.version,
                    ),
                    password,
                )
            } catch (e: SrpException) {
                return failOutcome(safeMessage(e))
            }
        passwordBytes.fill(0)

        pending =
            PendingLogin(
                username = username,
                password = password,
                mailboxPassword = null,
                proofs = proofs,
                srpSession = info.srpSession,
                authKeySalt = null,
                twoFactorInfo = null,
                passwordMode = 0,
            )
        return authenticateStep()
    }

    suspend fun submitTwoFactor(code: String): LoginOutcome {
        val pending = this.pending ?: return failOutcome("no login in progress")
        return when (val result = submitTwoFactorResponse(code)) {
            is TwoFactorResponse.Success -> adoptAndComplete(pending, result.response)
            TwoFactorResponse.InvalidCode -> LoginOutcome.InvalidTwoFactorCode
            is TwoFactorResponse.HumanVerification -> hvOutcome(pending, result.details)
            TwoFactorResponse.Failure -> failOutcome("two-factor authentication failed")
        }
    }

    suspend fun submitMailboxPassword(password: CharArray): LoginOutcome {
        val pending = this.pending ?: return failOutcome("no login in progress")
        if (pending.passwordMode != 2) {
            password.fill('\u0000')
            return failOutcome("this account does not use two-password mode")
        }
        this.pending = pending.copy(mailboxPassword = password)
        return completeLogin(pending.copy(mailboxPassword = password), pending.authKeySalt, password)
    }

    /**
     * After the user solved the human verification in a Custom Tab: adopt the returned token
     * (already handed to [SessionManager] by the caller) and replay the auth call.
     */
    suspend fun resumeWithHvToken(): LoginOutcome {
        val pending = this.pending ?: return failOutcome("no login in progress")
        return authenticateStep()
    }

    // region steps

    private suspend fun authenticateStep(): LoginOutcome {
        val pending = this.pending ?: return failOutcome("no login in progress")
        val response =
            try {
                api.authenticate(
                    SrpAuthRequest(
                        username = pending.username,
                        clientEphemeral = pending.proofs.clientEphemeralBase64,
                        clientProof = pending.proofs.clientProofBase64,
                        srpSession = pending.srpSession,
                    ),
                )
            } catch (e: HttpException) {
                return parseAuthErrorBody(e)?.let { details ->
                    hvOutcome(pending, details)
                } ?: failOutcome("authentication failed (HTTP ${e.code()})")
            } catch (e: Exception) {
                return failOutcome("authentication failed: ${safeMessage(e)}")
            }
        return handleAuthResponse(pending, response)
    }

    private suspend fun handleAuthResponse(
        pending: PendingLogin,
        response: AuthResponse,
    ): LoginOutcome {
        val twoFactor = response.twoFa
        if (response.code != AuthCodes.SUCCESS || (twoFactor != null && twoFactor.enabled != 0)) {
            val info = twoFactor ?: return failOutcome("authentication rejected (code ${response.code})")
            if (info.fido2Only) return LoginOutcome.Fido2Only
            this.pending =
                pending.copy(
                    twoFactorInfo = info,
                    authKeySalt = response.keySalt,
                    passwordMode = response.passwordMode,
                )
            return LoginOutcome.RequiresTwoFactor(info)
        }
        return adoptAndComplete(pending, response)
    }

    private suspend fun adoptAndComplete(
        pending: PendingLogin,
        response: AuthResponse,
    ): LoginOutcome {
        // Constant-time server proof verification BEFORE the session is adopted
        // (auth-flow.md §2.4, security-audit §2.1.4).
        val serverProof = response.serverProof
        if (serverProof == null || !srpClient.verifyServerProof(serverProof, pending.proofs.expectedServerProof)) {
            this.pending?.wipe()
            this.pending = null
            return failOutcome("server proof mismatch — possible man-in-the-middle attack")
        }
        if (!sessionManager.onAuthenticated(response)) {
            return failOutcome("the granted session does not cover calendar access")
        }

        val passwordMode = if (response.passwordMode == 2) 2 else pending.passwordMode
        if (passwordMode == 2 && pending.mailboxPassword == null) {
            this.pending =
                pending.copy(
                    authKeySalt = response.keySalt,
                    passwordMode = 2,
                    twoFactorInfo = null,
                )
            return LoginOutcome.RequiresMailboxPassword
        }

        val unlockPassword =
            if (passwordMode == 2) pending.mailboxPassword ?: pending.password else pending.password
        val completed = completeLogin(pending, response.keySalt, unlockPassword)
        pending.wipe()
        this.pending = null
        return completed
    }

    /** Key unlock → account → initial sync (auth-flow.md §2.6, architecture §4 step 7-8). */
    private suspend fun completeLogin(
        pending: PendingLogin,
        authKeySalt: String?,
        unlockPassword: CharArray,
    ): LoginOutcome {
        val unlockBytes = CharArrayCodec.toUtf8Bytes(unlockPassword)
        return try {
            unlockUserAndAddressKeys(pending.username, unlockBytes, authKeySalt)
            ProtonSyncAccount.ensureAccount(context, pending.username)
            ProtonSyncScheduler.enqueueImmediateSync(context)
            statusProvider.onSessionChanged(sessionManager.currentState(), pending.username)
            LoginOutcome.Success(pending.username)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failOutcome("key unlock failed — is the password correct? (${safeMessage(e)})")
        } finally {
            unlockBytes.fill(0)
        }
    }

    private suspend fun unlockUserAndAddressKeys(
        username: String,
        passwordBytes: ByteArray,
        authKeySalt: String?,
    ) {
        val users = api.getUsers()
        val activeKeys = users.user?.keys.orEmpty().filter { it.active == 1 }
        if (activeKeys.isEmpty()) throw IllegalStateException("the account has no active user keys")
        val primary = activeKeys.firstOrNull { it.primary == 1 } ?: activeKeys.first()

        val salts = try {
            api.getKeySalts().keySalts
        } catch (e: Exception) {
            emptyList()
        }
        val primarySalt = KeyPasswordDeriver.selectPrimarySalt(primary.id, salts, authKeySalt)
            ?: throw IllegalStateException("no key salt available for key ${primary.id}")

        // Primary + additional user keys, each with its own salt (auth-flow.md §2.6.5).
        val primaryKeyPassword = KeyPasswordDeriver.deriveKeyPassword(passwordBytes, primarySalt)
        keyring.addUserKey(primary.id, primary.privateKey, primaryKeyPassword)
        primaryKeyPassword.fill(0)
        for (key in activeKeys.filter { it.id != primary.id }) {
            val salt = KeyPasswordDeriver.selectPrimarySalt(key.id, salts, authKeySalt) ?: primarySalt
            val keyPassword = KeyPasswordDeriver.deriveKeyPassword(passwordBytes, salt)
            try {
                keyring.addUserKey(key.id, key.privateKey, keyPassword)
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "additional user key ${key.id} did not unlock: $e" }
            } finally {
                keyPassword.fill(0)
            }
        }

        // Address keys: passphrase = keyPassword, unless the key carries a Token — then the
        // token decrypts with the user ring and its content is the passphrase
        // (auth-flow.md §2.6.6).
        val addresses = api.getAddresses()
        for (address in addresses.addresses) {
            for (addressKey in address.keys) {
                val passphrase =
                    if (addressKey.token.isNullOrBlank()) {
                        KeyPasswordDeriver.deriveKeyPassword(passwordBytes, primarySalt)
                    } else {
                        val decrypted = keyring.decryptArmoredWithRing(addressKey.token)
                            ?: run {
                                logcat(TAG, LogPriority.WARN) {
                                    "address-key token for ${address.email}/${addressKey.id} undecryptable"
                                }
                                continue
                            }
                        decrypted.toByteArray(Charsets.UTF_8)
                    }
                try {
                    keyring.addAddressKey(address.id, addressKey.privateKey, passphrase)
                } catch (e: Exception) {
                    logcat(TAG, LogPriority.WARN) {
                        "address key ${address.email}/${addressKey.id} did not unlock: $e"
                    }
                } finally {
                    passphrase.fill(0)
                }
            }
        }
        if (!keyring.hasUserKey()) {
            throw IllegalStateException("no user key could be unlocked")
        }
    }

    private suspend fun submitTwoFactorResponse(code: String): TwoFactorResponse {
        require(code.length == 6 && code.all { it.isDigit() }) {
            "two-factor code must be exactly six digits"
        }
        val response =
            try {
                api.submitTwoFactor(TwoFactorRequest(twoFactorCode = code))
            } catch (e: HttpException) {
                return parseAuthErrorBody(e)?.let { TwoFactorResponse.HumanVerification(it) }
                    ?: TwoFactorResponse.Failure
            } catch (e: Exception) {
                return TwoFactorResponse.Failure
            }
        return when {
            response.code == AuthCodes.SUCCESS -> TwoFactorResponse.Success(response)
            response.code == AuthCodes.INVALID_TWO_FACTOR_CODE -> TwoFactorResponse.InvalidCode
            else -> TwoFactorResponse.Failure
        }
    }

    private sealed interface TwoFactorResponse {
        data class Success(val response: AuthResponse) : TwoFactorResponse

        data object InvalidCode : TwoFactorResponse

        data class HumanVerification(val details: HumanVerificationDetails) : TwoFactorResponse

        data object Failure : TwoFactorResponse
    }

    // endregion

    // region error handling

    /**
     * Parses the `Code`/`Details` block of an auth error body (auth-flow.md §2.7). Human
     * verification (9001) is the only recognized shape here; everything else is generic.
     */
    private fun parseAuthErrorBody(e: HttpException): HumanVerificationDetails? {
        val body = e.response()?.errorBody()?.string() ?: return null
        return try {
            val parsed = Json.parseToJsonElement(body).jsonObject
            val code = parsed["Code"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            if (code != AuthCodes.HUMAN_VERIFICATION) return null
            val details = parsed["Details"]?.jsonObject ?: return null
            val token = details["HumanVerificationToken"]?.jsonPrimitive?.content
            val url = details["WebUrl"]?.jsonPrimitive?.content
            HumanVerificationDetails(token = token, webUrl = url, methods = emptyList())
        } catch (ex: Exception) {
            null
        }
    }

    private fun hvOutcome(
        pending: PendingLogin,
        details: HumanVerificationDetails,
    ): LoginOutcome {
        val token = details.token
        val url = details.webUrl
        if (token.isNullOrBlank() || url.isNullOrBlank()) {
            return failOutcome("human verification challenge missing token or URL")
        }
        if (!isAllowedHvHost(url)) {
            // Refuse to open a non-allowlisted host — never hand the user to a stranger
            // (auth-flow.md §2.7).
            return failOutcome("refusing human verification on an untrusted host")
        }
        // In-memory only, replayed by the header interceptor on the next POST /auth/v4 and
        // consumed there (never a static, security-audit LO-3).
        sessionManager.setHvToken(token)
        return LoginOutcome.RequiresHumanVerification(url)
    }

    private fun isAllowedHvHost(url: String): Boolean {
        val host =
            try {
                URI(url).host
            } catch (e: Exception) {
                return false
            } ?: return false
        return host in HV_ALLOWED_HOSTS
    }

    private fun failOutcome(message: String): LoginOutcome {
        logcat(TAG, LogPriority.WARN) { message }
        return LoginOutcome.Error(message)
    }

    private fun safeMessage(e: Exception): String = e.message ?: e::class.simpleName ?: "unknown error"

    /**
     * Username canonicalization (auth-flow.md §2.1): lowercase, append `@proton.me` when the
     * input has no domain.
     */
    private fun canonicalizeUsername(email: String): String {
        val trimmed = email.trim().lowercase()
        return if ('@' in trimmed) trimmed else "$trimmed@proton.me"
    }

    // endregion

    companion object {
        private const val TAG = "ProtonLoginFlow"

        /** The human-verification host allowlist (auth-flow.md §2.7). */
        private val HV_ALLOWED_HOSTS = setOf("proton.me", "protonmail.com", "hcaptcha.com")
    }
}
