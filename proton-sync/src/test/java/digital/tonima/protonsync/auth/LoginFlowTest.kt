package digital.tonima.protonsync.auth

import android.accounts.AccountManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AddressesResponse
import digital.tonima.protonsync.api.dto.AuthCodes
import digital.tonima.protonsync.api.dto.AuthInfoRequest
import digital.tonima.protonsync.api.dto.AuthInfoResponse
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.KeySaltInfo
import digital.tonima.protonsync.api.dto.KeySaltsResponse
import digital.tonima.protonsync.api.dto.ProtonUser
import digital.tonima.protonsync.api.dto.TwoFactorInfo
import digital.tonima.protonsync.api.dto.UserKey
import digital.tonima.protonsync.api.dto.UserResponse
import digital.tonima.protonsync.crypto.KeysStore
import digital.tonima.protonsync.crypto.ProtonCrypto
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.session.SessionCrypto
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionState
import digital.tonima.protonsync.session.SessionStore
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import digital.tonima.protonsync.sync.ProtonSyncScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LoginFlow state-machine tests (auth-flow.md §2): SRP credentials → session adoption with
 * constant-time server-proof verification → key unlock → account registration → initial
 * sync; the TOTP branch, FIDO2-only abort, invalid-code retry and the HV host allowlist.
 *
 * SRP is a mocked seam here: [SrpClient.generateProofs] and [SrpClient.verifyServerProof]
 * are stubbed (see setUp), because the real client draws its ephemeral from SecureRandom,
 * which cannot be made deterministic on the JVM (SHA1PRNG self-seeds on construction under
 * Robolectric). The SRP math itself is covered by SrpClientTest's published vectors.
 */
@RunWith(RobolectricTestRunner::class)
class LoginFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var api: ProtonApiClient
    private lateinit var sessionManager: SessionManager
    private lateinit var keyring: ProtonCryptoKeyring
    private lateinit var keysFile: File
    private lateinit var statusProvider: ProtonSyncStatusProvider
    private lateinit var flow: LoginFlow
    private lateinit var srpClient: SrpClient

    private val challenge =
        SrpChallenge(
            username = "alice@proton.me",
            modulus = SrpClient.PINNED_MODULUS_BASE64,
            salt = Base64.getEncoder().encodeToString(ByteArray(10)),
            serverEphemeral = Base64.getEncoder().encodeToString(ByteArray(256).also { it[0] = 2 }),
            srpSession = "srp-session-1",
            version = 4,
        )

    private fun successAuthResponse(): AuthResponse =
        AuthResponse(
            code = AuthCodes.SUCCESS,
            accessToken = "access-token",
            refreshToken = "refresh-token",
            tokenType = "Bearer",
            uid = "uid-1",
            scope = "full",
            serverProof = "c2VydmVyLXByb29m", // SRP is a mocked seam in this suite — any value passes.
            passwordMode = 1,
            keySalt = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray()),
        )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        api = mockk(relaxed = true)
        keysFile = File(tempFolder.root, "proton-session/keys.dat")
        sessionManager = SessionManager(SessionStore(File(tempFolder.root, "session.dat"), SoftwareGcmCrypto()))
        keyring = ProtonCryptoKeyring(FakePgpCrypto, KeysStore(keysFile, SoftwareGcmCrypto()))
        statusProvider = ProtonSyncStatusProvider()
        srpClient = mockk(relaxed = true)
        // SRP is a seam here, not the subject (its math is covered by SrpClientTest's
        // published vectors): the flow must still drive generateProofs and accept the
        // server proof, so both are stubbed with deterministic fakes. A real-SRP version
        // of this test is not possible on the JVM: SHA1PRNG self-seeds on construction
        // under Robolectric, so two same-seed clients never agree on the ephemeral.
        every { srpClient.generateProofs(any(), any()) } returns
            SrpProofs(
                clientEphemeralBase64 = "ephemeral",
                clientProofBase64 = "proof",
                expectedServerProof = byteArrayOf(1, 2, 3),
            )
        every { srpClient.verifyServerProof(any(), any()) } returns true
        flow =
            LoginFlow(
                context = app,
                api = api,
                srpClient = srpClient,
                sessionManager = sessionManager,
                keyring = keyring,
                statusProvider = statusProvider,
            )
    }

    @Test
    fun `happy path - login unlocks keys registers the account and queues the first sync`() = runTest {
        coEvery { api.getAuthInfo(any<AuthInfoRequest>()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = challenge.modulus,
                salt = challenge.salt,
                serverEphemeral = challenge.serverEphemeral,
                srpSession = challenge.srpSession,
                version = 4,
            )
        coEvery { api.authenticate(any()) } returns successAuthResponse()
        coEvery { api.getUsers() } returns
            UserResponse(
                code = AuthCodes.SUCCESS,
                user =
                    ProtonUser(
                        id = "user-1",
                        keys = listOf(UserKey(id = "uk-1", privateKey = "armored-user-key", active = 1, primary = 1)),
                    ),
            )
        coEvery { api.getKeySalts() } returns
            KeySaltsResponse(
                code = AuthCodes.SUCCESS,
                keySalts = listOf(KeySaltInfo(id = "uk-1", keySalt = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray()))),
            )
        coEvery { api.getAddresses() } returns AddressesResponse(code = AuthCodes.SUCCESS, addresses = emptyList())

        val outcome = flow.submitCredentials("alice", "correct horse".toCharArray())

        assertTrue(outcome is LoginFlow.LoginOutcome.Success)
        assertEquals("alice@proton.me", (outcome as LoginFlow.LoginOutcome.Success).email)
        assertTrue(AccountManager.get(app).getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE).isNotEmpty())
        assertTrue(keysFile.exists())
        assertTrue(keyring.hasUserKey())
        assertEquals(SessionState.ACTIVE, statusProvider.state.value.sessionState)
        val work =
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(ProtonSyncScheduler.WORK_ONCE)
                .get()
        assertTrue(work.isNotEmpty())
    }

    @Test
    fun `totp challenge pauses until the code arrives`() = runTest {
        coEvery { api.getAuthInfo(any<AuthInfoRequest>()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = challenge.modulus,
                salt = challenge.salt,
                serverEphemeral = challenge.serverEphemeral,
                srpSession = challenge.srpSession,
                version = 4,
            )
        coEvery { api.authenticate(any()) } returns
            AuthResponse(
                code = AuthCodes.SUCCESS,
                twoFa = TwoFactorInfo(enabled = TwoFactorInfo.BIT_TOTP, totp = 1),
                keySalt = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray()),
            )

        val outcome = flow.submitCredentials("alice@proton.me", "pw".toCharArray())

        assertTrue(outcome is LoginFlow.LoginOutcome.RequiresTwoFactor)
        assertTrue(AccountManager.get(app).getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE).isEmpty())
    }

    @Test
    fun `fido2-only accounts abort with the explicit message`() = runTest {
        coEvery { api.getAuthInfo(any<AuthInfoRequest>()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = challenge.modulus,
                salt = challenge.salt,
                serverEphemeral = challenge.serverEphemeral,
                srpSession = challenge.srpSession,
                version = 4,
            )
        coEvery { api.authenticate(any()) } returns
            AuthResponse(
                code = AuthCodes.SUCCESS,
                twoFa = TwoFactorInfo(enabled = TwoFactorInfo.BIT_FIDO2, totp = 0),
            )

        val outcome = flow.submitCredentials("alice", "pw".toCharArray())

        assertEquals(LoginFlow.LoginOutcome.Fido2Only, outcome)
    }

    @Test
    fun `a forged server proof aborts login as a possible MITM`() = runTest {
        coEvery { api.getAuthInfo(any<AuthInfoRequest>()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = challenge.modulus,
                salt = challenge.salt,
                serverEphemeral = challenge.serverEphemeral,
                srpSession = challenge.srpSession,
                version = 4,
            )
        coEvery { api.authenticate(any()) } returns
            successAuthResponse()
                .copy(serverProof = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        // The seam's verify is stubbed true in setUp; this test forges the proof and
        // stubs verification to fail — the flow must abort with the MITM message.
        every { srpClient.verifyServerProof(any(), any()) } returns false

        val outcome = flow.submitCredentials("alice", "pw".toCharArray())

        assertTrue(outcome is LoginFlow.LoginOutcome.Error)
        assertTrue((outcome as LoginFlow.LoginOutcome.Error).message.contains("man-in-the-middle"))
        assertEquals(SessionState.LOGGED_OUT, sessionManager.currentState())
    }

    @Test
    fun `an invalid totp code is surfaced as retryable`() = runTest {
        coEvery { api.getAuthInfo(any<AuthInfoRequest>()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = challenge.modulus,
                salt = challenge.salt,
                serverEphemeral = challenge.serverEphemeral,
                srpSession = challenge.srpSession,
                version = 4,
            )
        coEvery { api.authenticate(any()) } returns
            AuthResponse(
                code = AuthCodes.SUCCESS,
                twoFa = TwoFactorInfo(enabled = TwoFactorInfo.BIT_TOTP, totp = 1),
            )
        coEvery { api.submitTwoFactor(any()) } returns
            AuthResponse(code = AuthCodes.INVALID_TWO_FACTOR_CODE)

        flow.submitCredentials("alice", "pw".toCharArray())
        val outcome = flow.submitTwoFactor("123456")

        assertEquals(LoginFlow.LoginOutcome.InvalidTwoFactorCode, outcome)
    }

    @Test
    fun `human verification on an untrusted host is refused`() = runTest {
        coEvery { api.getAuthInfo(any<AuthInfoRequest>()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = challenge.modulus,
                salt = challenge.salt,
                serverEphemeral = challenge.serverEphemeral,
                srpSession = challenge.srpSession,
                version = 4,
            )
        val errorBody =
            """{"Code":9001,"Error":"","Details":{"HumanVerificationToken":"hv-token","WebUrl":"https://evil.example/captcha","HumanVerificationMethods":["captcha"]}}"""
        coEvery { api.authenticate(any()) } throws
            HttpException(
                Response.error<AuthResponse>(
                    422,
                    errorBody.toResponseBody("application/json".toMediaType()),
                ),
            )

        val outcome = flow.submitCredentials("alice", "pw".toCharArray())

        assertTrue(outcome is LoginFlow.LoginOutcome.Error)
        assertTrue((outcome as LoginFlow.LoginOutcome.Error).message.contains("untrusted"))
    }

    @Test
    fun `human verification on an allowlisted host opens the custom tab flow`() = runTest {
        coEvery { api.getAuthInfo(any<AuthInfoRequest>()) } returns
            AuthInfoResponse(
                code = AuthCodes.SUCCESS,
                modulus = challenge.modulus,
                salt = challenge.salt,
                serverEphemeral = challenge.serverEphemeral,
                srpSession = challenge.srpSession,
                version = 4,
            )
        val errorBody =
            """{"Code":9001,"Error":"","Details":{"HumanVerificationToken":"hv-token","WebUrl":"https://proton.me/hv/xyz","HumanVerificationMethods":["captcha"]}}"""
        coEvery { api.authenticate(any()) } throws
            HttpException(
                Response.error<AuthResponse>(
                    422,
                    errorBody.toResponseBody("application/json".toMediaType()),
                ),
            )

        val outcome = flow.submitCredentials("alice", "pw".toCharArray())

        assertTrue(outcome is LoginFlow.LoginOutcome.RequiresHumanVerification)
        assertEquals("https://proton.me/hv/xyz", (outcome as LoginFlow.LoginOutcome.RequiresHumanVerification).url)
    }

    private object FakePgpCrypto : ProtonCrypto {
        override fun canParseKey(armoredKey: String): Boolean = true

        override fun unlock(
            armoredKey: String,
            passphrase: ByteArray,
        ): ByteArray = "unlocked:$armoredKey".toByteArray()

        override fun decryptArmoredText(
            armoredMessage: String,
            privateKey: ByteArray,
        ): String = "token-decrypted"

        override fun decryptArmoredData(
            armoredMessage: String,
            privateKey: ByteArray,
        ): ByteArray = decryptArmoredText(armoredMessage, privateKey).toByteArray()

        override fun decryptBinary(
            keyPacket: ByteArray,
            dataPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray = ByteArray(0)

        override fun decryptSessionKey(
            keyPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray = ByteArray(0)

        override fun decryptDataWithSessionKey(
            dataPacket: ByteArray,
            sessionKey: ByteArray,
        ): ByteArray = ByteArray(0)

        override fun verifyDetached(
            data: ByteArray,
            armoredSignature: String,
            senderArmoredKeys: List<String>,
        ): Boolean = false

        override fun fingerprint(armoredKey: String): String = "fp"
    }

    private class SoftwareGcmCrypto : SessionCrypto {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(data: ByteArray): ByteArray {
            val iv = data.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(data.copyOfRange(12, data.size))
        }
    }
}
