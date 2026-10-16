package digital.tonima.protonsync.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves the human-verification URL's redirect chain BEFORE a Custom Tab opens
 * (security-audit §2.2.22: "verify the CustomTab's *actual* resolved URL against that
 * allowlist, not just the request URL").
 *
 * The app can never observe what the browser process does with a URL after launch — Custom
 * Tabs run out of process and we deliberately have no WebView (§9.8). What is checkable, and
 * what this does, is to resolve the chain ourselves: every hop of the redirect chain is
 * validated with [HvUrlPolicy], and the **final resolved URL** is the one handed to the tab.
 * A chain that leaves the allowlist anywhere is refused before it can be opened.
 *
 * Failure handling is asymmetric on purpose:
 * - a chain that resolves to a non-allowlisted host → [Resolution.Refused] (hard stop),
 * - a network error or a missing `Location` on a 3xx → [Resolution.Unresolved], which the
 *   caller may still open if the *original* URL is allowlisted: a flaky network must not
 *   dead-end a legitimate login, and the original URL was already allowlist-checked.
 *
 * The probe is a seam ([HvRedirectProbe]) so the walk is unit-testable without a network;
 * [OkHttpHvRedirectProbe] is the production adapter (plain client — no Proton headers, no
 * certificate pinning: this request never goes to Proton's API).
 */
class HvUrlResolver(
    private val probe: HvRedirectProbe,
    private val maxRedirects: Int = MAX_REDIRECTS,
) {

    sealed interface Resolution {
        /** The URL to open: the end of a chain whose every hop passed the allowlist. */
        data class Resolved(val url: String) : Resolution

        /** The chain left the allowlist (or was malformed) — never open this. */
        data class Refused(val reason: String) : Resolution

        /** The chain could not be resolved; the caller falls back to the original URL. */
        data class Unresolved(val reason: String) : Resolution
    }

    suspend fun resolve(url: String): Resolution =
        withContext(Dispatchers.IO) {
            var current = url.trim()
            var hops = 0
            while (true) {
                if (!HvUrlPolicy.isAllowed(current)) {
                    return@withContext Resolution.Refused(
                        "the verification URL resolves to an untrusted host — refusing to open it",
                    )
                }
                if (hops >= maxRedirects) {
                    return@withContext Resolution.Unresolved(
                        "the verification URL is still redirecting after $maxRedirects hops",
                    )
                }
                when (val probeResult = probe.probe(current)) {
                    is HvRedirectProbe.Result.Terminal -> {
                        logcat(TAG, LogPriority.INFO) {
                            "human-verification URL resolved to ${probeResult.url} after $hops redirect(s)"
                        }
                        return@withContext Resolution.Resolved(probeResult.url)
                    }
                    is HvRedirectProbe.Result.Redirect -> {
                        val next =
                            probeResult.base.toHttpUrlOrNull()?.resolve(probeResult.location)?.toString()
                                ?: return@withContext Resolution.Refused(
                                    "the verification URL redirects to a malformed location",
                                )
                        current = next
                        hops++
                    }
                    is HvRedirectProbe.Result.Unreachable ->
                        return@withContext Resolution.Unresolved(probeResult.reason)
                }
            }
            // Unreachable: the loop returns from every branch.
            @Suppress("UNREACHABLE_CODE")
            Resolution.Unresolved("could not resolve the verification URL")
        }

    companion object {
        private const val TAG = "ProtonHvUrlResolver"

        /** Redirect hops followed before the walk gives up (three is already unusual). */
        const val MAX_REDIRECTS = 5
    }
}

/**
 * Redirect probe seam: one HEAD request per hop. [Result.Redirect] carries the base URL the
 * `Location` header is relative to (RFC 7231 §7.1.2 allows relative locations).
 */
interface HvRedirectProbe {

    fun probe(url: String): Result

    sealed interface Result {
        /** No further redirect: [url] is where the tab would land. */
        data class Terminal(val url: String) : Result

        data class Redirect(
            val base: String,
            val location: String,
        ) : Result

        data class Unreachable(val reason: String) : Result
    }
}

/**
 * The production [HvRedirectProbe]: a HEAD request with redirects disabled so the resolver can
 * validate every hop itself. Deliberately built on its own [OkHttpClient] — the Proton API
 * client carries Proton auth headers and certificate pinning, neither of which may be sent to
 * an hCaptcha host; this client is a plain, short-timeout, default-trust client.
 */
class OkHttpHvRedirectProbe(
    private val client: OkHttpClient = defaultClient(),
) : HvRedirectProbe {

    override fun probe(url: String): HvRedirectProbe.Result {
        val request =
            try {
                Request.Builder().url(url).head().build()
            } catch (e: Exception) {
                return HvRedirectProbe.Result.Unreachable("malformed URL: ${e.message}")
            }
        return try {
            client.newCall(request).execute().use { response ->
                val location = response.header("Location")
                if (response.isRedirect && !location.isNullOrBlank()) {
                    HvRedirectProbe.Result.Redirect(base = response.request.url.toString(), location = location)
                } else {
                    HvRedirectProbe.Result.Terminal(response.request.url.toString())
                }
            }
        } catch (e: Exception) {
            HvRedirectProbe.Result.Unreachable("could not reach the challenge host: ${e.message}")
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L

        /**
         * A plain client: default trust store, no cleartext (the policy only allows https),
         * no redirects, no Proton interceptors. Short timeouts keep the login UI responsive.
         */
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
