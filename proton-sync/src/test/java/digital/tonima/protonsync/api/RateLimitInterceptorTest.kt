package digital.tonima.protonsync.api

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

class RateLimitInterceptorTest {

    /** Fake clock in ms, advanced by the injected sleepers so waits are deterministic. */
    private var nowMs = 0L

    private val sleepMs = mutableListOf<Long>()
    private val recordedSleeps: (Long) -> Unit = { ms ->
        sleepMs.add(ms)
        nowMs += ms
    }

    private lateinit var interceptor: RateLimitInterceptor

    @Before
    fun setUp() {
        RateLimitInterceptor.resetBackoffGate()
        sleepMs.clear()
        nowMs = 0L
        interceptor = RateLimitInterceptor(
            sleeper = recordedSleeps,
            jitterSource = ZeroJitterRandom(),
            clock = { nowMs },
        )
    }

    @After
    fun tearDown() {
        RateLimitInterceptor.resetBackoffGate()
    }

    @Test
    fun `non-429 responses pass through untouched`() {
        var calls = 0
        val response = runInterceptor(chainProceed = { calls++; response(code = 200) })

        assertEquals(200, response.code)
        assertEquals(1, calls)
        assertEquals(emptyList<Long>(), sleepMs)
    }

    @Test
    fun `a 429 with Retry-After sleeps the server-specified delay and retries`() {
        var calls = 0
        val response = runInterceptor(
            chainProceed = {
                calls++
                if (calls == 1) response(code = 429, retryAfterSeconds = 2) else response(code = 200)
            },
        )

        assertEquals(200, response.code)
        assertEquals(2, calls)
        assertEquals(listOf(2000L), sleepMs)
    }

    @Test
    fun `a 429 without Retry-After backs off exponentially with jitter`() {
        var calls = 0
        runInterceptor(
            chainProceed = {
                calls++
                if (calls == 1) response(code = 429) else response(code = 200)
            },
        )

        assertEquals(2, calls)
        // 2^1 s = 2000 ms + zero jitter from the injected source.
        assertEquals(listOf(2000L), sleepMs)
    }

    @Test
    fun `a Retry-After beyond the 30 s single-wait cap gives up without retrying`() {
        var calls = 0
        val response = runInterceptor(
            chainProceed = { calls++; response(code = 429, retryAfterSeconds = 120) },
        )

        assertEquals(429, response.code)
        assertEquals(1, calls)
        assertEquals(emptyList<Long>(), sleepMs)
    }

    @Test
    fun `gives up when the cumulative 30 s wait cap is reached — before the attempt maximum`() {
        var calls = 0
        val response = runInterceptor(
            chainProceed = { calls++; response(code = 429) },
        )

        assertEquals(429, response.code)
        // Waits 2^1+2^2+2^3 s (2 s, 4 s, 8 s); the next 16 s wait would cross the 30 s total
        // cap, so the interceptor returns the last 429 after 4 attempts.
        assertEquals(4, calls)
        assertEquals(listOf(2000L, 4000L, 8000L), sleepMs)
    }

    @Test
    fun `cumulative wait above the 30 s total cap gives up instead of retrying`() {
        var calls = 0
        val response = runInterceptor(
            chainProceed = { calls++; response(code = 429, retryAfterSeconds = 8) },
        )

        assertEquals(429, response.code)
        // Three 8 s sleeps accumulate to 24 s; the fourth would cross 30 s → abort before it.
        assertEquals(4, calls)
        assertEquals(listOf(8000L, 8000L, 8000L), sleepMs)
    }

    @Test
    fun `the process-wide gate makes concurrent callers share the backoff`() {
        var calls = 0
        val first = runInterceptor(
            chainProceed = {
                calls++
                if (calls == 1) response(code = 429) else response(code = 200)
            },
        )
        assertEquals(200, first.code)
        assertEquals(listOf(2000L), sleepMs)

        // A second caller "arrives" while the first backoff is still active (its clock reads
        // the pre-wait time): it must sleep until the gate expires, then proceed untouched.
        nowMs = 0L
        val secondSleeps = mutableListOf<Long>()
        val second = RateLimitInterceptor(
            sleeper = { ms ->
                secondSleeps.add(ms)
                nowMs += ms
            },
            jitterSource = ZeroJitterRandom(),
            clock = { nowMs },
        )
        val response = second.intercept(chain { response(code = 200) })

        assertEquals(200, response.code)
        // The first caller released the gate at its own clock+2000; the second starts at 0.
        assertEquals(listOf(2000L), secondSleeps)
    }

    private fun response(code: Int, retryAfterSeconds: Long? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("https://mail-api.proton.me/calendar/v1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 429) "Too Many Requests" else "OK")
            .apply {
                retryAfterSeconds?.let { header("Retry-After", it.toString()) }
            }
            .build()

    private fun runInterceptor(chainProceed: () -> Response): Response =
        interceptor.intercept(chain(chainProceed))

    private fun chain(chainProceed: () -> Response): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>(relaxed = true)
        every { chain.request() } returns
            Request.Builder().url("https://mail-api.proton.me/calendar/v1").build()
        every { chain.proceed(any()) } answers { chainProceed() }
        return chain
    }

    /** Deterministic jitter source: always adds zero. */
    private class ZeroJitterRandom : Random(0) {
        override fun nextInt(bound: Int): Int = 0
    }
}
