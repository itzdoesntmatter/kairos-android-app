package digital.tonima.protonsync.crypto

import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.EventPart
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The inbound payload size caps of security-audit §3.5b (finding N-1), enforced at the
 * decryptor boundary. Each case is a T5 (hostile server) shape the audit named: an oversized
 * armored blob, an oversized plaintext/JSON part, an oversized signature, an oversized key
 * packet, and an event that is small per part but huge in aggregate. The contract is
 * reject-and-count, never crash: a placeholder with `payloadRejections >= 1`, no crypto call,
 * and a whole-event rejection (no partially assembled payload presented as the event).
 */
class PayloadCapTest {

    private val crypto = mockk<ProtonCrypto>(relaxed = true)
    private val keyring = mockk<ProtonCryptoKeyring>(relaxed = true)
    private val decryptor = EventPartDecryptor(crypto, keyring)

    @Before
    fun setUp() {
        // The OpenPGP primitive cannot run in a JVM test (the Go library is device-gated):
        // undecryptable input throws, exactly like the real implementation does for a payload
        // the ring cannot open. Size caps must reject BEFORE this is ever reached.
        every { crypto.decryptArmoredText(any(), any()) } throws CryptoDecryptException("cannot decrypt")
        every { crypto.decryptBinary(any(), any(), any()) } throws CryptoDecryptException("cannot decrypt")
    }

    private fun part(
        type: Int,
        data: String?,
        signature: String? = null,
    ) = EventPart(type = type, data = data, signature = signature)

    private fun detail(
        shared: List<EventPart> = emptyList(),
        calendar: List<EventPart> = emptyList(),
        calendarKeyPacket: String? = null,
    ) = CalendarEventDetail(
        id = "evt-1",
        startTime = 1_000L,
        endTime = 2_000L,
        sharedEvents = shared,
        calendarEvents = calendar,
        calendarKeyPacket = calendarKeyPacket,
    )

    private fun paddedIcal(totalChars: Int): String {
        val head = "DTSTART:20261016T090000Z\r\nSUMMARY:"
        require(totalChars > head.length) { "budget too small for the fixture" }
        return head + "x".repeat(totalChars - head.length)
    }

    @Test
    fun `a normal event assembles and counts nothing`() = runTest {
        val outcome = decryptor.decrypt(detail(shared = listOf(part(0, paddedIcal(200)))))

        assertFalse(outcome.isPlaceholder)
        assertEquals(0, outcome.payloadRejections)
    }

    @Test
    fun `a part at exactly the per-part budget is still accepted`() = runTest {
        val outcome =
            decryptor.decrypt(
                detail(shared = listOf(part(0, paddedIcal(PayloadLimits.MAX_PART_CHARS)))),
            )

        assertFalse(outcome.isPlaceholder)
        assertEquals(0, outcome.payloadRejections)
    }

    @Test
    fun `a plaintext part over the 256 KB JSON budget rejects the event before decryption`() = runTest {
        val outcome =
            decryptor.decrypt(
                detail(
                    shared =
                        listOf(
                            part(0, paddedIcal(PayloadLimits.MAX_PART_CHARS + 1)),
                            part(2, "SUMMARY:legit"),
                        ),
                ),
            )

        assertTrue(outcome.isPlaceholder)
        assertNull(outcome.icalText)
        assertEquals(1, outcome.payloadRejections)
        assertEquals(0, outcome.decryptFailures)
        verify(exactly = 0) { crypto.decryptArmoredText(any(), any()) }
        verify(exactly = 0) { crypto.decryptBinary(any(), any(), any()) }
    }

    @Test
    fun `an armored part over 1 MB is rejected`() = runTest {
        val armored = "-----BEGIN PGP MESSAGE-----\n" + "A".repeat(PayloadLimits.MAX_ARMORED_CHARS)

        val outcome = decryptor.decrypt(detail(shared = listOf(part(1, armored))))

        assertTrue(outcome.isPlaceholder)
        assertEquals(1, outcome.payloadRejections)
        verify(exactly = 0) { crypto.decryptArmoredText(any(), any()) }
    }

    @Test
    fun `an armored part under 1 MB but over 256 KB is accepted by the per-part rule`() = runTest {
        // 300 KB of armored text: inside the 1 MB armored cap, and the event carries nothing
        // else, so the aggregate budget is not the binding rule here.
        val armored = "-----BEGIN PGP MESSAGE-----\n" + "A".repeat(300_000)

        val outcome = decryptor.decrypt(detail(shared = listOf(part(3, armored))))

        // The fake crypto cannot decrypt it, so the part fails to decrypt — but it was NOT
        // rejected by a size cap: decryptFailures counts it, payloadRejections does not.
        assertEquals(0, outcome.payloadRejections)
        assertEquals(1, outcome.decryptFailures)
    }

    @Test
    fun `an oversized detached signature is rejected`() = runTest {
        val outcome =
            decryptor.decrypt(
                detail(
                    shared =
                        listOf(
                            part(
                                type = 2,
                                data = "DTSTART:20261016T090000Z",
                                signature = "S".repeat(PayloadLimits.MAX_SIGNATURE_CHARS + 1),
                            ),
                        ),
                ),
            )

        assertTrue(outcome.isPlaceholder)
        assertEquals(1, outcome.payloadRejections)
        verify(exactly = 0) { crypto.verifyDetached(any(), any(), any()) }
    }

    @Test
    fun `an oversized key packet is rejected`() = runTest {
        val outcome =
            decryptor.decrypt(
                detail(
                    shared = listOf(part(1, "QUJD")),
                    calendarKeyPacket = "K".repeat(PayloadLimits.MAX_KEY_PACKET_CHARS + 1),
                ),
            )

        assertTrue(outcome.isPlaceholder)
        assertEquals(1, outcome.payloadRejections)
        verify(exactly = 0) { crypto.decryptBinary(any(), any(), any()) }
    }

    @Test
    fun `many small parts that exceed the aggregate budget reject the event`() = runTest {
        // 6 × 200 KB = 1.2 MB: every part is under the per-part cap, the sum is not.
        val parts = (1..6).map { part(0, paddedIcal(200_000)) }

        val outcome = decryptor.decrypt(detail(shared = parts))

        assertTrue(outcome.isPlaceholder)
        assertEquals(1, outcome.payloadRejections)
    }

    @Test
    fun `decrypted output over the assembled budget is dropped whole`() = runTest {
        // Two parts of 200 KB each stay inside both per-part and aggregate caps, but the
        // assembled text would be 400 KB — past the 256 KB parser budget (the decompression-
        // bomb shape: small inputs, large assembled output).
        val outcome =
            decryptor.decrypt(
                detail(
                    shared = listOf(part(0, paddedIcal(200_000))),
                    calendar = listOf(part(0, "SUMMARY:" + "y".repeat(199_990))),
                ),
            )

        assertTrue(outcome.isPlaceholder)
        assertNull(outcome.icalText)
        assertEquals(1, outcome.payloadRejections)
    }
}
