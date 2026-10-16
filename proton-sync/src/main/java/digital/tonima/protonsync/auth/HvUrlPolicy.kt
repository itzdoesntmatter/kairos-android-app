package digital.tonima.protonsync.auth

import java.net.URI

/**
 * The human-verification URL policy (auth-flow.md §2.7, security-audit §2.2.22).
 *
 * One allowlist, one implementation, three call sites — the login flow when the challenge is
 * parsed, the URL resolver for every redirect hop, and the settings UI immediately before a
 * Custom Tab is launched. Keeping it in one place is what makes "the checked URL is the URL
 * that opens" auditable: there is no second, weaker copy of the rule in the UI layer.
 *
 * Two hardenings over a bare host comparison:
 * - **https only.** An `http://` challenge URL would let the network see (and rewrite) the
 *   verification page; Proton's WebUrl is always https, so refusing plaintext costs nothing.
 * - **host, not substring.** `URI.host` is compared exactly, so `proton.me.evil.example`,
 *   `notproton.me`, and `https://proton.me@evil.example` are all refused — userinfo and
 *   suffix tricks do not survive the parse.
 */
object HvUrlPolicy {

    /** The hosts Proton's human-verification challenges may live on (auth-flow.md §2.7). */
    val ALLOWED_HOSTS: Set<String> = setOf("proton.me", "protonmail.com", "hcaptcha.com")

    /** The only scheme a challenge URL may use. */
    private const val REQUIRED_SCHEME = "https"

    /**
     * True when [url] is an absolute https URL whose host is on [ALLOWED_HOSTS]. Malformed
     * input is refused — never thrown, so callers can use this as a guard on untrusted input.
     */
    fun isAllowed(url: String): Boolean {
        val uri =
            try {
                URI(url.trim())
            } catch (e: Exception) {
                return false
            }
        if (!uri.isAbsolute || uri.host.isNullOrBlank()) return false
        if (!REQUIRED_SCHEME.equals(uri.scheme, ignoreCase = true)) return false
        return uri.host.lowercase() in ALLOWED_HOSTS
    }
}
