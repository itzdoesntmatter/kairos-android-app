package digital.tonima.protonsync.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class SrpPrimitivesTest {

    @Test
    fun `expandHash is four SHA-512 digests with zero-based counters — 256 bytes`() {
        val out = SrpPrimitives.expandHash("abc".toByteArray())

        assertEquals(256, out.size)
        // Independently computed (python hashlib, sha512(data||i) for i in 0..3) — the
        // zero-based counter is protocol behaviour, not an implementation choice.
        val expected =
            "7ce05eda233e545a2d5c626862a5ddaafb09b9d8ec3bec08aa458b7c9e7d939d84a57d5a20d8a9002983aabae2457b19c5" +
                "0ba326bf5b081f75b41342f42c3383318968ca3cabc684b5a6edfda19384b502f841444e71d7aebbabee363b6c0c940ff6fe" +
                "4f1c01771d4c1c298949c769f73c4d072d81a96016cebbd8b66c728108c6976814861f5067f0adac294be83cba85318307dc" +
                "e9e116ffe37718642866ab9380c2f677f961a020790c4e53ad4bafc0f67b93c541dfc782ab8bd37e388b6b4a8b1a1dad94a" +
                "c119849921ac748b0a36a0a52e3024b004e140bad0b5271de93362baa3f124fa43789a2ada54098c93bc36de7559a30a16b" +
                "e0ad090f44c97e8e"
        assertEquals(expected, out.toHex())
    }

    @Test
    fun `little-endian serialization round-trips through the wire format`() {
        val value = BigInteger("12345678901234567890123456789012345678901234567890")

        val wire = SrpPrimitives.toFixedLittleEndian(value)

        assertEquals(256, wire.size)
        assertEquals(value, SrpPrimitives.fromLittleEndian(wire))
    }

    @Test
    fun `generator serializes to 256 bytes little-endian with a single leading byte`() {
        val wire = SrpPrimitives.toFixedLittleEndian(BigInteger.valueOf(2))

        assertEquals(256, wire.size)
        assertEquals(2.toByte(), wire[0])
        assertTrue(wire.drop(1).all { it == 0.toByte() })
    }

    @Test
    fun `negative values cannot be serialized`() {
        assertThrows(IllegalArgumentException::class.java) {
            SrpPrimitives.toFixedLittleEndian(BigInteger.valueOf(-1))
        }
    }

    @Test
    fun `fromLittleEndian rejects non-group-width input`() {
        assertThrows(IllegalArgumentException::class.java) {
            SrpPrimitives.fromLittleEndian(ByteArray(10))
        }
    }

    @Test
    fun `bcrypt base64 uses the dot-slash alphabet without padding`() {
        // Independent python computation of the 16 zero bytes and 0x00..0x0f vectors.
        assertEquals("......................", SrpPrimitives.bcryptBase64Encode(ByteArray(16)))
        assertEquals(
            "..CA.uOD/eaGAOmJB.yMBu",
            SrpPrimitives.bcryptBase64Encode(ByteArray(16) { it.toByte() }),
        )
    }

    @Test
    fun `constant-time equals compares contents`() {
        val a = byteArrayOf(1, 2, 3)
        assertTrue(SrpPrimitives.constantTimeEquals(a, byteArrayOf(1, 2, 3)))
        assertFalse(SrpPrimitives.constantTimeEquals(a, byteArrayOf(1, 2, 4)))
        assertFalse(SrpPrimitives.constantTimeEquals(a, byteArrayOf(1, 2)))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
