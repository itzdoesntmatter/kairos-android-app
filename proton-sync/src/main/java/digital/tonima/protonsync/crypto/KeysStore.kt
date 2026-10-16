package digital.tonima.protonsync.crypto

import android.content.Context
import digital.tonima.protonsync.session.SessionCrypto
import digital.tonima.protonsync.session.SessionCryptoException
import digital.tonima.protonsync.session.SessionStoreException
import digital.tonima.protonsync.session.AndroidKeyStoreSessionCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * At-rest storage of unlocked OpenPGP key material: `files/proton-session/keys.dat`
 * (architecture §5, security-audit §3.1, asset A5).
 *
 * Why this file exists: after login the account's unlocked private keys (user, address,
 * calendar) and their passphrases must survive process death or background sync is dead on
 * arrival. The mailbox password itself is NEVER retained — only the re-encrypted unlocked
 * material, wrapped under a dedicated non-exportable Android Keystore key (alias
 * `proton_keys_key`, separate from the session key so one key's loss never unlocks the
 * other — security-audit §3.2). The documented tradeoff: whoever can both read this
 * app-private file AND use the Keystore key already has device-level access.
 *
 * Wire format: `MAGIC("PSK1", 4 bytes) ‖ IV(12) ‖ AES-256-GCM(JSON, tag 128)`. Same
 * contract as [SessionStore] (atomic tmp+rename, tamper wipe, no plaintext fallback of any
 * kind — the reference's `B64:` fallback is explicitly forbidden).
 */
class KeysStore(
    private val keysFile: File,
    private val crypto: SessionCrypto,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Loads the stored keys, or null when absent/corrupt/tampered (file is wiped). */
    fun load(): List<StoredKeyMaterial>? {
        if (!keysFile.exists()) return null
        return try {
            val raw = keysFile.readBytes()
            val payload = decryptFile(raw)
            json.decodeFromString(StoredKeys.serializer(), String(payload, Charsets.UTF_8)).keys
        } catch (e: Exception) {
            logcat(TAG, LogPriority.WARN) {
                "keys file unreadable — discarding (reason: ${e::class.simpleName})"
            }
            wipe()
            null
        }
    }

    /** Atomically persists [keys]. Parent directory is created on demand. */
    fun save(keys: List<StoredKeyMaterial>) {
        keysFile.parentFile?.mkdirs()
        val payload = json.encodeToString(StoredKeys.serializer(), StoredKeys(keys))
            .toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(payload)

        val tmp = File(keysFile.parentFile, TEMP_NAME)
        FileOutputStream(tmp).use { out ->
            out.write(MAGIC)
            out.write(encrypted)
            out.fd.sync()
        }
        if (!tmp.renameTo(keysFile)) {
            throw SessionStoreException("failed to atomically replace the keys file")
        }
    }

    /** Deletes the keys file and any partial write (logout step 2, security-audit §2.3.15). */
    fun wipe() {
        keysFile.delete()
        keysFile.parentFile?.let { File(it, TEMP_NAME).delete() }
    }

    private fun decryptFile(raw: ByteArray): ByteArray {
        if (raw.size < MAGIC.size + 1) {
            throw SessionStoreException("keys file too short")
        }
        val magic = raw.copyOfRange(0, MAGIC.size)
        if (!magic.contentEquals(MAGIC)) {
            throw SessionStoreException("unknown keys file format")
        }
        val encrypted = raw.copyOfRange(MAGIC.size, raw.size)
        return crypto.decrypt(encrypted)
    }

    companion object {
        private const val TAG = "ProtonKeysStore"
        private const val TEMP_NAME = "keys.dat.tmp"
        private val MAGIC = ByteBuffer.allocate(4).put("PSK1".toByteArray(Charsets.US_ASCII)).array()

        /** Keystore alias for keys.dat — deliberately distinct from the session-file key. */
        const val KEYSTORE_ALIAS = "proton_keys_key"

        /** Canonical location: `files/proton-session/keys.dat` (architecture §5). */
        fun create(context: Context): KeysStore =
            KeysStore(
                keysFile = File(context.filesDir, "proton-session/keys.dat"),
                crypto = AndroidKeyStoreSessionCrypto(context, KEYSTORE_ALIAS),
            )
    }
}

/** JSON envelope — a list type so the file format survives adding fields. */
@Serializable
data class StoredKeys(
    val keys: List<StoredKeyMaterial> = emptyList(),
)

/**
 * One unlocked key at rest. [passphrase] is base64 of the passphrase bytes (secret-derived —
 * this is exactly the material the Keystore wrapper protects). [kind] is "user", "address"
 * or "calendar"; [id] identifies the key within its kind (key ID, address ID, calendar ID).
 * Content rule (security-audit §5.2): this file holds key material only — never event
 * content, never tokens.
 */
@Serializable
data class StoredKeyMaterial(
    val kind: String,
    val id: String,
    val armoredKey: String,
    val passphrase: String,
)
