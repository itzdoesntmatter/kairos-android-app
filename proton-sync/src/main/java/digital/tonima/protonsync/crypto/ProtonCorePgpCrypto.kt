package digital.tonima.protonsync.crypto

import me.proton.core.crypto.android.pgp.GOpenPGPCrypto
import me.proton.core.crypto.common.pgp.PGPCrypto
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.crypto.common.pgp.VerificationContext
import me.proton.core.crypto.common.pgp.VerificationTime
import java.nio.charset.StandardCharsets

/**
 * Production [ProtonCrypto]: adapter over `me.proton.core:crypto-android` (GOpenPGPCrypto),
 * which wraps `me.proton.crypto:android-golib` — the gomobile Go OpenPGP library the
 * reference app binds through `bridge.Bridge` (crypto-envelope.md §1). Envelope-exact by
 * construction (architecture D1/§5): PKESK‖SEIPD handling, session-key recovery and armor
 * parsing all run through the same Go code the RE spec documents.
 *
 * Method-to-Bridge mapping (crypto-envelope.md §1.1):
 * | This facade                | bridge.Bridge equivalent            |
 * |----------------------------|-------------------------------------|
 * | [canParseKey]              | canParseKey(armored)                |
 * | [unlock]                   | newKeyRing(armored, passphrase)     |
 * | [decryptArmoredText]       | KeyRing.decryptArmored              |
 * | [decryptSessionKey]        | KeyRing.decryptSessionKey           |
 * | [decryptDataWithSessionKey]| decryptDataWithSessionKey           |
 * | [decryptBinary]            | decryptBinary(keyPacket ‖ dataPacket)|
 * | [verifyDetached]           | KeyRing.verifyDetachedArmored       |
 *
 * GPL-3.0 boundary (architecture §9 item 13): every crypto-android import in the module
 * lives in this one file; the combined APK distributes under GPL-3.0 terms.
 *
 * IMPORTANT: constructing this class loads the native Go library — JVM unit tests must
 * never instantiate it (they use fakes of [ProtonCrypto]; real-library tests are the W3
 * device-gated KAT suite, architecture §10).
 */
class ProtonCorePgpCrypto : ProtonCrypto {

    private val pgp: PGPCrypto = GOpenPGPCrypto()

    override fun canParseKey(armoredKey: String): Boolean =
        try {
            pgp.isPrivateKey(armoredKey) || pgp.isPublicKey(armoredKey) || pgp.isValidKey(armoredKey)
        } catch (e: Exception) {
            false
        }

    override fun unlock(
        armoredKey: String,
        passphrase: ByteArray,
    ): ByteArray =
        try {
            pgp.unlock(armoredKey, passphrase).use { unlocked -> unlocked.value.copyOf() }
        } catch (e: Exception) {
            throw CryptoDecryptException("key unlock failed (wrong passphrase or unparseable key)", e)
        }

    override fun decryptArmoredText(
        armoredMessage: String,
        privateKey: ByteArray,
    ): String =
        String(decryptArmoredData(armoredMessage, privateKey), StandardCharsets.UTF_8)

    override fun decryptArmoredData(
        armoredMessage: String,
        privateKey: ByteArray,
    ): ByteArray =
        try {
            pgp.decryptData(armoredMessage, privateKey)
        } catch (e: Exception) {
            throw CryptoDecryptException("armored message decryption failed", e)
        }

    override fun decryptBinary(
        keyPacket: ByteArray,
        dataPacket: ByteArray,
        privateKey: ByteArray,
    ): ByteArray {
        val sessionKey = decryptSessionKey(keyPacket, privateKey)
        return decryptDataWithSessionKey(dataPacket, sessionKey)
    }

    override fun decryptSessionKey(
        keyPacket: ByteArray,
        privateKey: ByteArray,
    ): ByteArray =
        try {
            pgp.decryptSessionKey(keyPacket, privateKey).use { key -> key.key.copyOf() }
        } catch (e: Exception) {
            throw CryptoDecryptException("session key recovery failed", e)
        }

    override fun decryptDataWithSessionKey(
        dataPacket: ByteArray,
        sessionKey: ByteArray,
    ): ByteArray =
        try {
            SessionKey(sessionKey).use { key -> pgp.decryptData(dataPacket, key) }
        } catch (e: Exception) {
            throw CryptoDecryptException("session-key data decryption failed", e)
        }

    override fun verifyDetached(
        data: ByteArray,
        armoredSignature: String,
        senderArmoredKeys: List<String>,
    ): Boolean =
        senderArmoredKeys.any { sender ->
            try {
                pgp.verifyData(
                    data,
                    armoredSignature,
                    sender,
                    VerificationTime.Now,
                    VerificationContext("", VerificationContext.ContextRequirement.NotRequired),
                )
            } catch (e: Exception) {
                false
            }
        }

    override fun fingerprint(armoredKey: String): String =
        try {
            pgp.getSHA256Fingerprint(armoredKey)
        } catch (e: Exception) {
            "<unparseable>"
        }
}
