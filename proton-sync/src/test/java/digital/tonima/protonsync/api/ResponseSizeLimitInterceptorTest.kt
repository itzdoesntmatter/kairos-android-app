package digital.tonima.protonsync.api

import digital.tonima.protonsync.crypto.PayloadLimits
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The response-body cap (security-audit §3.5b, finding N-1: "no response-body cap"). A hostile
 * server must not be able to stream an unbounded body into memory: both the declared-length
 * path and the chunked/lying path are pinned here, plus the happy path that a normal response
 * still arrives intact.
 */
class ResponseSizeLimitInterceptorTest {

    private val payload = "x".repeat(4096)

    /** A client whose "network" is a canned response — the cap interceptor runs first. */
    private fun clientWithCannedBody(
        maxBytes: Long,
        body: () -> ResponseBody,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ResponseSizeLimitInterceptor(maxBytes))
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body())
                        .build()
                },
            )
            .build()

    /** Reads the body to completion, returning it or throwing what the cap raised. */
    private fun readBody(
        client: OkHttpClient,
        url: String = "https://mail-api.proton.me/calendar/v1",
    ): String? =
        client
            .newCall(Request.Builder().url(url).build())
            .execute()
            .use { response -> response.body?.string() }

    @Test
    fun `a response under the cap is delivered unchanged`() {
        val client = clientWithCannedBody(maxBytes = 8192) { payload.toResponseBody() }

        assertEquals(payload, readBody(client))
    }

    @Test
    fun `a declared content length over the cap is refused before the body is read`() {
        val client = clientWithCannedBody(maxBytes = 1024) { payload.toResponseBody() }

        try {
            readBody(client)
            fail("expected the response to be refused")
        } catch (e: ResponseTooLargeException) {
            assertEquals(1024L, e.limitBytes)
            assertTrue(e.message!!.contains("/calendar/v1"))
        }
    }

    @Test
    fun `a chunked body is cut off mid-stream when it exceeds the cap`() {
        // contentLength = -1: the declared-length check cannot fire, so only the streaming
        // guard can stop it (a server may lie about or omit its length).
        val client =
            clientWithCannedBody(maxBytes = 1024) {
                payload
                    .byteInputStream()
                    .source()
                    .buffer()
                    .asResponseBody(contentType = null, contentLength = -1L)
            }

        try {
            readBody(client)
            fail("expected the response to be refused")
        } catch (e: ResponseTooLargeException) {
            assertEquals(1024L, e.limitBytes)
        }
    }

    @Test
    fun `the default cap is the documented 4 MB`() {
        assertEquals(4L * 1024L * 1024L, PayloadLimits.MAX_RESPONSE_BYTES)
    }
}
