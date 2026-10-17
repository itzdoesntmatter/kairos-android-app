package digital.tonima.protonsync.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Gap-fill for [SrpClientTest] (specs/test-plan.md AUTH-07 / AUTH-08): the degenerate-input and
 * malformed-wire rejection paths of security-audit.md §2.1.2 (reject before any math; bounded
 * retry) and the server-proof mismatch paths of security-audit.md §2.1.4. The byte-exact happy
 * path against the go-srp vectors stays in [SrpClientTest] and is not duplicated here.
 *
 * Coverage note on `A == 0` / `A % N == 0` (security-audit §2.1.2, first clause): with the pinned
 * group (g = 2, N prime) `A = 2^a mod N` is a unit of Z_N\* and can never be 0, so the
 * `clientEphemeral.signum() == 0` guard in [SrpClient.generateProofs] is unreachable by
 * construction — it is defensive. The reachable retry path is `S == 0`, driven by a server
 * ephemeral congruent to `k·g^x (mod N)`, and that path is covered below (including the
 * 1000-attempt bound). This is reported in the LOD-12 coverage matrix rather than asserted.
 */
class SrpClientGapTest {

    // go-srp published vector inputs (same constants as SrpClientTest — public protocol material).
    private val vectorSalt = "yKlc5/CvObfoiw=="
    private val vectorPassword = "abc123"
    private val vectorUsername = "jakubqa"
    private val vectorModulusBody = TestFixtures.TEST_MODULUS_BODY
    private val vectorServerEphemeral =
        "l13IQSVFBEV0ZZREuRQ4ZgP6OpGiIfIjbSDYQG3Yp39FkT2B/k3n1ZhwqrAdy+qvPPFq/le0b7UDtayoX4aOTJihoRvi" +
            "fas8Hr3icd9nAHqd0TUBbkZkT6Iy6UpzmirCXQtEhvGQIdOLuwvy+vZWh24G2ahBM75dAqwkP961EJMh67/I5PA5hJdQZj" +
            "dPT5luCyVa7BS1d9ZdmuR0/VCjUOdJbYjgtIH7BQoZs+KacjhUN8gybu+fsycvTK3eC+9mCN2Y6GdsuCMuR3pFB0RF9eKa" +
            "e7cA6RbJfF1bjm0nNfWLXzgKguKBOeF3GEAsnCgK68q82/pq9etiUDizUlUBcA=="

    private fun challenge(
        modulus: String = TestFixtures.testClearsignedModulus(),
        serverEphemeral: String = vectorServerEphemeral,
    ) = SrpChallenge(
        username = vectorUsername,
        modulus = modulus,
        salt = vectorSalt,
        serverEphemeral = serverEphemeral,
        srpSession = "session-id",
        version = 4,
    )

    @Test
    fun `a server ephemeral above the modulus is refused - all-0xFF overshoot`() {
        // B must be in (0, N): the all-ones value is strictly greater than the pinned modulus
        // (its top byte is 0x5b), so it is a degenerate group element (security-audit §2.1.2).
        val overshoot = SrpPrimitives.base64NoWrap(ByteArray(SrpPrimitives.GROUP_BYTES) { 0xFF.toByte() })

        val error = assertThrows(SrpException::class.java) {
            SrpClient().generateProofs(challenge(serverEphemeral = overshoot), vectorPassword.toCharArray())
        }

        assertTrue(error.message!!.contains("degenerate"))
    }

    @Test
    fun `a server ephemeral of the wrong width is refused before any math`() {
        val short = SrpPrimitives.base64NoWrap(ByteArray(SrpPrimitives.GROUP_BYTES - 1))

        val error = assertThrows(IllegalArgumentException::class.java) {
            SrpClient().generateProofs(challenge(serverEphemeral = short), vectorPassword.toCharArray())
        }

        assertTrue(error.message!!.contains("256"))
    }

    @Test
    fun `an empty modulus body is refused before any math`() {
        val error = assertThrows(SrpException::class.java) {
            SrpClient().generateProofs(challenge(modulus = ""), vectorPassword.toCharArray())
        }

        assertTrue(error.message!!.contains("clearsigned"))
    }

    @Test
    fun `a modulus that is not base64 surfaces the decoder failure - malformed wire`() {
        // FINDING F-1 (LOD-12), updated: since the modulus moved to signature verification,
        // a non-clearsigned `Modulus` field is now rejected up front as SrpException("modulus
        // is not a clearsigned message") — a cleaner failure mode than the raw
        // java.util.Base64 IllegalArgumentException this test originally documented.
        val error = assertThrows(SrpException::class.java) {
            SrpClient().generateProofs(challenge(modulus = "not base64 !!!"), vectorPassword.toCharArray())
        }
        assertTrue(error.message!!.contains("clearsigned"))
    }

    @Test
    fun `an empty password is refused before any SRP math`() {
        assertThrows(IllegalArgumentException::class.java) {
            SrpClient().generateProofs(challenge(), CharArray(0))
        }
    }

    @Test
    fun `a degenerate shared secret is retried and the retry loop is bounded`() {
        // B ≡ k·g^x (mod N) makes base = B − k·g^x ≡ 0, so S = (base)^e = 0 for EVERY client
        // secret: the retry loop can never succeed and must terminate loudly instead of
        // spinning (auth-flow.md §2.3 step 9; security-audit §2.1.2 "bounded"). The fixed
        // secret keeps each iteration cheap (a = 1), so the bound is exercised for real.
        val modulusBytes = SrpPrimitives.decodeBase64(TestFixtures.TEST_MODULUS_BODY)
        val n = SrpPrimitives.fromLittleEndian(modulusBytes)
        val hashed = BcryptPasswordHasher()
            .hashPassword(vectorPassword.toByteArray(Charsets.US_ASCII), SrpPrimitives.decodeBase64(vectorSalt))
        val x = SrpPrimitives.fromLittleEndian(SrpPrimitives.expandHash(hashed + modulusBytes))
        val k = SrpPrimitives.fromLittleEndian(
            SrpPrimitives.expandHash(SrpPrimitives.toFixedLittleEndian(SrpPrimitives.GENERATOR) + modulusBytes),
        ).mod(n)
        val degenerateEphemeral = SrpPrimitives.base64NoWrap(
            SrpPrimitives.toFixedLittleEndian(
                k.multiply(SrpPrimitives.GENERATOR.modPow(x, n)).mod(n),
            ),
        )

        val error = assertThrows(SrpException::class.java) {
            SrpClient(FixedValueSecureRandom(1))
                .generateProofs(challenge(serverEphemeral = degenerateEphemeral), vectorPassword.toCharArray())
        }

        assertTrue(error.message!!.contains("usable SRP session"))
        assertTrue(error.message!!.contains(SrpClient.MAX_SECRET_ATTEMPTS.toString()))
    }

    @Test
    fun `the same fixed secret produces proofs against a non-degenerate server ephemeral`() {
        // Control for the bounded-retry test: the fixed secret itself is valid (A != 0), so a
        // normal server ephemeral must yield proofs — the loop only repeats on S == 0.
        val proofs = SrpClient(FixedValueSecureRandom(1))
            .generateProofs(challenge(), vectorPassword.toCharArray())

        assertEquals(SrpPrimitives.GROUP_BYTES, SrpPrimitives.decodeBase64(proofs.clientEphemeralBase64).size)
        assertEquals(SrpPrimitives.GROUP_BYTES, proofs.expectedServerProof.size)
    }

    @Test
    fun `server proof verification rejects wrong-length and empty proofs`() {
        val client = SrpClient()
        val expected = ByteArray(SrpPrimitives.GROUP_BYTES) { (it % 251).toByte() }

        assertTrue(client.verifyServerProof(SrpPrimitives.base64NoWrap(expected), expected))
        assertFalse(client.verifyServerProof("", expected))
        assertFalse(
            client.verifyServerProof(SrpPrimitives.base64NoWrap(ByteArray(SrpPrimitives.GROUP_BYTES + 1)), expected),
        )
        val flipped = expected.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertFalse(client.verifyServerProof(SrpPrimitives.base64NoWrap(flipped), expected))
    }

    @Test
    fun `a non-base64 server proof surfaces the decoder failure - malformed wire`() {
        // Same failure-type caveat as FINDING F-1: the mismatch is correctly refused, but a
        // non-base64 proof exits as IllegalArgumentException rather than "false".
        assertThrows(IllegalArgumentException::class.java) {
            SrpClient().verifyServerProof("!!!", ByteArray(SrpPrimitives.GROUP_BYTES))
        }
    }

    /** Returns the same little-endian integer [value] on every [nextBytes] call. */
    private class FixedValueSecureRandom(private val value: Byte) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0)
            bytes[0] = value
        }
    }
}
