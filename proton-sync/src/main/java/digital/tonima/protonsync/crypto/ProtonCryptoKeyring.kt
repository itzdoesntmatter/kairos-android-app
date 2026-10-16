package digital.tonima.protonsync.crypto

import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.logcat
import java.io.Closeable

/**
 * The in-memory keyring (crypto-envelope.md §2): user keys, address keys and lazily-unlocked
 * calendar keys, backed by [KeysStore] for process-death survival.
 *
 * Reference semantics reproduced deliberately (crypto-envelope.md §2.5): every unlocked key
 * joins ONE shared ring — user + address + calendar keys together — which is why a single
 * ring can open calendar PKESKs. Ring lookups therefore try every key, not a selected one.
 *
 * Key lifecycle:
 * - Login builds the ring: user keys unlocked with the password-derived keyPassword, address
 *   keys with the keyPassword or the token-decrypted passphrase (auth-flow.md §2.6.6).
 * - Calendar keys are unlocked lazily at first sync: the calendar passphrase is decrypted
 *   WITH the user ring, then used to unlock the calendar private key (crypto-envelope.md §2.5).
 * - Every successful unlock is persisted to [KeysStore] immediately; [restore] rebuilds the
 *   ring after process death. Wipe on logout (security-audit §2.3.15).
 *
 * Threading: engine runs are serialized per unique work name (WorkManager), login/logout
 * happen on the main dispatcher — a [kotlinx.coroutines.sync.Mutex] guards the state anyway
 * because logout can race an in-flight sync.
 */
class ProtonCryptoKeyring(
    private val crypto: ProtonCrypto,
    private val keysStore: KeysStore,
) : Closeable {

    private data class UnlockedEntry(
        val unlocked: ByteArray,
        val armored: String,
    )

    /** The shared ring: user + address keys (calendar keys join it for PKESK opens). */
    private val userRing = mutableListOf<UnlockedEntry>()

    /** Per-calendar keys, also appended to the shared ring on unlock. */
    private val calendarKeys = mutableMapOf<String, UnlockedEntry>()

    /** Unlock attempts must not interleave with logout wipe. */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** Rebuilds the ring from keys.dat (process start). Unusable entries are skipped. */
    suspend fun restore() = mutex.withLock {
        keysStore.load()?.forEach { stored ->
            val passphrase =
                try {
                    Base64.getDecoder().decode(stored.passphrase)
                } catch (e: IllegalArgumentException) {
                    return@forEach
                }
            val unlocked =
                try {
                    crypto.unlock(stored.armoredKey, passphrase)
                } catch (e: CryptoDecryptException) {
                    logcat(TAG, LogPriority.WARN) {
                        "skipping unusable stored key ${stored.kind}/${stored.id}: ${e.message}"
                    }
                    return@forEach
                } finally {
                    passphrase.fill(0)
                }
            addEntry(stored.kind, stored.id, stored.armoredKey, unlocked)
        }
    }

    /** Unlocks and registers a user key (primary or additional, auth-flow.md §2.6.5). */
    suspend fun addUserKey(
        keyId: String,
        armoredKey: String,
        passphrase: ByteArray,
    ) = addKey(KIND_USER, keyId, armoredKey, passphrase)

    /** Unlocks and registers an address key (auth-flow.md §2.6.6). */
    suspend fun addAddressKey(
        addressId: String,
        armoredKey: String,
        passphrase: ByteArray,
    ) = addKey(KIND_ADDRESS, addressId, armoredKey, passphrase)

    /**
     * Unlocks and registers a calendar key (crypto-envelope.md §2.5). [passphrase] is the
     * decrypted calendar passphrase. The key joins the shared ring exactly like the
     * reference's `keyRing.addKey` behaviour.
     */
    suspend fun addCalendarKey(
        calendarId: String,
        armoredKey: String,
        passphrase: ByteArray,
    ) = addKey(KIND_CALENDAR, calendarId, armoredKey, passphrase)

    /** The unlocked private-key bytes of the whole ring (user + address + calendar keys). */
    suspend fun ringKeys(): List<ByteArray> = mutex.withLock {
        (userRing + calendarKeys.values).map { it.unlocked.copyOf() }
    }

    /** The armored forms of the whole ring — the candidate signer keys for verification. */
    suspend fun verificationArmoredKeys(): List<String> = mutex.withLock {
        (userRing + calendarKeys.values).map { it.armored }
    }

    /** The calendar key for [calendarId], or null when not unlocked yet. */
    suspend fun calendarKey(calendarId: String): ByteArray? = mutex.withLock {
        calendarKeys[calendarId]?.unlocked?.copyOf()
    }

    /**
     * Decrypts an armored message with the whole ring, trying every key until one opens it
     * (ring semantics — one ring opens PKESKs, crypto-envelope.md §2.5). Used for calendar
     * passphrases and address-key tokens encrypted to the user key. Returns null when no
     * ring key can decrypt.
     */
    suspend fun decryptArmoredWithRing(armoredMessage: String): String? = mutex.withLock {
        for (entry in userRing + calendarKeys.values) {
            try {
                return@withLock crypto.decryptArmoredText(armoredMessage, entry.unlocked)
            } catch (e: CryptoDecryptException) {
                // next ring key
            }
        }
        null
    }

    /** True when at least one user key is unlocked (login completed). */
    suspend fun hasUserKey(): Boolean = mutex.withLock { userRing.isNotEmpty() }

    /** Wipes all unlocked material from memory (logout; keys.dat is wiped by the caller). */
    override fun close() {
        userRing.forEach { it.unlocked.fill(0) }
        calendarKeys.values.forEach { it.unlocked.fill(0) }
        userRing.clear()
        calendarKeys.clear()
    }

    private suspend fun addKey(
        kind: String,
        id: String,
        armoredKey: String,
        passphrase: ByteArray,
    ) = mutex.withLock {
        try {
            val unlocked = crypto.unlock(armoredKey, passphrase)
            addEntry(kind, id, armoredKey, unlocked)
            persist(kind, id, armoredKey, passphrase)
        } finally {
            passphrase.fill(0)
        }
    }

    private fun addEntry(
        kind: String,
        id: String,
        armored: String,
        unlocked: ByteArray,
    ) {
        val entry = UnlockedEntry(unlocked, armored)
        if (kind == KIND_CALENDAR) calendarKeys[id] = entry else userRing += entry
    }

    private fun persist(
        kind: String,
        id: String,
        armored: String,
        passphrase: ByteArray,
    ) {
        val stored =
            StoredKeyMaterial(
                kind = kind,
                id = id,
                armoredKey = armored,
                passphrase = Base64.getEncoder().encodeToString(passphrase),
            )
        val current = keysStore.load().orEmpty()
            .filterNot { it.kind == kind && it.id == id } + stored
        keysStore.save(current)
    }

    companion object {
        const val KIND_USER = "user"
        const val KIND_ADDRESS = "address"
        const val KIND_CALENDAR = "calendar"
        private const val TAG = "ProtonCryptoKeyring"
    }
}
