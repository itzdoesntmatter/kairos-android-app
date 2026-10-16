package digital.tonima.protonsync.crypto

/**
 * Inbound payload size caps (security-audit §3.5b, finding N-1) — the size half of the T5
 * (hostile server) defence. Count caps alone (`PAGE_SIZE`, `MAX_PAGES`, the 90-day windowing)
 * bound *how many* events a server can push; these constants bound *how big* each one may be,
 * so a multi-megabyte part can never reach the PGP parser, the iCal assembler or the property
 * parser and turn into an app-wide OOM that would take Kairós's alarms down with it.
 *
 * Policy: reject-and-count, never crash. Every rejection is logged with the offending size
 * and folded into the run's failure counters ([EventPartDecryptor.DecryptionOutcome]); the
 * event then degrades to the documented placeholder (time metadata only, §3.4.d) or is
 * skipped — the sync run always completes.
 *
 * The numbers are the audit's, applied at the boundary we own. §3.5b's two figures are
 * self-subsuming if taken as one aggregate ("event JSON ≤ 256 KB" already implies no part may
 * exceed 256 KB), so each is applied where it actually binds and the event budget is set so
 * the two rules stay independent:
 * - [MAX_ARMORED_CHARS]: 1 MB — a single armored PGP payload ("reject armored payloads > 1 MB").
 * - [MAX_PART_CHARS]: 256 KB — one non-armored part's `Data` ("event JSON > 256 KB uncompressed"
 *   at the field that dominates an event's JSON) and, via [MAX_ASSEMBLED_CHARS], everything
 *   that reaches the property parser.
 * - [MAX_EVENT_CHARS]: 1 MB — one event's aggregate payload across the four part lists plus its
 *   key packets. Equal to the armored ceiling on purpose: a maximum-size armored part passes
 *   the per-part rule and the aggregate rule independently, so neither hides the other.
 * - [MAX_SIGNATURE_CHARS] / [MAX_KEY_PACKET_CHARS]: detached OpenPGP signatures and PKESK key
 *   packets are ~1-4 KB in practice (a 4096-bit RSA signature is 512 bytes; armored text ~850
 *   bytes) — 64 KB is two orders of magnitude of headroom over anything real.
 * - [MAX_ASSEMBLED_CHARS]: the assembled iCal text handed to ical4j is held to the 256 KB
 *   budget of one event's JSON.
 * - [MAX_RESPONSE_BYTES]: a single HTTP response body (page-level; `PAGE_SIZE=100` events of
 *   a few KB each is the realistic worst case at ~1 MB, so 4 MB is generous while still
 *   OOM-proof). Enforced in [digital.tonima.protonsync.api.ResponseSizeLimitInterceptor].
 */
object PayloadLimits {

    /** 1 MB — maximum size of an armored PGP payload (security-audit §3.5b). */
    const val MAX_ARMORED_CHARS: Int = 1_048_576

    /** 256 KB — maximum size of one non-armored part's `Data` (security-audit §3.5b). */
    const val MAX_PART_CHARS: Int = 262_144

    /** 1 MB — maximum aggregate part payload of one event (per-part caps still apply). */
    const val MAX_EVENT_CHARS: Int = 1_048_576

    /** 64 KB — maximum size of a detached signature. */
    const val MAX_SIGNATURE_CHARS: Int = 65_536

    /** 64 KB — maximum size of a base64 PKESK key packet. */
    const val MAX_KEY_PACKET_CHARS: Int = 65_536

    /** 256 KB — maximum size of the assembled iCal text fed to the property parser. */
    const val MAX_ASSEMBLED_CHARS: Int = 262_144

    /** 4 MB — maximum size of a single Proton API response body. */
    const val MAX_RESPONSE_BYTES: Long = 4L * 1024L * 1024L

    /** An armored payload marker (`-----BEGIN PGP …`). */
    private const val ARMORED_PREFIX = "-----BEGIN PGP"

    /** True when [data] is an armored blob: [MAX_ARMORED_CHARS] applies, else [MAX_PART_CHARS]. */
    fun isOversizedPart(data: String?): Boolean =
        when {
            data == null -> false
            data.startsWith(ARMORED_PREFIX) -> data.length > MAX_ARMORED_CHARS
            else -> data.length > MAX_PART_CHARS
        }

    /** True when [signature] exceeds [MAX_SIGNATURE_CHARS]. */
    fun isOversizedSignature(signature: String?): Boolean =
        signature != null && signature.length > MAX_SIGNATURE_CHARS

    /** True when [keyPacket] exceeds [MAX_KEY_PACKET_CHARS]. */
    fun isOversizedKeyPacket(keyPacket: String?): Boolean =
        keyPacket != null && keyPacket.length > MAX_KEY_PACKET_CHARS
}
