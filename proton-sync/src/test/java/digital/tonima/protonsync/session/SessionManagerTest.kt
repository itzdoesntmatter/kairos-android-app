package digital.tonima.protonsync.session

import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.RefreshTokenResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SessionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: SessionStore
    private lateinit var manager: SessionManager

    @Before
    fun setUp() {
        store = SessionStore(File(tempFolder.root, "proton-session/session.dat"), SoftwareGcmCrypto())
        manager = SessionManager(store)
    }

    @Test
    fun `initialize restores the persisted session without an access token`() {
        store.save(StoredSession(uid = "uid", refreshToken = "refresh", scope = "calendar"))

        manager.initialize()

        assertEquals("uid", manager.currentUid())
        assertEquals("refresh", manager.currentRefreshToken())
        assertEquals("calendar", manager.currentScope())
        assertNull(manager.currentAccessToken())
        assertEquals(SessionState.ACTIVE, manager.currentState())
    }

    @Test
    fun `onAuthenticated keeps the access token in memory and persists the rest`() {
        val accepted = manager.onAuthenticated(
            AuthResponse(
                code = 1000,
                accessToken = "access-token",
                refreshToken = "refresh-token",
                uid = "uid-1",
                scope = "full",
            ),
        )

        assertTrue(accepted)
        assertEquals("access-token", manager.currentAccessToken())
        assertEquals(SessionState.ACTIVE, manager.currentState())
        assertEquals(StoredSession("uid-1", "refresh-token", "full"), store.load())
        // The access token never reaches the file.
        val onDisk = String(
            File(tempFolder.root, "proton-session/session.dat").readBytes(),
            Charsets.ISO_8859_1,
        )
        assertTrue(!onDisk.contains("access-token"))
    }

    @Test
    fun `onAuthenticated rejects a narrowed scope and persists nothing`() {
        val accepted = manager.onAuthenticated(
            AuthResponse(
                code = 1000,
                accessToken = "access",
                refreshToken = "refresh",
                uid = "uid",
                scope = "mail settings", // no calendar grant
            ),
        )

        assertFalse(accepted)
        assertNull(store.load())
        assertNull(manager.currentAccessToken())
        assertEquals(SessionState.LOGGED_OUT, manager.currentState())
    }

    @Test
    fun `onRefreshed rotates tokens and persists the new refresh token atomically`() {
        manager.onAuthenticated(
            AuthResponse(
                code = 1000,
                accessToken = "old-access",
                refreshToken = "old-refresh",
                uid = "uid",
                scope = "full",
            ),
        )

        val rotated = manager.onRefreshed(
            RefreshTokenResponse(
                code = 1000,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                uid = "uid",
                scope = "full",
            ),
        )

        assertTrue(rotated)
        assertEquals("new-access", manager.currentAccessToken())
        assertEquals("new-refresh", manager.currentRefreshToken())
        assertEquals(StoredSession("uid", "new-refresh", "full"), store.load())
    }

    @Test
    fun `onRefreshed rejects a narrowed scope and keeps the old session`() {
        manager.onAuthenticated(
            AuthResponse(
                code = 1000,
                accessToken = "old-access",
                refreshToken = "old-refresh",
                uid = "uid",
                scope = "full",
            ),
        )

        val rotated = manager.onRefreshed(
            RefreshTokenResponse(
                code = 1000,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                uid = "uid",
                scope = "mail", // narrowed
            ),
        )

        assertFalse(rotated)
        assertEquals("old-access", manager.currentAccessToken())
        assertEquals(StoredSession("uid", "old-refresh", "full"), store.load())
    }

    @Test
    fun `markNeedsReauth flips the state for the UI`() {
        manager.onAuthenticated(
            AuthResponse(code = 1000, accessToken = "a", refreshToken = "r", uid = "u", scope = "full"),
        )

        manager.markNeedsReauth()

        assertEquals(SessionState.NEEDS_REAUTH, manager.currentState())
    }

    @Test
    fun `clear wipes memory and disk`() {
        manager.onAuthenticated(
            AuthResponse(code = 1000, accessToken = "a", refreshToken = "r", uid = "u", scope = "full"),
        )
        manager.setHvToken("hv-token")

        manager.clear()

        assertNull(manager.currentAccessToken())
        assertNull(manager.currentUid())
        assertNull(manager.currentRefreshToken())
        assertNull(manager.consumeHvToken())
        assertEquals(SessionState.LOGGED_OUT, manager.currentState())
        assertNull(store.load())
    }

    @Test
    fun `human verification token is single-use`() {
        manager.setHvToken("hv-1")

        assertEquals("hv-1", manager.consumeHvToken())
        assertNull(manager.consumeHvToken())
    }

    private class SoftwareGcmCrypto : SessionCrypto {
        private val secret = SecretKeySpec(
            KeyGenerator.getInstance("AES").run {
                init(256)
                generateKey()
            }.encoded,
            "AES",
        )
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(12)
            java.security.SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            return cipher.doFinal(data.copyOfRange(12, data.size))
        }
    }
}
