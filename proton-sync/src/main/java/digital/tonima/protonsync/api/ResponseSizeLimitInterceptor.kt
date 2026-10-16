package digital.tonima.protonsync.api

import digital.tonima.protonsync.crypto.PayloadLimits
import logcat.LogPriority
import logcat.logcat
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** A Proton response body exceeded [PayloadLimits.MAX_RESPONSE_BYTES] (security-audit §3.5b). */
class ResponseTooLargeException(
    val limitBytes: Long,
    val path: String,
) : IOException("Proton response body for $path is over the $limitBytes-byte cap (security-audit §3.5b)")

/**
 * Caps one Proton API response body (security-audit §3.5b / finding N-1: "no response-body
 * cap"). Count caps bound how many events a page may carry; this bounds how many *bytes* a
 * response may stream into memory before kotlinx-serialization would materialise them as
 * Strings — the OOM vector a hostile server (T5) would otherwise use.
 *
 * Both paths are covered: a declared `Content-Length` over the cap fails before any byte is
 * read, and a chunked/lying response is cut off mid-stream (the reader throws once it has
 * seen one byte more than the cap). The failure is a plain [IOException], so the engine's
 * existing per-calendar isolation handles it — a too-large page defers that calendar's run,
 * it never crashes the process.
 */
class ResponseSizeLimitInterceptor(
    private val maxBytes: Long = PayloadLimits.MAX_RESPONSE_BYTES,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val path = chain.request().url.encodedPath

        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) {
            body.close()
            logcat(TAG, LogPriority.WARN) {
                "rejecting $path: declared Content-Length $declaredLength exceeds the $maxBytes-byte cap (§3.5b)"
            }
            throw ResponseTooLargeException(limitBytes = maxBytes, path = path)
        }

        val limited =
            object : ForwardingSource(body.source()) {
                private var readBytes = 0L

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val read = super.read(sink, byteCount)
                    if (read > 0) {
                        readBytes += read
                        if (readBytes > maxBytes) {
                            logcat(TAG, LogPriority.WARN) {
                                "rejecting $path: body exceeded the $maxBytes-byte cap while reading (§3.5b)"
                            }
                            throw ResponseTooLargeException(limitBytes = maxBytes, path = path)
                        }
                    }
                    return read
                }
            }

        return response
            .newBuilder()
            .body(limited.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }

    private companion object {
        private const val TAG = "ProtonResponseCap"
    }
}
