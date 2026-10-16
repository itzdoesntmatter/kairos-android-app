package digital.tonima.protonsync.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP 429 backoff policy (auth-flow.md §1, architecture §6): honour `Retry-After` when
 * present, else exponential `2^i` capped at 16 s plus ≤500 ms jitter; a single wait is capped
 * at 30 s and at most [MAX_ATTEMPTS] attempts are made. A process-wide gate makes concurrent
 * callers share the backoff (the reference's AtomicLong pattern) so a rate-limit burst from N
 * coroutines doesn't produce N independent retry storms.
 *
 * Testability: the sleeper, jitter source and clock are injected; production wiring uses
 * `Thread.sleep`, [Random] and [System.currentTimeMillis] on OkHttp's background threads.
 */
class RateLimitInterceptor(
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val jitterSource: Random = Random(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var totalWaitMs = 0L
        while (true) {
            waitForGate()
            val response = chain.proceed(chain.request())
            if (response.code != 429 || attempt >= MAX_ATTEMPTS - 1) {
                return response
            }
            attempt++
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
            val waitMs = backoffMillis(attempt, retryAfterSeconds)
            totalWaitMs += waitMs
            if (totalWaitMs >= TOTAL_WAIT_CAP_MS) {
                return response
            }
            releaseGate(waitMs)
            sleeper(waitMs)
        }
    }

    private fun backoffMillis(attempt: Int, retryAfterSeconds: Long?): Long {
        val base =
            if (retryAfterSeconds != null) {
                (retryAfterSeconds * 1000).coerceAtMost(MAX_SINGLE_WAIT_MS)
            } else {
                (1L shl attempt).coerceAtMost(MAX_EXPONENTIAL_MS) * 1000
            }
        return (base + jitterSource.nextInt(JITTER_MAX_MS + 1)).coerceAtMost(MAX_SINGLE_WAIT_MS)
    }

    /** Concurrent callers sleep until the gate expires — one shared backoff, not N. */
    private fun waitForGate() {
        var remaining = nextAllowedRequestAt.get() - clock()
        while (remaining > 0) {
            sleeper(remaining)
            remaining = nextAllowedRequestAt.get() - clock()
        }
    }

    private fun releaseGate(waitMs: Long) {
        nextAllowedRequestAt.set(clock() + waitMs)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_SINGLE_WAIT_MS = 30_000L
        const val MAX_EXPONENTIAL_MS = 16L
        const val TOTAL_WAIT_CAP_MS = 30_000L
        private const val JITTER_MAX_MS = 500

        private val nextAllowedRequestAt = AtomicLong(0L)

        /** Test hook: clears the process-wide gate so suites run deterministically. */
        internal fun resetBackoffGate() {
            nextAllowedRequestAt.set(0L)
        }
    }
}
