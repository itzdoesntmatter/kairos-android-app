package digital.tonima.protonsync.auth

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * Byte-level primitives of Proton's SRP-6a variant. Every rule here is byte-exact protocol
 * behaviour, validated against the published test vectors of ProtonMail/go-srp (MIT,
 * https://github.com/ProtonMail/go-srp, `srp_test.go`) — the same vectors this module's unit
 * tests reproduce end to end. Sources for each non-obvious detail are cited per function.
 *
 * Cleanroom: this file implements the algorithm from the RE behaviour spec
 * (reverse-engineering/sync-for-proton/specs/auth-flow.md §2.3) and the public go-srp source;
 * nothing is transcribed from decompiled application code.
 */
internal object SrpPrimitives {

    /** Width of every fixed-width SRP value in bytes (2048-bit group → 256 bytes). */
    const val GROUP_BYTES = 256

    /** Generator of the SRP group (Proton pins g = 2, auth-flow.md §2.3 step 6). */
    val GENERATOR = BigInteger.valueOf(2)

    /**
     * `expandHash`: four SHA-512 digests over `data || counter` for counter in 0..3,
     * concatenated — exactly 256 bytes. The zero-based counter is the observed wire behaviour
     * (go-srp `hash.go` `expandHash`, counters 0,1,2,3); a one-based variant would break the
     * client proof and is what the published vectors pin down.
     */
    fun expandHash(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        val out = ByteArray(GROUP_BYTES)
        for (i in 0 until 4) {
            digest.reset()
            digest.update(data)
            digest.update(i.toByte())
            val part = digest.digest()
            part.copyInto(out, destinationOffset = i * part.size)
        }
        return out
    }

    /**
     * Fixed-width 256-byte little-endian serialization (`fromInt` in go-srp): the value's
     * minimal big-endian magnitude is byte-reversed and left-padded with zeroes to 256 bytes.
     * Values of 2048 bits fit exactly; a larger value is a caller bug (asserted).
     */
    fun toFixedLittleEndian(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "negative SRP value cannot be serialized" }
        val magnitude = value.toByteArray().let {
            // BigInteger.toByteArray prefixes a 0x00 sign byte when the top bit is set.
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        require(magnitude.size <= GROUP_BYTES) { "SRP value exceeds group width" }
        val out = ByteArray(GROUP_BYTES)
        for (i in magnitude.indices) {
            out[i] = magnitude[magnitude.size - 1 - i]
        }
        return out
    }

    /**
     * Reads a little-endian byte string as an unsigned [BigInteger] (`toInt` in go-srp):
     * the input is byte-reversed and parsed big-endian, so wire-format LE values become the
     * canonical integers the group math operates on.
     */
    fun fromLittleEndian(bytes: ByteArray): BigInteger {
        require(bytes.size == GROUP_BYTES) { "SRP value must be exactly $GROUP_BYTES bytes" }
        return BigInteger(1, bytes.reversedArray())
    }

    /** Base64 without padding/line breaks — the wire encoding of ClientEphemeral/ClientProof. */
    fun base64NoWrap(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Inverse of [base64NoWrap]. */
    fun decodeBase64(text: String): ByteArray = Base64.getDecoder().decode(text)

    /**
     * bcrypt's custom base64 alphabet — `./0-9A-Za-z`, no padding (auth-flow.md §2.3; the
     * alphabet is defined by the bcrypt specification itself). Encodes 16 raw bytes to the
     * canonical 22-character bcrypt salt string.
     */
    fun bcryptBase64Encode(bytes: ByteArray): String {
        val alphabet = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                out.append(alphabet[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer shl (6 - bits)) and 0x3F])
        }
        return out.toString()
    }

    /**
     * Constant-time equality for the server-proof check. `MessageDigest.isEqual` is mandated
     * by the security audit (§2.1.4): a timing-variable compare here would leak the SRP
     * session to a MITM.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
