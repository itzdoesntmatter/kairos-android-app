package digital.tonima.protonsync.auth

import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import java.nio.charset.StandardCharsets

/**
 * The bcrypt password hash of Proton's SRP variant (auth-flow.md §2.3 step 4).
 *
 * Derivation, byte-exact:
 * 1. `bcryptSalt = (salt || "proton")[0:16]` — the server salt is 10 bytes; appending the
 *    ASCII string "proton" yields exactly 16.
 * 2. bcrypt(password, salt, cost 10) with the `$2y$` version marker — BouncyCastle's
 *    `OpenBSDBCrypt.generate("2y", …)` produces the same hash bytes as the flow's
 *    documented implementations (Spring `BCryptPasswordEncoder` + `$2a$`→`$2y$` rewrite;
 *    the spec's bcrypt-base64 encode/decode roundtrip of the 16-byte salt is an identity
 *    operation and is skipped). Byte-exactness is pinned by the published-vector tests.
 *
 * The resulting string's ASCII bytes feed `expandHash(hashedPassword || modulus)` — see
 * [SrpClient].
 *
 * Known limitation (documented, not a bug we can resolve without a live capture): OpenBSD
 * bcrypt implementations differ in their handling of passwords longer than 55 bytes
 * (the 2011 crypt_blowfish `$2a$` bug). Behaviour below 55 bytes is universal and is what
 * the published vectors cover.
 */
internal class BcryptPasswordHasher {

    /**
     * Full bcrypt hash string (`$2y$10$…`, 60 ASCII characters) for [passwordBytes] and the
     * wire-format [salt] from `auth/v4/info`. The caller owns the returned bytes and must
     * zeroize them after use — the string feeds the SRP `x` derivation and is secret-derived.
     */
    fun hashPassword(passwordBytes: ByteArray, salt: ByteArray): ByteArray {
        val bcryptSalt = ByteArray(SALT_BYTES)
        for (i in 0 until SALT_BYTES) {
            bcryptSalt[i] =
                when {
                    i < salt.size -> salt[i]
                    i - salt.size < SALT_SUFFIX.length -> SALT_SUFFIX[i - salt.size].code.toByte()
                    else -> 0
                }
        }
        return OpenBSDBCrypt.generate("2y", passwordBytes, bcryptSalt, BCRYPT_COST)
            .toByteArray(StandardCharsets.US_ASCII)
    }

    companion object {
        private const val BCRYPT_COST = 10
        private const val SALT_SUFFIX = "proton"
        private const val SALT_BYTES = 16
    }
}
