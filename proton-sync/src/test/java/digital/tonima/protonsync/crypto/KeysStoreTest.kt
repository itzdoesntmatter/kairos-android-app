package digital.tonima.protonsync.crypto

import digital.tonima.protonsync.session.SessionCrypto
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * KeysStore round-trip, tamper detection and wipe — the keys.dat at-rest contract
 * (architecture §5, security-audit §3.1). Mirrors SessionStoreTest's software-GCM approach:
 * the real Keystore round-trip is the device-gated W3 test (Robolectric's Keystore is a
 * mock, architecture §10).
 */
class KeysStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keysFile: File
    private lateinit var store: KeysStore

    @Before
    fun setUp() {
        keysFile = File(tempFolder.root, "proton-session/keys.dat")
        store = KeysStore(keysFile, SoftwareGcmCrypto())
    }

    @Test
    fun `round-trips stored key material through the encrypted file`() {
        val keys =
            listOf(
                StoredKeyMaterial(kind = "user", id = "k1", armoredKey = "armored-1", passphrase = "cGFzcw=="),
                StoredKeyMaterial(kind = "calendar", id = "cal-9", armoredKey = "armored-2", passphrase = "c2VjcmV0"),
            )

        store.save(keys)

        assertEquals(keys, store.load())
    }

    @Test
    fun `missing file loads as null`() {
        assertNull(store.load())
    }

    @Test
    fun `a flipped byte in the ciphertext is detected and wipes the file`() {
        store.save(listOf(StoredKeyMaterial(kind = "user", id = "k", armoredKey = "a", passphrase = "cA==")))
        val bytes = keysFile.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        keysFile.writeBytes(bytes)

        assertNull(store.load())
        assertFalse(keysFile.exists())
    }

    @Test
    fun `wipe removes the file and its partial write`() {
        store.save(listOf(StoredKeyMaterial(kind = "user", id = "k", armoredKey = "a", passphrase = "cA==")))

        store.wipe()

        assertFalse(keysFile.exists())
        assertFalse(File(tempFolder.root, "proton-session/keys.dat.tmp").exists())
    }

    @Test
    fun `save is idempotent - second save replaces the first`() {
        store.save(listOf(StoredKeyMaterial(kind = "user", id = "k1", armoredKey = "old", passphrase = "cA==")))
        store.save(listOf(StoredKeyMaterial(kind = "user", id = "k1", armoredKey = "new", passphrase = "cA==")))

        assertEquals("new", store.load()!!.single().armoredKey)
        assertTrue(keysFile.exists())
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
