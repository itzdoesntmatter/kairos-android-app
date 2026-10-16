package digital.tonima.protonsync.session

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * The only persistent home of Proton session material (security-audit.md §2.4.17, D5).
 *
 * Wire format of `files/proton-session/session.dat`:
 * ```
 * MAGIC("PSS1", 4 bytes) || IV(12) || AES-256-GCM(payload, tag 128)
 * ```
 * The payload is a small JSON object ([StoredSession]) holding exactly the refresh token,
 * UID and scope. The access token is **never** persisted — it lives in memory only
 * ([SessionManager]) and is rebuilt from the refresh token at process start (D5).
 *
 * Invariants:
 * - Writes are atomic: payload → `session.dat.tmp` (fsync) → rename. A crash mid-write can
 *   never leave a half-written session file.
 * - Every write uses a fresh random 12-byte IV (from the GCM cipher).
 * - Tamper detection is AEAD-native: any bit flip fails GCM authentication, the file is
 *   deleted, and [load] returns null — the caller treats that as logged out.
 * - No plaintext fallback exists anywhere: [SessionCrypto] refuses to operate without the
 *   Keystore key rather than degrade (forbidden fallback, security-audit.md §3.1).
 * - Backup exclusion for `files/proton-session/` lives in the app's backup rule files
 *   (security-audit.md CR-1); this file is additionally MODE_PRIVATE by virtue of app storage.
 */
class SessionStore(
    private val sessionFile: File,
    private val crypto: SessionCrypto,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Loads the stored session, or null when absent, corrupt, or tampered (file is wiped). */
    fun load(): StoredSession? {
        if (!sessionFile.exists()) return null
        return try {
            val raw = sessionFile.readBytes()
            val payload = decryptFile(raw)
            json.decodeFromString(StoredSession.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            logcat(TAG, LogPriority.WARN) {
                "session file unreadable — discarding (reason: ${e::class.simpleName})"
            }
            wipe()
            null
        }
    }

    /** Atomically persists [session]. The file's parent directory is created on demand. */
    fun save(session: StoredSession) {
        sessionFile.parentFile?.mkdirs()
        val payload = json.encodeToString(StoredSession.serializer(), session).toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(payload)

        val tmp = File(sessionFile.parentFile, TEMP_NAME)
        FileOutputStream(tmp).use { out ->
            out.write(MAGIC)
            out.write(encrypted)
            out.fd.sync()
        }
        if (!tmp.renameTo(sessionFile)) {
            throw SessionStoreException("failed to atomically replace the session file")
        }
    }

    /** Deletes the session file and any partial write. */
    fun wipe() {
        sessionFile.delete()
        sessionFile.parentFile?.let { File(it, TEMP_NAME).delete() }
    }

    private fun decryptFile(raw: ByteArray): ByteArray {
        if (raw.size < MAGIC.size + 1) {
            throw SessionStoreException("session file too short")
        }
        val magic = raw.copyOfRange(0, MAGIC.size)
        if (!magic.contentEquals(MAGIC)) {
            throw SessionStoreException("unknown session file format")
        }
        val encrypted = raw.copyOfRange(MAGIC.size, raw.size)
        return crypto.decrypt(encrypted)
    }

    companion object {
        private const val TAG = "ProtonSessionStore"
        private const val TEMP_NAME = "session.dat.tmp"
        private val MAGIC = ByteBuffer.allocate(4).put("PSS1".toByteArray(Charsets.US_ASCII)).array()

        /** Canonical location: `files/proton-session/session.dat` (architecture §4). */
        fun create(context: Context): SessionStore =
            SessionStore(
                sessionFile = File(context.filesDir, "proton-session/session.dat"),
                crypto = AndroidKeyStoreSessionCrypto(context),
            )
    }
}

class SessionStoreException(message: String) : Exception(message)

/**
 * The persisted session shape. [accessToken] is deliberately NOT a field — it never touches
 * disk; [SessionManager] keeps it in memory (security-audit.md §2.4.18).
 */
@Serializable
data class StoredSession(
    val uid: String,
    val refreshToken: String,
    val scope: String,
)
