package digital.tonima.protonsync.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BcryptPasswordHasherTest {

    private val hasher = BcryptPasswordHasher()

    @Test
    fun `hash is the canonical 60-byte 2y string at cost 10`() {
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        val hash = hasher.hashPassword("password".toByteArray(), salt)

        assertEquals(60, hash.size)
        val asString = String(hash, Charsets.US_ASCII)
        assertTrue(asString.startsWith("\$2y\$10\$"))
    }

    @Test
    fun `salt below 16 bytes is padded with the proton suffix`() {
        // A 10-byte salt (the wire shape) is padded with "proton" to 16 bytes; the same
        // salt already containing the suffix must produce the identical hash.
        val wireSalt = "0123456789".toByteArray()
        val paddedSalt = ("0123456789" + "proton").toByteArray()

        assertEquals(
            String(hasher.hashPassword("pw".toByteArray(), wireSalt), Charsets.US_ASCII),
            String(hasher.hashPassword("pw".toByteArray(), paddedSalt), Charsets.US_ASCII),
        )
    }

    @Test
    fun `same password and salt always hash identically`() {
        val salt = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9)
        val a = hasher.hashPassword("abc123".toByteArray(), salt)
        val b = hasher.hashPassword("abc123".toByteArray(), salt)
        assertEquals(String(a, Charsets.US_ASCII), String(b, Charsets.US_ASCII))
    }
}
