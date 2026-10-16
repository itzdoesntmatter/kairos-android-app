package digital.tonima.protonsync.crypto

import digital.tonima.protonsync.api.dto.KeySaltInfo
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The key-password KDF: login password → OpenPGP private-key passphrase (auth-flow.md §2.6,
 * crypto-envelope.md §2.6).
 *
 * Derivation, byte-exact: `keyPassword = bcrypt(passwordBytes, salt16)[29:]` — the bcrypt
 * string with its `$2y$NN$` prefix (29 ASCII chars) stripped, kept as bytes. [salt16] is the
 * first 16 bytes of the base64-decoded KeySalt; the reference's "bcrypt-b64 roundtrip" of
 * those bytes is an identity operation and is skipped (auth-flow.md §2.6.4).
 *
 * Cost factor: 10 (Spring `BCryptPasswordEncoder` default; the reference's key-salt handling
 * uses the same family — crypto-envelope.md VERIFY-NEEDED #4 flags that the salt string may
 * embed a cost, which a live capture in W3 must confirm).
 *
 * Password hygiene (security-audit §2.1.5): [password] and every derived buffer are zeroized
 * before this object returns; the returned passphrase must be zeroized by the caller after
 * the key is unlocked.
 */
object KeyPasswordDeriver {

    /** bcrypt cost for the key-password KDF (see class doc). */
    const val BCRYPT_COST = 10

    /** Length of the `$2y$NN$` prefix stripped from the bcrypt string. */
    const val BCRYPT_PREFIX_LENGTH = 29

    /**
     * Derives the key password for [passwordBytes] against the key salt [keySalt]
     * (base64, 16 bytes). The caller's array is NOT modified — derivation works on a copy —
     * but the CALLER still owns the input array and must zeroize it when the login
     * transaction ends (the same password bytes unlock several keys in sequence).
     */
    fun deriveKeyPassword(
        passwordBytes: ByteArray,
        keySalt: String,
    ): ByteArray {
        val salt16 = decodeSalt16(keySalt)
        val passwordCopy = passwordBytes.copyOf()
        val bcrypt =
            try {
                OpenBSDBCrypt.generate("2y", passwordCopy, salt16, BCRYPT_COST)
                    .toByteArray(StandardCharsets.US_ASCII)
            } finally {
                passwordCopy.fill(0)
                salt16.fill(0)
            }
        return bcrypt.copyOfRange(BCRYPT_PREFIX_LENGTH, bcrypt.size).also {
            bcrypt.fill(0)
        }
    }

    /**
     * Salt fallback chain for the primary user key (auth-flow.md §2.6.3): per-key salt by key
     * ID → first salt the server returned → the auth-response `KeySalt`. Null when nothing is
     * available (the caller then surfaces a login error instead of guessing).
     */
    fun selectPrimarySalt(
        primaryKeyId: String,
        keySalts: List<KeySaltInfo>,
        authKeySalt: String?,
    ): String? =
        keySalts.firstOrNull { it.id == primaryKeyId }?.keySalt
            ?: keySalts.firstOrNull()?.keySalt
            ?: authKeySalt

    private fun decodeSalt16(keySalt: String): ByteArray {
        val decoded =
            try {
                Base64.getDecoder().decode(keySalt)
            } catch (e: IllegalArgumentException) {
                throw CryptoDecryptException("key salt is not valid base64", e)
            }
        return decoded.copyOf(minOf(decoded.size, SALT_BYTES))
    }

    private const val SALT_BYTES = 16
}
