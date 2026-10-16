package digital.tonima.protonsync.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Byte-exact validation of the SRP-6a implementation against the published test vectors of
 * ProtonMail/go-srp (MIT, `srp_test.go`, `TestSRPauth`):
 *
 * ```
 * NewAuth(4, "jakubqa", []byte("abc123"), "yKlc5/CvObfoiw==", testModulusClearSign, testServerEphemeral)
 * proofs := GenerateProofs(2048)   // with a deterministic PRNG client secret
 * ```
 *
 * The go-srp vectors pin the whole chain — bcrypt salt handling, `$2y$` rewrite, expandHash
 * (zero-based counters), little-endian group math, client proof and expected server proof —
 * so a green run here means the client reproduces Proton's SRP byte-for-byte. The client
 * secret is injected through a deterministic [SecureRandom] returning the same 256 bytes the
 * vector used (in the wire-little-endian form this implementation reads).
 */
class SrpClientTest {

    // The vector's client secret, little-endian 256 bytes (go-srp `fromInt(2048, secretInt)`).
    private val vectorSecretHex =
        "b19256d09afdcac0ba7489ff27ed6f6b57e3a83f0216c1d40e4144f3fd47894961108e6517386e052b8e822623c9053f27" +
            "5e7029e1b2745b5ac22ddca296e771701c1ca100da07b62c695f882dcdda00e18728100cbefdfe12575b49e0c37baef3c4" +
            "c6e30ced8a97160662785a4643aa3c903ced428a1051a7821b88aeaf0b1b8ec7a2cf79557c87a22ece04ac1add13ac8f6d5" +
            "4eaeb1cfe4f8c3ed6e83e84badc4ce36fbd4b9ee7818e744ee5826f9eda8fe12919f9f41db402bc75d3613f0858079b70e0" +
            "cb1a843585884c14d6c00c5ba53b0d9f9f0c7a619b42764d9bd7df529ddd09ba923e34d9c90952f284145b9fe872b44b9f" +
            "bb971bbf64b1967f8c53"

    private val vectorSalt = "yKlc5/CvObfoiw=="
    private val vectorPassword = "abc123"
    private val vectorUsername = "jakubqa"
    private val vectorServerEphemeral =
        "l13IQSVFBEV0ZZREuRQ4ZgP6OpGiIfIjbSDYQG3Yp39FkT2B/k3n1ZhwqrAdy+qvPPFq/le0b7UDtayoX4aOTJihoRvi" +
            "fas8Hr3icd9nAHqd0TUBbkZkT6Iy6UpzmirCXQtEhvGQIdOLuwvy+vZWh24G2ahBM75dAqwkP961EJMh67/I5PA5hJdQZj" +
            "dPT5luCyVa7BS1d9ZdmuR0/VCjUOdJbYjgtIH7BQoZs+KacjhUN8gybu+fsycvTK3eC+9mCN2Y6GdsuCMuR3pFB0RF9eKa" +
            "e7cA6RbJfF1bjm0nNfWLXzgKguKBOeF3GEAsnCgK68q82/pq9etiUDizUlUBcA=="

    /** Wire-little-endian modulus (the pinned group), base64. */
    private val vectorModulusBody = SrpClient.PINNED_MODULUS_BASE64

    private val vectorModulusClearSign =
        "-----BEGIN PGP SIGNED MESSAGE-----\n" +
            "Hash: SHA256\n" +
            "\n" +
            "$vectorModulusBody\n" +
            "-----BEGIN PGP SIGNATURE-----\n" +
            "Version: ProtonMail\n" +
            "Comment: https://protonmail.com\n" +
            "\n" +
            "wl4EARYIABAFAlwB1j0JEDUFhcTpUY8mAAD8CgEAnsFnF4cF0uSHKkXa1GIa\n" +
            "GO86yMV4zDZEZcDSJo0fgr8A/AlupGN9EdHlsrZLmTA1vhIx+rOgxdEff28N\n" +
            "kvNM7qIK\n" +
            "=q6vu\n" +
            "-----END PGP SIGNATURE-----"

    // Expected outputs from the vector (client ephemeral as hex, proofs as base64).
    private val expectedClientEphemeralHex =
        "98af9e48ca2c7d93bf0ace6cfaf71b8e9b0dec5f1400e6f094a2a70b2ff3f45a683113363df4dee72c0b0607df98b61a6a93" +
            "eaed73b16aa6a3d3c91c64b1dc3357c80362506594ca3e7112375a80433b49dff607308d28a7ac81833c7df34dce8c8ce78c2" +
            "0f60775c148c387206e281b4530a1cb085f7da188c9d894c324b7c346ae2b723e59e611bc694676b77eef4b119fafd2ea3a3e3" +
            "282aceefef44dee68c8bae0769d5c17f2c76ada04517003eca1ec5bc4ed4fa197700972512b7b877ca667e131dc57cded6ca092" +
            "3c7ccbaa69ad3bad8d2bd2b17998b2d3baa4c8191164535a86f65a2af7e20cb12c2092dd4fde0856eb724aed8e8b59d0f2d1de9c02"

    private val expectedClientProof =
        "Qb+1+jEqHRqpJ3nEJX2FEj0kXgCIWHngO0eT4R2Idkwke/ceCIUmQa0RfTYU53ybO1AVergtb7N0W/3bathdHT9FAHhy0vD" +
            "GQDg/yPnuUneqV76NuU+pQHnO83gcjmZjDq/zvRRSD7dtIORRK97xhdR9W9bG5XRGr2c9Zev40YVcXgUiNUG/0zHSKQfEhUp" +
            "MKxdauKtGC+dZnZzU6xaU0qvulYEsraawurRf0b1VXwohM6KE52Fj5xlS2FWZ3Mg0WIOC5KW5ziI6QirEUDK2pH/Rxvu4HcW" +
            "9aMuppUmHk9Bm6kdg99o3vl0G7OgmEI7y6iyEYmXqH44XGORJ2sDMxQ=="

    private val expectedServerProof =
        "SLCSIClioSAtozauZZzcJuVPyY+MjnxfJSgEe9y6RafgjlPqnhQTZclRKPGsEhxVyWan7PIzhL+frPyZNaE1QaV5zbqz1yf9" +
            "RXpGyTjZwU3FuVCJpkhp6iiCK3Wd2SemxawFXC06dgAdJ7I3HKvfkXeMANOUUh5ofjnJtXg42OGp4x1lKoFcH+IbB/CvRNQ" +
            "CmRTyhOiBJmZyUFwxHXLT/h+PlD0XSehcyybIIBIsscQ7ZPVPxQw4BqlqoYzTjjXPJxLxeQUQm2g9bPzT+izuR0VOPDtjt+d" +
            "XrWny90k2nzS0Bs2YvNIqbJn1aQwFZr42p/O1I9n5S3mYtMgGk/7b1g=="

    private fun clientWithFixedSecret(): SrpClient = SrpClient(FixedSecretSecureRandom())

    private fun vectorChallenge(modulus: String = vectorModulusClearSign) =
        SrpChallenge(
            username = vectorUsername,
            modulus = modulus,
            salt = vectorSalt,
            serverEphemeral = vectorServerEphemeral,
            srpSession = "session-id",
            version = 4,
        )

    @Test
    fun `go-srp published vector — client ephemeral, client proof and server proof match byte-for-byte`() {
        val client = clientWithFixedSecret()

        val proofs = client.generateProofs(vectorChallenge(), vectorPassword.toCharArray())

        assertEquals(expectedClientEphemeralHex, SrpPrimitives.decodeBase64(proofs.clientEphemeralBase64).toHex())
        assertEquals(expectedClientProof, proofs.clientProofBase64)
        assertEquals(expectedServerProof, SrpPrimitives.base64NoWrap(proofs.expectedServerProof))
    }

    @Test
    fun `accepts the pinned modulus without armor — raw base64 body`() {
        val client = clientWithFixedSecret()

        val proofs = client.generateProofs(
            vectorChallenge(modulus = vectorModulusBody),
            vectorPassword.toCharArray(),
        )

        assertEquals(expectedClientProof, proofs.clientProofBase64)
    }

    @Test
    fun `rejects a modulus that differs from the pinned group`() {
        val client = clientWithFixedSecret()
        val otherModulus = "AB" + vectorModulusBody.substring(2)

        val e = assertThrows(SrpException::class.java) {
            client.generateProofs(vectorChallenge(modulus = otherModulus), vectorPassword.toCharArray())
        }
        assertTrue(e.message!!.contains("pinned"))
    }

    @Test
    fun `rejects a degenerate server ephemeral of zero`() {
        val client = clientWithFixedSecret()
        val zeroEphemeral = SrpPrimitives.base64NoWrap(ByteArray(SrpPrimitives.GROUP_BYTES))

        val e = assertThrows(SrpException::class.java) {
            client.generateProofs(
                vectorChallenge().copy(serverEphemeral = zeroEphemeral),
                vectorPassword.toCharArray(),
            )
        }
        assertTrue(e.message!!.contains("degenerate"))
    }

    @Test
    fun `rejects a server ephemeral at or above the modulus`() {
        val client = clientWithFixedSecret()
        val bEqualsN = vectorModulusBody // B == N, out of the valid range (1, N-1)

        val e = assertThrows(SrpException::class.java) {
            client.generateProofs(
                vectorChallenge().copy(serverEphemeral = bEqualsN),
                vectorPassword.toCharArray(),
            )
        }
        assertTrue(e.message!!.contains("degenerate"))
    }

    @Test
    fun `server proof verification is exact and rejects any tampering`() {
        val client = clientWithFixedSecret()
        val proofs = client.generateProofs(vectorChallenge(), vectorPassword.toCharArray())

        assertTrue(client.verifyServerProof(expectedServerProof, proofs.expectedServerProof))

        val flipped = proofs.expectedServerProof.copyOf()
        flipped[0] = (flipped[0].toInt() xor 1).toByte()
        assertFalse(client.verifyServerProof(expectedServerProof, flipped))
        assertFalse(client.verifyServerProof("AAAA", proofs.expectedServerProof))
    }

    @Test
    fun `pinned modulus constant decodes to a 256-byte group`() {
        val decoded = SrpPrimitives.decodeBase64(SrpClient.PINNED_MODULUS_BASE64)
        assertEquals(SrpPrimitives.GROUP_BYTES, decoded.size)
    }

    /** Returns the vector's fixed 256-byte client secret on every read. */
    private inner class FixedSecretSecureRandom : SecureRandom() {
        private val secret = vectorSecretHex.decodeHex()
        override fun nextBytes(bytes: ByteArray) {
            secret.copyInto(bytes, endIndex = minOf(secret.size, bytes.size))
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.decodeHex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
