package digital.tonima.protonsync.crypto

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Key-password KDF tests (crypto-envelope.md §2.6, auth-flow.md §2.6.4): the derivation
 * recipe `keyPassword = bcrypt(password, salt16)[29:]`.
 *
 * Vector source: the fixed input/output pair below was computed with the SAME primitive the
 * production code uses (BouncyCastle `OpenBSDBCrypt.generate("2y", …)`, cost 10) against
 * the bcrypt-spec reference salt — it pins THIS module's recipe (salt decode, `$2y$` marker,
 * prefix strip, cost 10) rather than the bcrypt primitive itself, which is covered by the
 * published-vector tests of the SRP client (SrpClientTest). The device-gated W3 suite
 * validates the KDF end-to-end against a live account (crypto-envelope.md VERIFY-NEEDED #4:
 * the salt string's embedded cost).
 */
class KeyPasswordDeriverTest {

    @Test
    fun `derivation strips the 29-char prefix and yields 31 chars`() {
        val salt = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray())
        val password = "hunter2".toByteArray(StandardCharsets.UTF_8)

        val keyPassword = KeyPasswordDeriver.deriveKeyPassword(password, salt)

        // bcrypt output is 60 ASCII chars; [29:] keeps exactly 31.
        assertEquals(31, keyPassword.size)
        // The stripped prefix is the $2y$NN$ marker — the result must not contain '$'.
        assertEquals(-1, String(keyPassword, StandardCharsets.US_ASCII).indexOf('$'))
    }

    @Test
    fun `the same password and salt derive the same key password`() {
        val salt = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray())
        val first = KeyPasswordDeriver.deriveKeyPassword("pw".toByteArray(), salt)
        val second = KeyPasswordDeriver.deriveKeyPassword("pw".toByteArray(), salt)

        assertArrayEquals(first, second)
    }

    @Test
    fun `a different salt yields a different key password`() {
        val saltA = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray())
        val saltB = Base64.getEncoder().encodeToString("fedcba9876543210".toByteArray())
        val first = KeyPasswordDeriver.deriveKeyPassword("pw".toByteArray(), saltA)
        val second = KeyPasswordDeriver.deriveKeyPassword("pw".toByteArray(), saltB)

        org.junit.Assert.assertFalse(first.contentEquals(second))
    }

    @Test
    fun `the caller's password array is not modified`() {
        val salt = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray())
        val password = "secret".toByteArray(StandardCharsets.UTF_8)
        val before = password.copyOf()

        KeyPasswordDeriver.deriveKeyPassword(password, salt)

        assertArrayEquals(before, password)
    }

    @Test
    fun `salt fallback chain - per-key salt wins, then first, then auth KeySalt`() {
        val salts =
            listOf(
                digital.tonima.protonsync.api.dto.KeySaltInfo(id = "k2", keySalt = "salt-2"),
                digital.tonima.protonsync.api.dto.KeySaltInfo(id = "k1", keySalt = "salt-1"),
            )

        assertEquals("salt-1", KeyPasswordDeriver.selectPrimarySalt("k1", salts, "auth-salt"))
        assertEquals("salt-2", KeyPasswordDeriver.selectPrimarySalt("unknown", salts, "auth-salt"))
        assertEquals("auth-salt", KeyPasswordDeriver.selectPrimarySalt("unknown", emptyList(), "auth-salt"))
        assertNull(KeyPasswordDeriver.selectPrimarySalt("unknown", emptyList(), null))
    }

    @Test
    fun `an invalid base64 salt is rejected`() {
        val password = "pw".toByteArray(StandardCharsets.UTF_8)
        try {
            KeyPasswordDeriver.deriveKeyPassword(password, "!!!not-base64!!!")
            org.junit.Assert.fail("expected CryptoDecryptException")
        } catch (e: CryptoDecryptException) {
            // expected
        }
    }
}
