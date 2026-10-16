package digital.tonima.protonsync.auth

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Detects device/server clock skew from the `Date` response header of `auth/v4/info`
 * (auth-flow.md §2.2): when the device is 20 s or more off server time, TOTP codes computed
 * locally will be rejected, and the login UI must warn before the 2FA code is submitted.
 */
class ClockSkewDetector {

    /** True when the device clock is [MAX_SKEW_SECONDS] or more away from [serverDateHeader]. */
    fun isClockSkewed(
        serverDateHeader: String,
        deviceEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val serverTime = parseHttpDate(serverDateHeader) ?: return false
        val skewSeconds = kotlin.math.abs(serverTime.toEpochMilli() - deviceEpochMillis) / 1000
        return skewSeconds >= MAX_SKEW_SECONDS
    }

    /** Parses an RFC 1123 date (the format mandated for the HTTP `Date` header). */
    internal fun parseHttpDate(value: String): Instant? =
        try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            null
        }

    companion object {
        /** Reference threshold: ≥20 s off means TOTP rejection (auth-flow.md §2.2). */
        const val MAX_SKEW_SECONDS = 20L
    }
}
