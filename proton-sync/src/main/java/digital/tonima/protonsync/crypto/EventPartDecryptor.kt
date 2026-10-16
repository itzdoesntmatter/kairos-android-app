package digital.tonima.protonsync.crypto

import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.EventPart
import logcat.LogPriority
import logcat.logcat
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Inbound event decryption: the four EventPart lists → assembled iCal text
 * (crypto-envelope.md §3, security-audit HI-3/§3.4).
 *
 * Part semantics (crypto-envelope.md §3):
 * - Type 0: plaintext iCal lines, used as-is.
 * - Type 1: plaintext when `BEGIN:`-prefixed, else binary-decrypt with the list's key packet.
 * - Type 2: the main content part — plaintext iCal; an optional detached signature is
 *   verified before the content is accepted.
 * - Type 3: plaintext when `BEGIN:`-prefixed, else binary-decrypt then verify the detached
 *   signature over the DECRYPTED text.
 * - Other types: skipped.
 *
 * Key-packet selection per list (crypto-envelope.md §3): SharedEvents → AddressKeyPacket
 * falling back to SharedKeyPacket; CalendarEvents → CalendarKeyPacket; PersonalEvents and
 * AttendeesEvents → AddressKeyPacket. Decryption tries every key in the ring (one ring
 * opens PKESKs — crypto-envelope.md §2.5).
 *
 * STRICT signature verification — verify-or-drop (security-audit §3.4, HI-3): where the
 * reference swallows verification failures, Kairos drops the part and counts the failure.
 * A payload whose signature cannot be verified is never presented as authentic; when every
 * content part fails, the caller emits the unambiguous "[Encrypted event — cannot decrypt]"
 * placeholder carrying only the server-sent time metadata (§3.4.d). Plaintext parts (types
 * 0/2 without signature) carry NO authenticity and are schema-validated downstream as
 * untrusted input (§3.4.c).
 */
class EventPartDecryptor(
    private val crypto: ProtonCrypto,
    private val keyring: ProtonCryptoKeyring,
) {

    /** Decrypts all four part lists of [detail] into assembled iCal text (or null → placeholder). */
    suspend fun decrypt(detail: CalendarEventDetail): DecryptionOutcome {
        var decryptFailures = 0
        var verifyFailures = 0
        val kept = mutableListOf<String>()

        val lists =
            listOf(
                PartList(SHARED, detail.sharedEvents) {
                    detail.addressKeyPacket ?: detail.sharedKeyPacket
                },
                PartList(CALENDAR, detail.calendarEvents) { detail.calendarKeyPacket },
                PartList(PERSONAL, detail.personalEvents) { detail.addressKeyPacket },
                PartList(ATTENDEES, detail.attendeesEvents) { detail.addressKeyPacket },
            )

        for (list in lists) {
            for (part in list.parts) {
                when (part.type) {
                    TYPE_PLAINTEXT -> part.data?.let { kept += it }
                    TYPE_BINARY_OR_TEXT -> {
                        if (part.data.isNullOrBlank()) continue
                        when {
                            // BEGIN:-prefixed (iCal text) is plaintext even in a type-1 part
                            // (crypto-envelope.md §3); only PGP-armored blobs decrypt.
                            part.data.startsWith("BEGIN:") -> kept += part.data
                            isArmored(part.data) -> {
                                decryptArmored(list, part)?.let {
                                    kept += it
                                } ?: decryptFailures++
                            }
                            else -> {
                                decryptBinaryWithKeyPacket(list, part)?.let {
                                    kept += it
                                } ?: decryptFailures++
                            }
                        }
                    }
                    TYPE_SIGNED_PLAINTEXT -> {
                        if (part.data.isNullOrBlank()) continue
                        when (verifyOrDrop(part)) {
                            Verification.Verified -> kept += part.data
                            Verification.Failed -> verifyFailures++
                            Verification.NoSignature -> kept += part.data
                        }
                    }
                    TYPE_SIGNED_BINARY -> {
                        if (part.data.isNullOrBlank()) continue
                        if (isArmored(part.data)) {
                            // Armored type-3: reference decrypts then verifies over the
                            // decrypted text (crypto-envelope.md §3).
                            val decrypted = decryptArmored(list, part)
                            if (decrypted == null) {
                                decryptFailures++
                            } else {
                                when (verifyOver(part, decrypted)) {
                                    Verification.Verified, Verification.NoSignature -> kept += decrypted
                                    Verification.Failed -> verifyFailures++
                                }
                            }
                        } else {
                            val decrypted = decryptBinaryWithKeyPacket(list, part)
                            if (decrypted == null) {
                                decryptFailures++
                            } else {
                                when (verifyOver(part, decrypted)) {
                                    Verification.Verified, Verification.NoSignature -> kept += decrypted
                                    Verification.Failed -> verifyFailures++
                                }
                            }
                        }
                    }
                    else -> logcat(TAG, LogPriority.DEBUG) {
                        "skipping unknown event-part type ${part.type}"
                    }
                }
            }
        }

        val assembled = assembleIcal(kept)
        return if (assembled == null) {
            DecryptionOutcome(
                icalText = null,
                isPlaceholder = true,
                decryptFailures = decryptFailures,
                verifyFailures = verifyFailures,
            )
        } else {
            DecryptionOutcome(
                icalText = assembled,
                isPlaceholder = false,
                decryptFailures = decryptFailures,
                verifyFailures = verifyFailures,
            )
        }
    }

    /** Binary decrypt of the list's key packet ‖ data packet, trying every ring key. */
    private suspend fun decryptBinaryWithKeyPacket(
        list: PartList,
        part: EventPart,
    ): String? {
        val keyPacket =
            decodeBase64(list.keyPacket())
        if (keyPacket == null) {
            logcat(TAG, LogPriority.WARN) { "undecodable key packet for ${list.name} part" }
            return null
        }
        val dataPacket = decodeBase64(part.data) ?: return null
        val keys = keyring.ringKeys()
        for (key in keys) {
            try {
                return crypto.decryptBinary(keyPacket, dataPacket, key).toString(StandardCharsets.UTF_8)
            } catch (e: CryptoDecryptException) {
                // next key in the ring
            }
        }
        logcat(TAG, LogPriority.WARN) { "no ring key opened the ${list.name} binary part" }
        return null
    }

    private suspend fun decryptArmored(
        list: PartList,
        part: EventPart,
    ): String? {
        val data = part.data ?: return null
        val keys = keyring.ringKeys()
        for (key in keys) {
            try {
                return crypto.decryptArmoredText(data, key)
            } catch (e: CryptoDecryptException) {
                // next key in the ring
            }
        }
        logcat(TAG, LogPriority.WARN) { "no ring key opened the ${list.name} armored part" }
        return null
    }

    /** Detached-signature check for plaintext parts (type 2). */
    private suspend fun verifyOrDrop(part: EventPart): Verification {
        val signature = part.signature
        if (signature.isNullOrBlank()) return Verification.NoSignature
        return verifyOver(part, part.data ?: "")
    }

    private suspend fun verifyOver(
        part: EventPart,
        data: String,
    ): Verification {
        val signature = part.signature
        if (signature.isNullOrBlank()) return Verification.NoSignature
        val senderKeys = keyring.verificationArmoredKeys()
        val verified =
            crypto.verifyDetached(
                data.toByteArray(StandardCharsets.UTF_8),
                signature,
                senderKeys,
            )
        return if (verified) {
            Verification.Verified
        } else {
            logcat(TAG, LogPriority.WARN) {
                "signature verification failed on a type-${part.type} part — dropping it (verify-or-drop)"
            }
            Verification.Failed
        }
    }

    /**
     * Assembly (crypto-envelope.md §3): line-unfold, drop the VCALENDAR/VEVENT wrapper lines
     * (VERSION, PRODID, BEGIN/END), keep property lines. Returns null when nothing usable
     * survived — the caller then builds the placeholder from time metadata only.
     */
    private fun assembleIcal(kept: List<String>): String? {
        val properties = mutableListOf<String>()
        for (chunk in kept) {
            val unfolded = unfoldLines(chunk)
            for (line in unfolded) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val name = trimmed.substringBefore(':').substringBefore(';').uppercase()
                if (name in WRAPPER_PROPERTIES) continue
                properties += trimmed
            }
        }
        if (properties.none { it.startsWith("DTSTART", ignoreCase = true) }) {
            // A payload without a start time is not mappable — placeholder instead of
            // fabricating a time.
            return null
        }
        return properties.joinToString("\r\n")
    }

    private fun unfoldLines(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val unfolded = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // Continuation line: join it to the previous one, KEEPING its whitespace —
                // the fold marker was the CRLF; content whitespace is data (RFC 5545 §3.1
                // unfolding removes the CRLF, and the KAT vectors assert the space survives).
                if (unfolded.isNotEmpty()) {
                    unfolded[unfolded.size - 1] = unfolded.last() + line
                }
            } else {
                unfolded += line
            }
        }
        return unfolded
    }

    private fun isArmored(data: String): Boolean =
        data.startsWith(BEGIN_PGP_MARKER)

    private fun decodeBase64(raw: String?): ByteArray? =
        try {
            raw?.let { Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            null
        }

    private enum class Verification { Verified, Failed, NoSignature }

    private data class PartList(
        val name: String,
        val parts: List<EventPart>,
        val keyPacket: () -> String?,
    )

    companion object {
        private const val TAG = "ProtonPartDecryptor"
        private const val TYPE_PLAINTEXT = 0
        private const val TYPE_BINARY_OR_TEXT = 1
        private const val TYPE_SIGNED_PLAINTEXT = 2
        private const val TYPE_SIGNED_BINARY = 3

        private const val SHARED = "shared"
        private const val CALENDAR = "calendar"
        private const val PERSONAL = "personal"
        private const val ATTENDEES = "attendees"

        private const val BEGIN_PGP_MARKER = "-----BEGIN PGP"

        /** Wrapper lines dropped during assembly (crypto-envelope.md §3). */
        private val WRAPPER_PROPERTIES = setOf("BEGIN", "END", "VERSION", "PRODID")
    }
}

/** Result of decrypting one event's part lists. */
data class DecryptionOutcome(
    /** Assembled iCal property lines; null → nothing decrypted, use the placeholder. */
    val icalText: String?,
    val isPlaceholder: Boolean,
    val decryptFailures: Int,
    val verifyFailures: Int,
)