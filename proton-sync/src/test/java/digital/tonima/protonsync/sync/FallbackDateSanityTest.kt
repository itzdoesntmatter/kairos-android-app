package digital.tonima.protonsync.sync

import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.calendar.ProtonEventData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Date sanity on the metadata-only paths (security-audit §3.5c, finding N-4): the parse path
 * already enforced 1970-2100 and end-after-start, but the unparseable-payload fallback and the
 * all-parts-failed placeholder multiplied the server's raw seconds straight into the writer —
 * no bounds, and a Long overflow at extreme values. These cases pin the shared rule: seconds
 * are checked before the multiply, and a range that cannot be made sane marks the event
 * undated so the engine skips it instead of writing a fabricated row.
 */
class FallbackDateSanityTest {

    private val parser = IcalEventParser(ZoneId.of("UTC"))

    private fun detail(
        startTime: Long? = null,
        endTime: Long? = null,
        fullDay: Int = 0,
        uid: String? = null,
    ) = CalendarEventDetail(
        id = "evt-1",
        startTime = startTime,
        endTime = endTime,
        fullDay = fullDay,
        uid = uid,
    )

    /** Both metadata-only paths: the unparseable-payload fallback and the placeholder. */
    private fun fallbacks(detail: CalendarEventDetail): List<ProtonEventData> =
        listOf(
            parser.parse("NOT ICAL AT ALL", detail, "me@proton.me"),
            parser.placeholder(detail, "me@proton.me"),
        )

    @Test
    fun `sane metadata still passes through unchanged`() {
        for (event in fallbacks(detail(startTime = 1_000L, endTime = 2_000L, uid = "u"))) {
            assertEquals(1_000_000L, event.startMillis)
            assertEquals(2_000_000L, event.endMillis)
            assertEquals("u", event.icalUid)
            assertFalse(event.isUndated)
        }
    }

    @Test
    fun `a missing end degrades to a zero-length event, not a rejection`() {
        for (event in fallbacks(detail(startTime = 1_000L))) {
            assertEquals(1_000_000L, event.startMillis)
            assertEquals(1_000_000L, event.endMillis)
            assertFalse(event.isUndated)
        }
    }

    @Test
    fun `an end before the start is clamped instead of written as an inverted range`() {
        for (event in fallbacks(detail(startTime = 2_000L, endTime = 1_000L))) {
            assertEquals(2_000_000L, event.startMillis)
            assertEquals(2_000_000L, event.endMillis)
            assertFalse(event.isUndated)
        }
    }

    @Test
    fun `negative metadata seconds are refused`() {
        for (event in fallbacks(detail(startTime = -1L, endTime = 1_000L))) {
            assertTrue(event.isUndated)
            assertEquals(0L, event.startMillis)
        }
    }

    @Test
    fun `an absurd start with no usable end marks the event undated`() {
        // Year ~95 million: the ×1000 multiply would wrap Long and write a negative instant.
        for (event in fallbacks(detail(startTime = 3_000_000_000_000_000L))) {
            assertTrue(event.isUndated)
            assertEquals(0L, event.startMillis)
            assertEquals(0L, event.endMillis)
        }
    }

    @Test
    fun `extreme Long metadata cannot overflow into a negative range`() {
        for (event in fallbacks(detail(startTime = Long.MAX_VALUE, endTime = Long.MAX_VALUE))) {
            assertTrue(event.isUndated)
            assertEquals(0L, event.startMillis)
            assertEquals(0L, event.endMillis)
        }
    }

    @Test
    fun `metadata beyond 2100 is refused`() {
        val year2200 = 7_258_118_400L // 2200-01-01T00:00:00Z in seconds

        for (event in fallbacks(detail(startTime = year2200, endTime = year2200 + 3_600L))) {
            assertTrue(event.isUndated)
        }
    }

    @Test
    fun `a sane end does not rescue an unusable start`() {
        // Without a usable start there is no place on the timeline for the event: it is marked
        // undated (and skipped) rather than pinned to a made-up instant.
        val event = parser.placeholder(detail(startTime = -5L, endTime = 2_000L), "me@proton.me")

        assertTrue(event.isUndated)
        assertEquals(0L, event.startMillis)
    }

    @Test
    fun `a missing start with metadata present is undated`() {
        for (event in fallbacks(detail(endTime = 2_000L))) {
            assertTrue(event.isUndated)
        }
    }

    @Test
    fun `the placeholder title and metadata payload are unchanged`() {
        val event = parser.placeholder(detail(startTime = 1_000L, endTime = 2_000L), "me@proton.me")

        assertEquals(IcalEventParser.PLACEHOLDER_TITLE, event.title)
        assertEquals(null, event.description)
        assertTrue(event.attendees.isEmpty())
    }

    @Test
    fun `the parse path still prefers the payload dates over the metadata`() {
        val ical = "SUMMARY:Team sync\r\nDTSTART:20261016T090000Z\r\nDTEND:20261016T100000Z"

        val event = parser.parse(ical, detail(startTime = 1_000L, endTime = 2_000L), "me@proton.me")

        assertFalse(event.isUndated)
        assertTrue(event.startMillis > 2_000_000L)
    }
}
