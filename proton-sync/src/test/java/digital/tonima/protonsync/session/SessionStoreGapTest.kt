package digital.tonima.protonsync.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
 * Gap-fill for [SessionStoreTest] (specs/test-plan.md SESS-03/04/05): every truncation/format
 * failure of the `MAGIC || IV || AES-GCM` container must end in a wipe with no plaintext
 * fallback, and a cipher failure must fail **closed** on both the write and the read path
 * (security-audit.md §2.4.17, §3.1, §5.2.6 — the reference's `B64:` degradation stays forbidden).
 */
class SessionStoreGapTest {

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
    fun `an empty file is discarded and wiped`() {
        writeRaw(ByteArray(0))

        assertNull(store.load())
        assertTrue(!sessionFile.exists())
    }

    @Test
    fun `a file holding only the magic header is discarded and wiped`() {
        writeRaw(MAGIC)

        assertNull(store.load())
        assertTrue(!sessionFile.exists())
    }

    @Test
    fun `a file truncated inside the IV is discarded and wiped`() {
        writeRaw(MAGIC + ByteArray(6) { 0x11 })

        assertNull(store.load())
        assertTrue(!sessionFile.exists())
    }

    @Test
    fun `a decryptable payload that is not a session object is discarded and wiped`() {
        // The container decrypts, but the JSON inside is not a StoredSession — the strict decode
        // must fail into the same wipe path (no partial session is ever returned).
        sessionFile.parentFile!!.mkdirs()
        sessionFile.writeBytes(MAGIC + """{"not":"a session"}""".toByteArray(Charsets.UTF_8))

        val passthroughStore = SessionStore(sessionFile, PassthroughCrypto())

        assertNull(passthroughStore.load())
        assertTrue(!sessionFile.exists())
    }

    @Test
    fun `every write uses a fresh IV — identical payloads produce different file bytes`() {
        val session = StoredSession(uid = "u", refreshToken = "r", scope = "full")

        store.save(session)
        val first = sessionFile.readBytes()
        store.save(session)
        val second = sessionFile.readBytes()

        assertEquals(MAGIC.toList(), first.copyOfRange(0, MAGIC.size).toList())
        assertTrue(
            "IV must differ per write (12-byte IV, security-audit §3.2)",
            !first.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
                .contentEquals(second.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)),
        )
        assertTrue(!first.contentEquals(second))
        assertEquals(session, store.load())
    }

    @Test
    fun `discarding an unreadable session also removes a leftover temp file`() {
        store.save(StoredSession(uid = "u", refreshToken = "r", scope = "full"))
        val leftover = File(sessionFile.parentFile, "session.dat.tmp").apply { writeBytes(byteArrayOf(9)) }
        val corrupted = sessionFile.readBytes()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0x5A).toByte()
        sessionFile.writeBytes(corrupted)

        assertNull(store.load())

        assertTrue(!sessionFile.exists())
        assertTrue(!leftover.exists())
    }

    @Test
    fun `a cipher failure on save fails closed — nothing reaches disk`() {
        val failing = SessionStore(sessionFile, FailingCrypto("keystore gone"))

        val error = assertThrows(SessionCryptoException::class.java) {
            failing.save(StoredSession(uid = "u", refreshToken = "refresh-secret", scope = "full"))
        }

        assertTrue(error.message!!.contains("keystore gone"))
        assertTrue("no session file may exist after a failed write", !sessionFile.exists())
        val leftovers = sessionFile.parentFile?.listFiles().orEmpty()
        assertEquals(
            "a failed write must not leave plaintext material behind",
            0,
            leftovers.count { String(it.readBytes(), Charsets.ISO_8859_1).contains("refresh-secret") },
        )
    }

    @Test
    fun `a cipher failure on load discards the file instead of falling back to plaintext`() {
        writeRaw(MAGIC + ByteArray(48) { 0x7F })
        val failing = SessionStore(sessionFile, FailingCrypto("tampered"))

        assertNull(failing.load())

        assertTrue(!sessionFile.exists())
    }

    private fun writeRaw(bytes: ByteArray) {
        sessionFile.parentFile!!.mkdirs()
        sessionFile.writeBytes(bytes)
    }

    /** Software AES-256-GCM with the same wire contract and IV-width guard as production. */
    private class SoftwareGcmCrypto : SessionCrypto {
        private val secret = SecretKeySpec(
            KeyGenerator.getInstance("AES").run {
                init(256)
                generateKey()
            }.encoded,
            "AES",
        )
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(data: ByteArray): ByteArray {
            if (data.size <= IV_BYTES) {
                throw SessionCryptoException("session data too short to contain an IV")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, data.copyOfRange(0, IV_BYTES)))
            return cipher.doFinal(data.copyOfRange(IV_BYTES, data.size))
        }
    }

    /** Test double that hides the container: encrypt/decrypt are the identity. */
    private class PassthroughCrypto : SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(data: ByteArray): ByteArray = data
    }

    /** Test double for a Keystore that is gone: both directions refuse. */
    private class FailingCrypto(private val reason: String) : SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = throw SessionCryptoException(reason)
        override fun decrypt(data: ByteArray): ByteArray = throw SessionCryptoException(reason)
    }

    private companion object {
        const val IV_BYTES = 12
        val MAGIC = "PSS1".toByteArray(Charsets.US_ASCII)
    }
}
