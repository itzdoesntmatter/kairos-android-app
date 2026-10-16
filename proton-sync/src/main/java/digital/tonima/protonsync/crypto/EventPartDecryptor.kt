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
 *
 * SIZE CAPS — reject-and-count (security-audit §3.5b, finding N-1): the whole event is measured
 * before any crypto runs (per-part caps, key packets and the event's aggregate budget), and the
 * assembled text is held to the same budget ([PayloadLimits] carries the numbers and their
 * rationale). An oversized event degrades to the placeholder and is counted — a hostile server
 * cannot turn a multi-megabyte part into an app-wide OOM, and it cannot get a partial payload
 * written as if it were the event either.
 */
class EventPartDecryptor(
    private val crypto: ProtonCrypto,
    private val keyring: ProtonCryptoKeyring,
) {

    /**
     * Decrypts all four part lists of [detail] into assembled iCal text (or null → placeholder).
     *
     * Size caps are enforced at this boundary — before PGP and before the property parser
     * (security-audit §3.5b): per-event budget, per-part caps and the assembled-text budget
     * all live here so no oversized bytes can reach the crypto or the parser in the first
     * place. Rejections are counted in [DecryptionOutcome.payloadRejections].
     */
    suspend fun decrypt(detail: CalendarEventDetail): DecryptionOutcome {
        var decryptFailures = 0
        var verifyFailures = 0
        var payloadRejections = 0
        var assembledChars = 0
        var assembledOverflow = false
        val kept = mutableListOf<String>()

        // Accumulates what will be assembled, enforcing the 256 KB assembled budget on the
        // way in: a payload that would blow the budget is dropped whole (never a truncated
        // event written as if it were complete).
        fun keep(text: String) {
            if (assembledOverflow) return
            if (assembledChars + text.length > PayloadLimits.MAX_ASSEMBLED_CHARS) {
                assembledOverflow = true
                payloadRejections++
                logcat(TAG, LogPriority.WARN) {
                    "assembled payload for ${detail.id} would exceed " +
                        "${PayloadLimits.MAX_ASSEMBLED_CHARS} chars — dropping the event (§3.5b)"
                }
                return
            }
            kept += text
            assembledChars += text.length
        }

        val rejection = oversize(detail)
        if (rejection != null) {
            // Reject-and-count before any decode/decrypt (security-audit §3.5b / N-1). The
            // whole event is dropped, not just the offending part: assembling a partial
            // payload would write an event that silently is not the one the server sent.
            logcat(TAG, LogPriority.WARN) { "event ${detail.id}: $rejection — rejected before decryption" }
            return DecryptionOutcome(
                icalText = null,
                isPlaceholder = true,
                decryptFailures = 0,
                verifyFailures = 0,
                payloadRejections = 1,
            )
        }

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
                    TYPE_PLAINTEXT -> part.data?.let { keep(it) }
                    TYPE_BINARY_OR_TEXT -> {
                        if (part.data.isNullOrBlank()) continue
                        when {
                            // BEGIN:-prefixed (iCal text) is plaintext even in a type-1 part
                            // (crypto-envelope.md §3); only PGP-armored blobs decrypt.
                            part.data.startsWith("BEGIN:") -> keep(part.data)
                            isArmored(part.data) -> {
                                decryptArmored(list, part)?.let {
                                    keep(it)
                                } ?: decryptFailures++
                            }
                            else -> {
                                decryptBinaryWithKeyPacket(list, part)?.let {
                                    keep(it)
                                } ?: decryptFailures++
                            }
                        }
                    }
                    TYPE_SIGNED_PLAINTEXT -> {
                        if (part.data.isNullOrBlank()) continue
                        when (verifyOrDrop(part)) {
                            Verification.Verified -> keep(part.data)
                            Verification.Failed -> verifyFailures++
                            Verification.NoSignature -> keep(part.data)
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
                                    Verification.Verified, Verification.NoSignature -> keep(decrypted)
                                    Verification.Failed -> verifyFailures++
                                }
                            }
                        } else {
                            val decrypted = decryptBinaryWithKeyPacket(list, part)
                            if (decrypted == null) {
                                decryptFailures++
                            } else {
                                when (verifyOver(part, decrypted)) {
                                    Verification.Verified, Verification.NoSignature -> keep(decrypted)
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

        val assembled = if (assembledOverflow) null else assembleIcal(kept)
        return DecryptionOutcome(
            icalText = assembled,
            isPlaceholder = assembled == null,
            decryptFailures = decryptFailures,
            verifyFailures = verifyFailures,
            payloadRejections = payloadRejections,
        )
    }

    /**
     * The §3.5b size pre-scan: returns a human-readable reason when [detail] carries anything
     * over budget, null when the whole event may be decrypted. Runs on the decoded DTO — no
     * crypto, no base64, no parsing — so it costs a few integer additions and bounds every
     * step after it.
     *
     * Budgets ([PayloadLimits] carries the numbers and their provenance):
     * - one armored payload ≤ 1 MB, one non-armored part ≤ 256 KB (with a 64 KB signature cap),
     * - key packets ≤ 64 KB each,
     * - the event's aggregate part payload ≤ 1 MB.
     */
    private fun oversize(detail: CalendarEventDetail): String? {
        var total = 0L
        val lists =
            listOf(
                "shared" to detail.sharedEvents,
                "calendar" to detail.calendarEvents,
                "personal" to detail.personalEvents,
                "attendees" to detail.attendeesEvents,
            )
        for ((name, parts) in lists) {
            for (part in parts) {
                if (PayloadLimits.isOversizedPart(part.data)) {
                    return "oversized $name type-${part.type} part (${part.data?.length ?: 0} chars)"
                }
                if (PayloadLimits.isOversizedSignature(part.signature)) {
                    return "oversized $name type-${part.type} signature (${part.signature?.length ?: 0} chars)"
                }
                total += part.data?.length ?: 0
                total += part.signature?.length ?: 0
            }
        }
        val keyPackets =
            listOf(
                "calendar" to detail.calendarKeyPacket,
                "address" to detail.addressKeyPacket,
                "shared" to detail.sharedKeyPacket,
            )
        for ((name, packet) in keyPackets) {
            if (PayloadLimits.isOversizedKeyPacket(packet)) {
                return "oversized $name key packet (${packet?.length ?: 0} chars)"
            }
            total += packet?.length ?: 0
        }
        if (total > PayloadLimits.MAX_EVENT_CHARS) {
            return "part payload of $total chars over the ${PayloadLimits.MAX_EVENT_CHARS}-char event budget"
        }
        return null
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
    /**
     * Parts (or whole events) rejected by the size caps of security-audit §3.5b — counted
     * separately from decrypt/verify failures so the engine can log why content went missing
     * (N-1). The engine folds these into the persisted `decryptFailures` counter, which is
     * already surfaced in the settings card.
     */
    val payloadRejections: Int = 0,
)