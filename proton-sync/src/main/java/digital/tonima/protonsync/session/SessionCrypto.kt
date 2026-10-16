package digital.tonima.protonsync.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest crypto for the session file (security-audit.md §2.4.17 / §3.2).
 *
 * Contract: AES-256-GCM, 12-byte random IV per write, 128-bit tag, key held in
 * AndroidKeyStore under a dedicated alias, non-exportable, `setRandomizedEncryptionRequired`,
 * `setUnlockedDeviceRequired(true)` (no pre-unlock credential exposure), no user-auth
 * requirement in v1 (background refresh must work; the seam for a biometric wave is the
 * spec parameter, per architecture §9.12).
 *
 * **There is deliberately no fallback path.** If the Keystore is unavailable, construction
 * throws [SessionCryptoException] and the caller refuses to persist the session — the
 * reference app's plaintext `B64:` fallback (auth-flow.md §4) is explicitly forbidden for
 * Kairos (security-audit.md §3.1).
 */
interface SessionCrypto {
    /** Encrypts [plaintext] and returns `IV(12) || ciphertext||tag`. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Inverse of [encrypt]; throws [SessionCryptoException] on auth/tag failure (tamper). */
    fun decrypt(data: ByteArray): ByteArray
}

class SessionCryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Production [SessionCrypto]: AndroidKeyStore-backed AES-256-GCM.
 *
 * Key aliases: `proton_session_key` encrypts `files/proton-session/session.dat`. The
 * wave-2 key-material file (`keys.dat`) gets its own alias so one key's loss never unlocks
 * the other (security-audit.md §3.2).
 */
class AndroidKeyStoreSessionCrypto(
    context: Context,
    keyAlias: String = KEY_ALIAS,
) : SessionCrypto {

    private val keyAlias = keyAlias

    private val keyStore: KeyStore =
        try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        } catch (e: Exception) {
            throw SessionCryptoException(
                "AndroidKeyStore is unavailable — refusing to store session material " +
                    "without encryption",
                e,
            )
        }

    private val secretKey: SecretKey by lazy { getOrCreateKey() }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = try {
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            throw SessionCryptoException("session encryption failed", e)
        }
        return iv + ciphertext
    }

    override fun decrypt(data: ByteArray): ByteArray {
        if (data.size <= IV_BYTES) {
            throw SessionCryptoException("session data too short to contain an IV")
        }
        val iv = data.copyOfRange(0, IV_BYTES)
        val ciphertext = data.copyOfRange(IV_BYTES, data.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SessionCryptoException("session decryption failed (tampered or corrupt)", e)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generator.generateKey()
        } catch (e: Exception) {
            throw SessionCryptoException(
                "AndroidKeyStore is unavailable — refusing to store session material " +
                    "without encryption",
                e,
            )
        }
    }

    companion object {
        const val KEY_ALIAS = "proton_session_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
