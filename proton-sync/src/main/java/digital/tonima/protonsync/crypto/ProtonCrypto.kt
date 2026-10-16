package digital.tonima.protonsync.crypto

/**
 * The only surface `:proton-sync` code touches for OpenPGP work (architecture §5).
 *
 * The production implementation is [ProtonCorePgpCrypto], a thin adapter over
 * `me.proton.core:crypto-android` (which wraps `me.proton.crypto:android-golib` — the exact
 * gomobile Go library the reference app binds, `crypto-envelope.md` §1). Keeping the GPL-3.0
 * linked surface behind this interface is deliberate:
 *
 * 1. It is the swap seam documented in architecture §5 (every crypto-android touchpoint
 *    lives in one file — clean separation for the GPL compliance record, §9 item 13).
 * 2. JVM unit tests (Robolectric) cannot load the native Go library; they exercise every
 *    envelope-policy path against fakes of this interface instead. Real key-vector tests
 *    against the Go lib are device-gated (architecture §10, W3).
 *
 * Method semantics mirror `crypto-envelope.md` §1/§3/§5: armor parsing, keyring unlock,
 * PKESK session-key unwrap, and SEIPD decryption under a recovered session key.
 */
interface ProtonCrypto {
    /** `Bridge.canParseKey` — true when [armoredKey] parses as an OpenPGP key. */
    fun canParseKey(armoredKey: String): Boolean

    /**
     * `Bridge.newKeyRing(armored, passphrase)` — unlocks [armoredKey] with [passphrase] and
     * returns the unlocked key bytes usable in the decrypt/verify calls below. Throws
     * [CryptoDecryptException] when the passphrase is wrong or the key is unparseable.
     */
    fun unlock(armoredKey: String, passphrase: ByteArray): ByteArray

    /**
     * `KeyRing.decryptArmored` (text variant) — decrypts an armored PGP message with the
     * private key and returns the UTF-8 plaintext. Used for calendar passphrases encrypted
     * to the user key and for address-key tokens (crypto-envelope.md §2.3/§2.4).
     */
    fun decryptArmoredText(armoredMessage: String, privateKey: ByteArray): String

    /** `KeyRing.decryptArmored` (binary variant) — same, returning raw plaintext bytes. */
    fun decryptArmoredData(armoredMessage: String, privateKey: ByteArray): ByteArray

    /**
     * `KeyRing.decryptBinary(keyPacket ‖ dataPacket)` — the binary envelope split for
     * transport (crypto-envelope.md §3): one OpenPGP message whose PKESK is followed by the
     * SEIPD data packet. Implemented as unwrap + decrypt for clarity; returns the plaintext
     * bytes. Throws [CryptoDecryptException] on any AEAD/wrap failure (drop the event, never
     * present garbage — security-audit §3.5).
     */
    fun decryptBinary(
        keyPacket: ByteArray,
        dataPacket: ByteArray,
        privateKey: ByteArray,
    ): ByteArray

    /**
     * `KeyRing.decryptSessionKey(keyPacket)` — recovers the session key from a PKESK packet.
     * Returns the raw session-key bytes.
     */
    fun decryptSessionKey(
        keyPacket: ByteArray,
        privateKey: ByteArray,
    ): ByteArray

    /**
     * `Bridge.decryptDataWithSessionKey(sessionKey, dataPacket)` — opens a SEIPD data packet
     * with an explicit session key. Returns the plaintext bytes.
     */
    fun decryptDataWithSessionKey(
        dataPacket: ByteArray,
        sessionKey: ByteArray,
    ): ByteArray

    /**
     * Detached-signature verification (crypto-envelope.md §3): returns true when
     * [armoredSignature] verifies over [data] against ANY of [senderArmoredKeys]. The
     * verify-or-drop policy (security-audit HI-3, §3.4) lives in [EventPartDecryptor]; this
     * method is the raw check.
     */
    fun verifyDetached(
        data: ByteArray,
        armoredSignature: String,
        senderArmoredKeys: List<String>,
    ): Boolean

    /** Key identity for diagnostics; only a prefix may ever be logged (security-audit §3.7). */
    fun fingerprint(armoredKey: String): String
}

/** Any decryption/verification failure. Messages are safe to log (never include content). */
class CryptoDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)
