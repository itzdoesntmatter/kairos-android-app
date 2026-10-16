package digital.tonima.protonsync.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSkewDetectorTest {

    private val detector = ClockSkewDetector()

    // 2026-10-16T17:30:00Z in epoch millis.
    private val referenceEpochMillis = 1792171800000L

    @Test
    fun `RFC 1123 date parsing handles the standard header format`() {
        val skew = detector.isClockSkewed(
            "Fri, 16 Oct 2026 17:30:00 GMT",
            deviceEpochMillis = referenceEpochMillis,
        )

        assertFalse(skew)
    }

    @Test
    fun `a 20 second skew is reported`() {
        val skew = detector.isClockSkewed(
            "Fri, 16 Oct 2026 17:30:00 GMT",
            deviceEpochMillis = referenceEpochMillis - 20_000L,
        )

        assertTrue(skew)
    }

    @Test
    fun `a small skew is not reported`() {
        val skew = detector.isClockSkewed(
            "Fri, 16 Oct 2026 17:30:00 GMT",
            deviceEpochMillis = referenceEpochMillis + 5_000L,
        )

        assertFalse(skew)
    }

    @Test
    fun `an unparseable date header never reports skew`() {
        assertFalse(detector.isClockSkewed("not a date", 0L))
    }
}
