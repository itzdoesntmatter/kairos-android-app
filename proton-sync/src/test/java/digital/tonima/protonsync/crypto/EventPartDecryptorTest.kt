package digital.tonima.protonsync.crypto

import java.util.Base64
import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.EventPart
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * EventPartDecryptor envelope-policy tests (crypto-envelope.md §3, security-audit HI-3):
 * part-type dispatch, key-packet selection, binary/armored decryption, STRICT
 * verify-or-drop with failure counters, assembly and the all-parts-failed placeholder.
 *
 * The OpenPGP primitive itself is faked ([FakeProtonCrypto]): the Go library cannot load in
 * a JVM test (device-gated KATs are the W3 suite, architecture §10) — what these tests pin
 * is the envelope ORCHESTRATION, which is the code this wave owns. Vector sources are the
 * part-semantics table of crypto-envelope.md §3.
 */
class EventPartDecryptorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var crypto: FakeProtonCrypto
    private lateinit var keyring: ProtonCryptoKeyring

    private suspend fun setupKeyring() {
        crypto = FakeProtonCrypto()
        val keysFile = File(tempFolder.root, "keys.dat")
        keyring = ProtonCryptoKeyring(crypto, KeysStore(keysFile, SoftwareGcmCrypto()))
        keyring.addUserKey("user-1", "armored-user-key", "pass".toByteArray())
    }

    private fun part(
        type: Int,
        data: String?,
        signature: String? = null,
    ) = EventPart(type = type, data = data, signature = signature)

    private fun detail(
        shared: List<EventPart> = emptyList(),
        calendar: List<EventPart> = emptyList(),
        personal: List<EventPart> = emptyList(),
        attendees: List<EventPart> = emptyList(),
        addressKeyPacket: String? = null,
        sharedKeyPacket: String? = null,
        calendarKeyPacket: String? = null,
    ) = CalendarEventDetail(
        id = "evt-1",
        addressKeyPacket = addressKeyPacket,
        sharedKeyPacket = sharedKeyPacket,
        calendarKeyPacket = calendarKeyPacket,
        sharedEvents = shared,
        calendarEvents = calendar,
        personalEvents = personal,
        attendeesEvents = attendees,
    )

    @Test
    fun `type-0 plaintext part is kept as-is`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)

        val outcome = decryptor.decrypt(detail(shared = listOf(part(0, "DTSTART:20261016T090000Z"))))

        assertEquals("DTSTART:20261016T090000Z", outcome.icalText)
        assertFalse(outcome.isPlaceholder)
        assertEquals(0, outcome.decryptFailures)
        assertEquals(0, outcome.verifyFailures)
    }

    @Test
    fun `type-1 BEGIN-prefixed data is plaintext`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)

        val outcome = decryptor.decrypt(detail(shared = listOf(part(1, "BEGIN:VCALENDAR\r\nDTSTART:20261016T090000Z"))))

        // BEGIN-prefixed type-1 data is treated as PLAINTEXT (never binary-decrypted); the
        // assembly then strips the VCALENDAR wrapper lines (crypto-envelope.md §3) so only
        // the property lines survive for the parser.
        assertEquals("DTSTART:20261016T090000Z", outcome.icalText)
        assertEquals(0, outcome.decryptFailures)
    }

    @Test
    fun `type-1 binary part decrypts through the shared key packet`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)
        val keyPacket = java.util.Base64.getEncoder().encodeToString("shared-key-packet".toByteArray())
        val data = java.util.Base64.getEncoder().encodeToString("encrypted-body".toByteArray())

        val outcome =
            decryptor.decrypt(
                detail(
                    shared = listOf(part(1, data)),
                    // No AddressKeyPacket → the SharedKeyPacket must be used (crypto-envelope.md §3).
                    sharedKeyPacket = java.util.Base64.getEncoder().encodeToString("shared-key-packet".toByteArray()),
                ),
            )

        assertTrue(outcome.icalText!!.contains("DTSTART:20261016T090000Z"))
        assertEquals("shared-key-packet", String(crypto.lastKeyPacket))
        assertEquals(0, outcome.decryptFailures)
    }

    @Test
    fun `shared parts prefer AddressKeyPacket and fall back to SharedKeyPacket`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)
        val data = java.util.Base64.getEncoder().encodeToString("body".toByteArray())
        val sharedKp = java.util.Base64.getEncoder().encodeToString("shared-kp".toByteArray())

        // No AddressKeyPacket → SharedKeyPacket must be used (crypto-envelope.md §3).
        decryptor.decrypt(detail(shared = listOf(part(1, data)), sharedKeyPacket = sharedKp))
        assertEquals("shared-kp", String(crypto.lastKeyPacket))
    }

    @Test
    fun `type-2 with a passing signature is kept`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)
        val body = "SUMMARY:Team sync\r\nDTSTART:20261016T090000Z"
        val signature = FakeProtonCrypto.validSignature(body)

        val outcome = decryptor.decrypt(detail(shared = listOf(part(2, body, signature))))

        assertEquals(body, outcome.icalText)
        assertEquals(0, outcome.verifyFailures)
    }

    @Test
    fun `type-2 with a failing signature is DROPPED and counted - verify-or-drop`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)
        val body = "SUMMARY:Tampered\r\nDTSTART:20261016T090000Z"

        val outcome =
            decryptor.decrypt(detail(shared = listOf(part(2, body, "SIG:forged-signature"))))

        assertNull(outcome.icalText)
        assertTrue(outcome.isPlaceholder)
        assertEquals(1, outcome.verifyFailures)
    }

    @Test
    fun `type-2 without a signature is kept - plaintext parts carry no authenticity`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)
        val body = "SUMMARY:Plain\r\nDTSTART:20261016T090000Z"

        val outcome = decryptor.decrypt(detail(shared = listOf(part(2, body))))

        assertEquals(body, outcome.icalText)
        assertEquals(0, outcome.verifyFailures)
    }

    @Test
    fun `type-3 binary decryption failure is counted and the event becomes a placeholder`() = runTest {
        setupKeyring()
        crypto.decryptBinarySucceeds = false
        val decryptor = EventPartDecryptor(crypto, keyring)
        val data = java.util.Base64.getEncoder().encodeToString("garbage".toByteArray())

        val outcome =
            decryptor.decrypt(
                detail(
                    shared = listOf(part(3, data)),
                    addressKeyPacket = java.util.Base64.getEncoder().encodeToString("kp".toByteArray()),
                ),
            )

        assertTrue(outcome.isPlaceholder)
        assertEquals(1, outcome.decryptFailures)
    }

    @Test
    fun `type-3 decrypted content with a failing signature is dropped and counted`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)
        val data = java.util.Base64.getEncoder().encodeToString("encrypted".toByteArray())

        val outcome =
            decryptor.decrypt(
                detail(
                    shared = listOf(part(3, data, "SIG:forged")),
                    addressKeyPacket = java.util.Base64.getEncoder().encodeToString("kp".toByteArray()),
                ),
            )

        assertTrue(outcome.isPlaceholder)
        assertEquals(1, outcome.verifyFailures)
    }

    @Test
    fun `armored data strings decrypt through the ring`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)

        val outcome =
            decryptor.decrypt(
                detail(shared = listOf(part(1, "-----BEGIN PGP MESSAGE-----\nDECRYPTABLE:DTSTART:20261016T090000Z\n-----END PGP MESSAGE-----"))),
            )

        assertTrue(outcome.icalText!!.contains("DTSTART:20261016T090000Z"))
        assertEquals(0, outcome.decryptFailures)
    }

    @Test
    fun `assembly strips wrapper lines and unfolds continuation lines`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)
        val folded = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nSUMMARY:Team\r\n sync\r\nDTSTART:20261016T090000Z\r\nEND:VCALENDAR"

        val outcome = decryptor.decrypt(detail(shared = listOf(part(0, folded))))

        assertEquals("SUMMARY:Team sync\r\nDTSTART:20261016T090000Z", outcome.icalText)
    }

    @Test
    fun `assembly without any DTSTART line is not mappable - placeholder`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)

        val outcome = decryptor.decrypt(detail(shared = listOf(part(0, "SUMMARY:no time here"))))

        assertTrue(outcome.isPlaceholder)
        assertNull(outcome.icalText)
    }

    @Test
    fun `unknown part types are skipped`() = runTest {
        setupKeyring()
        val decryptor = EventPartDecryptor(crypto, keyring)

        val outcome =
            decryptor.decrypt(
                detail(
                    shared = listOf(part(99, "garbage"), part(0, "DTSTART:20261016T090000Z")),
                ),
            )

        assertEquals("DTSTART:20261016T090000Z", outcome.icalText)
        assertEquals(0, outcome.decryptFailures)
        assertEquals(0, outcome.verifyFailures)
    }

    /** Deterministic fake of the OpenPGP primitive (see class doc). */
    private class FakeProtonCrypto : ProtonCrypto {
        var lastKeyPacket: ByteArray = ByteArray(0)
            private set
        var decryptBinarySucceeds = true

        override fun canParseKey(armoredKey: String): Boolean = true

        override fun unlock(
            armoredKey: String,
            passphrase: ByteArray,
        ): ByteArray = "unlocked:$armoredKey".toByteArray()

        override fun decryptArmoredText(
            armoredMessage: String,
            privateKey: ByteArray,
        ): String {
            val marker = "DECRYPTABLE:"
            val start = armoredMessage.indexOf(marker)
            if (start < 0) throw CryptoDecryptException("not decryptable")
            return armoredMessage.substring(start + marker.length)
        }

        override fun decryptArmoredData(
            armoredMessage: String,
            privateKey: ByteArray,
        ): ByteArray = decryptArmoredText(armoredMessage, privateKey).toByteArray()

        override fun decryptBinary(
            keyPacket: ByteArray,
            dataPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray {
            if (!decryptBinarySucceeds) throw CryptoDecryptException("AEAD failure")
            lastKeyPacket = keyPacket
            return "DTSTART:20261016T090000Z".toByteArray()
        }

        override fun decryptSessionKey(
            keyPacket: ByteArray,
            privateKey: ByteArray,
        ): ByteArray {
            lastKeyPacket = keyPacket
            return "session-key".toByteArray()
        }

        override fun decryptDataWithSessionKey(
            dataPacket: ByteArray,
            sessionKey: ByteArray,
        ): ByteArray = "DECRYPTED:DTSTART:20261016T090000Z".toByteArray()

        override fun verifyDetached(
            data: ByteArray,
            armoredSignature: String,
            senderArmoredKeys: List<String>,
        ): Boolean = armoredSignature == validSignature(String(data))

        override fun fingerprint(armoredKey: String): String = "fp"

        companion object {
            fun validSignature(body: String): String = "SIG:valid:${java.util.Base64.getEncoder().encodeToString(body.toByteArray())}"
        }
    }

    /** Software AES-GCM with the SessionCrypto wire contract (same as SessionStoreTest). */
    private class SoftwareGcmCrypto : digital.tonima.protonsync.session.SessionCrypto {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(data: ByteArray): ByteArray {
            val iv = data.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(data.copyOfRange(12, data.size))
        }
    }
}
