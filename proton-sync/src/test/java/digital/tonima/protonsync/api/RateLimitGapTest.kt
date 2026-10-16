package digital.tonima.protonsync.api

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Gap-fill for [RateLimitInterceptorTest] (specs/test-plan.md API-07): the arithmetic edges of
 * the 429 policy — attempt ceiling vs cumulative ceiling, jitter application, `Retry-After`
 * parsing fallbacks, and the exponential ceiling on the last permitted attempt
 * (auth-flow.md §1, architecture §6).
 */
class RateLimitGapTest {

    private var nowMs = 0L
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        RateLimitInterceptor.resetBackoffGate()
        sleeps.clear()
        nowMs = 0L
    }

    @After
    fun tearDown() {
        RateLimitInterceptor.resetBackoffGate()
    }

    @Test
    fun `the retry loop stops at the five-attempt ceiling when no backoff accumulates`() {
        // Retry-After: 0 keeps the cumulative wait at 0 ms, so the only bound left is
        // MAX_ATTEMPTS: five requests, four retries (auth-flow.md §1 "max 5 attempts").
        var calls = 0
        val response = interceptor(jitter = 0).intercept(
            chain {
                calls++
                response(code = 429, retryAfterSeconds = 0)
            },
        )

        assertEquals(429, response.code)
        assertEquals(RateLimitInterceptor.MAX_ATTEMPTS, calls)
        assertEquals(listOf(0L, 0L, 0L, 0L), sleeps)
    }

    @Test
    fun `jitter is added on top of the exponential base and stays within the 500 ms bound`() {
        var calls = 0
        interceptor(jitter = 500).intercept(
            chain {
                calls++
                if (calls == 1) response(code = 429) else response(code = 200)
            },
        )

        assertEquals(2, calls)
        // 2^1 s base + the injected maximum jitter.
        assertEquals(listOf(2500L), sleeps)
    }

    @Test
    fun `a non-numeric Retry-After falls back to the exponential policy`() {
        var calls = 0
        interceptor(jitter = 0).intercept(
            chain {
                calls++
                if (calls == 1) response(code = 429, retryAfterHeaderValue = "2.5") else response(code = 200)
            },
        )

        assertEquals(2, calls)
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun `a 20 s Retry-After is honoured once, then the cumulative cap stops the loop`() {
        var calls = 0
        val response = interceptor(jitter = 0).intercept(
            chain {
                calls++
                response(code = 429, retryAfterSeconds = 20)
            },
        )

        assertEquals(listOf(20_000L), sleeps)
        assertEquals(429, response.code)
    }

    @Test
    fun `the exponential series reaches its 16 s ceiling on the last permitted attempt`() {
        // Retry-After: 0 for the first three rounds keeps the cumulative total below the cap, so
        // the loop reaches attempt 4 and the 2^i series is exercised at its ceiling (2^4 = 16 s,
        // MAX_EXPONENTIAL_MS): the next round would exceed MAX_ATTEMPTS and returns instead.
        var calls = 0
        interceptor(jitter = 0).intercept(
            chain {
                calls++
                when (calls) {
                    4 -> response(code = 429)
                    else -> response(code = 429, retryAfterSeconds = 0)
                }
            },
        )

        assertEquals(RateLimitInterceptor.MAX_ATTEMPTS, calls)
        assertEquals(listOf(0L, 0L, 0L, 16_000L), sleeps)
    }

    @Test
    fun `a negative Retry-After is passed to the sleeper unchanged - FINDING F-5`() {
        // FINDING F-5 (LOD-12): `Retry-After` is server-controlled and parsed with toLongOrNull
        // without a lower bound, so a hostile/broken value of "-1" produces a negative wait that
        // is handed to the sleeper. Production wires `Thread.sleep`, which throws
        // IllegalArgumentException on a negative delay — a 429 with a negative Retry-After would
        // crash the call instead of backing off. Recommend clamping at 0. Owner: code-agent.
        var calls = 0
        val response = interceptor(jitter = 0).intercept(
            chain {
                calls++
                response(code = 429, retryAfterSeconds = -1)
            },
        )

        assertEquals(429, response.code)
        assertEquals(listOf(-1000L, -1000L, -1000L, -1000L), sleeps)
    }

    private fun interceptor(jitter: Int): RateLimitInterceptor =
        RateLimitInterceptor(
            sleeper = { ms ->
                sleeps.add(ms)
                nowMs += ms
            },
            jitterSource = FixedJitter(jitter),
            clock = { nowMs },
        )

    private fun response(
        code: Int,
        retryAfterSeconds: Long? = null,
        retryAfterHeaderValue: String? = null,
    ): Response =
        Response.Builder()
            .request(Request.Builder().url("https://mail-api.proton.me/calendar/v1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 429) "Too Many Requests" else "OK")
            .apply {
                (retryAfterHeaderValue ?: retryAfterSeconds?.toString())?.let { header("Retry-After", it) }
            }
            .build()

    private fun chain(proceed: () -> Response): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>(relaxed = true)
        every { chain.request() } returns Request.Builder().url("https://mail-api.proton.me/calendar/v1").build()
        every { chain.proceed(any()) } answers { proceed() }
        return chain
    }

    /** Deterministic jitter source returning a fixed value. */
    private class FixedJitter(private val value: Int) : Random(0) {
        override fun nextInt(bound: Int): Int = value.coerceAtMost(bound - 1)
    }
}
