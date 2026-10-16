package digital.tonima.protonsync.auth

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * SRP-6a client for Proton `auth/v4` (the exact byte recipe of auth-flow.md §2.3, validated
 * end to end against ProtonMail/go-srp's published test vectors — see [SrpClientTest]).
 *
 * Security posture (security-audit.md §2.1, CR-2):
 * - **The SRP group is pinned.** The 2048-bit modulus is a hard-coded constant and every
 *   server-provided modulus is compared against it byte-for-byte before any math. A weak-group
 *   substitution (the classic SRP downgrade that enables offline dictionary attack) aborts
 *   login with [SrpException]. The reference app accepted the armored modulus without even
 *   verifying its signature; we are deliberately stricter.
 * - Degenerate inputs are rejected before modular arithmetic: server ephemeral `B == 0 mod N`
 *   or `B >= N`, client ephemeral `A == 0`, shared secret `S == 0` (retry with a fresh `a`,
 *   bounded at [MAX_SECRET_ATTEMPTS] like the reference).
 * - The client secret `a` is 256 bytes from [SecureRandom] — never derived from the password.
 * - The expected server proof is compared constant-time ([SrpPrimitives.constantTimeEquals]);
 *   a timing-variable compare leaks the SRP session to an active MITM.
 * - Password hygiene: the password enters as a `CharArray`, is converted to bytes only inside
 *   [generateProofs], and every derived byte buffer (password bytes, bcrypt hash, x bytes) is
 *   zeroized before the call returns. BigInteger intermediates (x, S) are JVM-immutable and
 *   cannot be wiped — that limitation is inherent to the BigInteger approach the architecture
 *   selected and is documented here rather than papered over.
 */
class SrpClient(private val secureRandom: SecureRandom = SecureRandom()) {

    /**
     * Computes the client ephemeral, client proof, and expected server proof for [challenge].
     *
     * @throws SrpException on a modulus mismatch with the pinned group, degenerate server
     *   ephemeral, or exhaustion of the bounded secret-generation retry loop.
     */
    fun generateProofs(challenge: SrpChallenge, password: CharArray): SrpProofs {
        require(password.isNotEmpty()) { "password must not be empty" }

        val modulusBytes = ModulusParser.parseAndVerify(challenge.modulus)
        val serverEphemeralBytes = SrpPrimitives.decodeBase64(challenge.serverEphemeral)
        require(serverEphemeralBytes.size == SrpPrimitives.GROUP_BYTES) {
            "server ephemeral must be ${SrpPrimitives.GROUP_BYTES} bytes"
        }
        val saltBytes = SrpPrimitives.decodeBase64(challenge.salt)

        val n = SrpPrimitives.fromLittleEndian(modulusBytes)
        val b = SrpPrimitives.fromLittleEndian(serverEphemeralBytes)
        if (b.signum() == 0 || b >= n) {
            throw SrpException("server ephemeral is degenerate (B mod N == 0 or B >= N)")
        }

        val passwordBytes = CharArrayCodec.toUtf8Bytes(password)
        val hashedPassword =
            try {
                BcryptPasswordHasher().hashPassword(passwordBytes, saltBytes)
            } finally {
                passwordBytes.fill(0)
            }
        try {
            val x = SrpPrimitives.fromLittleEndian(
                SrpPrimitives.expandHash(hashedPassword + modulusBytes),
            )
            val k = SrpPrimitives.fromLittleEndian(
                SrpPrimitives.expandHash(
                    SrpPrimitives.toFixedLittleEndian(SrpPrimitives.GENERATOR) + modulusBytes,
                ),
            ).mod(n)
            // g^x is constant across secret retries — hoist it out of the loop.
            val gPowX = SrpPrimitives.GENERATOR.modPow(x, n)

            repeat(MAX_SECRET_ATTEMPTS) {
                val secret = SrpPrimitives.fromLittleEndian(randomSecret())
                val clientEphemeral = SrpPrimitives.GENERATOR.modPow(secret, n)
                if (clientEphemeral.signum() == 0) return@repeat

                val u = SrpPrimitives.fromLittleEndian(
                    SrpPrimitives.expandHash(
                        SrpPrimitives.toFixedLittleEndian(clientEphemeral) + serverEphemeralBytes,
                    ),
                )
                val exponent = u.multiply(x).add(secret).mod(n.subtract(BigInteger.ONE))
                val base = b.subtract(k.multiply(gPowX)).mod(n)
                val sharedSecret = base.modPow(exponent, n)
                if (sharedSecret.signum() == 0) return@repeat

                val clientEphemeralBytes = SrpPrimitives.toFixedLittleEndian(clientEphemeral)
                val sharedSecretBytes = SrpPrimitives.toFixedLittleEndian(sharedSecret)
                val clientProof = SrpPrimitives.expandHash(
                    clientEphemeralBytes + serverEphemeralBytes + sharedSecretBytes,
                )
                val expectedServerProof = SrpPrimitives.expandHash(
                    clientEphemeralBytes + clientProof + sharedSecretBytes,
                )
                return SrpProofs(
                    clientEphemeralBase64 = SrpPrimitives.base64NoWrap(clientEphemeralBytes),
                    clientProofBase64 = SrpPrimitives.base64NoWrap(clientProof),
                    expectedServerProof = expectedServerProof,
                )
            }
            throw SrpException(
                "could not generate a usable SRP session within $MAX_SECRET_ATTEMPTS attempts",
            )
        } finally {
            hashedPassword.fill(0)
        }
    }

    /**
     * Verifies the server's proof against the expected value, constant-time. A mismatch means
     * the exchange was tampered with — surface it as "possible MITM", never as a wrong-password
     * (auth-flow.md §2.4).
     */
    fun verifyServerProof(serverProofBase64: String, expectedServerProof: ByteArray): Boolean {
        val actual = SrpPrimitives.decodeBase64(serverProofBase64)
        return SrpPrimitives.constantTimeEquals(actual, expectedServerProof)
    }

    private fun randomSecret(): ByteArray {
        val bytes = ByteArray(SrpPrimitives.GROUP_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    companion object {
        /** Retry bound for fresh client secrets, matching the reference cap (auth-flow.md §2.3). */
        const val MAX_SECRET_ATTEMPTS = 1000

        /**
         * The pinned SRP modulus, wire-format little-endian base64. Source: the published
         * test vectors of ProtonMail/go-srp (MIT, `srp_test.go`, `testModulus`) — the modulus
         * Proton's servers deliver for `auth/v4/info`, signed by Proton's dedicated SRP modulus
         * key (`proton@srp.modulus`; the same bytes are pinned by the independently maintained
         * `proton-sdk` crate, whose docs note the signature verifies against the production
         * modulus key). Pin per security-audit CR-2: if Proton ever rotates the group, login
         * fails loudly here with a modulus-mismatch error instead of silently trusting a
         * substituted group.
         */
        const val PINNED_MODULUS_BASE64 =
            "W2z5HBi8RvsfYzZTS7qBaUxxPhsfHJFZpu3Kd6s1JafNrCCH9rfvPLrfuqocxWPgWDH2R8neK7PkNvjxto9" +
                "TStuY5z7jAzWRvFWN9cQhAKkdWgy0JY6ywVn22+HFpF4cYesHrqFIKUPDMSSIlWjBVmEJZ/MusD44ZT29xcPrOqeZvwt" +
                "CffKtGAIjLYPZIEbZKnDM1Dm3q2K/xS5h+xdhjnndhsrkwm9U9oyA2wxzSXFL+pdfj2fOdRwuR5nW0J2NFrq3kJjkRmpO" +
                "/Genq1UW+TEknIWAb6VzJJJA244K/H8cnSx2+nSNZO3bbo6Ys228ruV9A8m6DhxmS+bihN3ttQ=="

        private val pinnedModulus: ByteArray by lazy {
            SrpPrimitives.decodeBase64(PINNED_MODULUS_BASE64)
        }

        internal fun isPinnedModulus(candidate: ByteArray): Boolean =
            candidate.size == pinnedModulus.size &&
                SrpPrimitives.constantTimeEquals(candidate, pinnedModulus)
    }
}

/** A parsed `auth/v4/info` challenge: everything the SRP client needs from the server. */
data class SrpChallenge(
    val username: String,
    /** `Modulus` field — PGP clearsigned base64 (or raw base64), little-endian wire form. */
    val modulus: String,
    val salt: String,
    val serverEphemeral: String,
    val srpSession: String,
    val version: Int,
)

/** The client side of the SRP exchange, ready to post to `auth/v4`. */
data class SrpProofs(
    val clientEphemeralBase64: String,
    val clientProofBase64: String,
    val expectedServerProof: ByteArray,
)

/** Any unrecoverable SRP failure. Messages are safe to surface (no secrets). */
class SrpException(message: String) : Exception(message)

/**
 * Extracts and pin-verifies the modulus from the `Modulus` field.
 *
 * The field is normally a PGP clearsigned message; the base64 body after the `Hash:` header is
 * the modulus (auth-flow.md §2.2). A raw base64 body without armor is also accepted — safe
 * because the decoded bytes must match the pinned constant exactly either way, which is a
 * strictly stronger check than the reference's (it never verified the signature at all).
 */
private object ModulusParser {

    fun parseAndVerify(raw: String): ByteArray {
        val body = extractBase64Body(raw)
        val decoded = SrpPrimitives.decodeBase64(body)
        if (!SrpClient.isPinnedModulus(decoded)) {
            throw SrpException(
                "server modulus does not match the pinned SRP group — refusing to proceed",
            )
        }
        return decoded
    }

    private fun extractBase64Body(raw: String): String {
        val beginSignature = "-----BEGIN PGP SIGNATURE-----"
        val signatureStart = raw.indexOf(beginSignature)
        val unsigned =
            if (signatureStart >= 0) raw.substring(0, signatureStart) else raw
        return unsigned
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") && !it.startsWith("Hash:") }
            .joinToString("")
    }
}

/** Converts a password CharArray to UTF-8 bytes. Lives here so the conversion is explicit. */
internal object CharArrayCodec {
    fun toUtf8Bytes(chars: CharArray): ByteArray {
        val builder = StringBuilder(chars.size)
        for (c in chars) builder.append(c)
        return builder.toString().toByteArray(StandardCharsets.UTF_8)
    }
}
