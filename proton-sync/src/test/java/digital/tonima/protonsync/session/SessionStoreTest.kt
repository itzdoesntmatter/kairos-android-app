package digital.tonima.protonsync.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SessionStore round-trip, tamper detection and atomic-write behaviour, against a software
 * AES-GCM crypto (the same wire contract as [AndroidKeyStoreSessionCrypto]; the real
 * Keystore round-trip is a device-gated W3 test per architecture §10 — Robolectric's
 * Keystore is a mock).
 */
class SessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sessionFile: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        sessionFile = File(tempFolder.root, "proton-session/session.dat")
        store = SessionStore(sessionFile, SoftwareGcmCrypto())
    }

    @Test
    fun `round-trips a session through the encrypted file`() {
        val session = StoredSession(uid = "uid-1", refreshToken = "refresh-token", scope = "full")

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun `missing file loads as null`() {
        assertNull(store.load())
    }

    @Test
    fun `a flipped byte in the ciphertext is detected and wipes the file`() {
        store.save(StoredSession(uid = "u", refreshToken = "r", scope = "calendar"))
        val bytes = sessionFile.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        sessionFile.writeBytes(bytes)

        assertNull(store.load())
        assertTrue(!sessionFile.exists())
    }

    @Test
    fun `a wrong magic header is rejected and wiped`() {
        store.save(StoredSession(uid = "u", refreshToken = "r", scope = "full"))
        val bytes = sessionFile.readBytes()
        bytes[0] = 'X'.code.toByte()
        sessionFile.writeBytes(bytes)

        assertNull(store.load())
        assertTrue(!sessionFile.exists())
    }

    @Test
    fun `save is atomic — no temp file remains and the target is complete`() {
        store.save(StoredSession(uid = "u", refreshToken = "r", scope = "full"))

        assertNull(File(sessionFile.parentFile, "session.dat.tmp").takeIf { it.exists() })
        assertEquals(StoredSession("u", "r", "full"), store.load())
    }

    @Test
    fun `save creates the session directory on demand`() {
        assertTrue(!sessionFile.parentFile.exists())

        store.save(StoredSession(uid = "u", refreshToken = "r", scope = "full"))

        assertTrue(sessionFile.exists())
    }

    @Test
    fun `wipe removes the session file and any leftover temp file`() {
        store.save(StoredSession(uid = "u", refreshToken = "r", scope = "full"))
        File(sessionFile.parentFile, "session.dat.tmp").writeBytes(byteArrayOf(1))

        store.wipe()

        assertTrue(!sessionFile.exists())
        assertTrue(!File(sessionFile.parentFile, "session.dat.tmp").exists())
    }

    @Test
    fun `load never returns the encrypted bytes or exposes the payload`() {
        store.save(StoredSession(uid = "uid", refreshToken = "secret-refresh-token", scope = "full"))

        val onDisk = String(sessionFile.readBytes(), Charsets.ISO_8859_1)

        assertTrue(!onDisk.contains("secret-refresh-token"))
    }

    /**
     * Software AES-256-GCM implementing exactly [SessionCrypto]'s wire contract
     * (`IV(12) || ciphertext||tag`). Not the production path — test double only.
     */
    private class SoftwareGcmCrypto : SessionCrypto {
        private val key = KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey()
        }
        private val secret = SecretKeySpec(key.encoded, "AES")
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(data: ByteArray): ByteArray {
            val iv = data.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            return cipher.doFinal(data.copyOfRange(12, data.size))
        }
    }
}
