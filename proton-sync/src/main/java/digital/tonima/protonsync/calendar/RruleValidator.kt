package digital.tonima.protonsync.calendar

import net.fortuna.ical4j.model.property.RRule
import java.time.LocalDateTime

/**
 * Structural RRULE validation for CalendarProvider writes (security-audit S-4: "full parse
 * (or bounded structural validator) with reject → single-instance degradation, or per-event
 * try/catch isolation"). Uses the same ical4j parser family the reference app ships
 * (sync-algorithm §4.4), so what passes here is structurally a real RFC 5545 recurrence
 * rule, not merely a `FREQ=`-prefixed string.
 *
 * Semantics contract with the engine: [validate] returns null for anything unparseable —
 * the caller degrades the event to a single instance and logs, it never throws
 * mid-transaction and never feeds garbage to the provider.
 */
object RruleValidator {

    /**
     * Returns the trimmed rule when it parses and validates as an RFC 5545 RRULE, null
     * otherwise. Blank input is a non-recurring event (valid, returns null by contract).
     */
    fun validate(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            RRule<LocalDateTime>(trimmed).validate()
            trimmed
        } catch (e: Exception) {
            null
        }
    }
}
