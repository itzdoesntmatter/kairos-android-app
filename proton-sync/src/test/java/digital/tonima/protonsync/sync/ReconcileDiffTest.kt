package digital.tonima.protonsync.sync

import digital.tonima.protonsync.db.NativeEventMapEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReconcileDiff matrices (architecture §3, sync-algorithm §3): the LastEditTime refresh
 * rule — no local row → insert; stored version older than the server's → update; equal or
 * newer → skip. Deletion is deliberately NOT part of this diff (the /events/ids pass owns
 * it through tombstones).
 */
class ReconcileDiffTest {

    private val diff = ReconcileDiff()

    private fun mapRow(
        eventId: String,
        lastModified: Long,
    ) = NativeEventMapEntity(
        accountName = "a@proton.me",
        protonCalendarId = "cal-1",
        protonEventId = eventId,
        androidEventId = 1L,
        lastModified = lastModified,
        isException = false,
        parentProtonEventId = null,
    )

    @Test
    fun `events with no local row are inserts`() {
        val plan = diff.plan(mapOf("evt-1" to 100L, "evt-2" to 200L), emptyList())

        assertEquals(listOf("evt-1", "evt-2"), plan.inserts)
        assertTrue(plan.updates.isEmpty())
        assertEquals(listOf("evt-1", "evt-2"), plan.touched)
    }

    @Test
    fun `a newer server LastEditTime is an update`() {
        val plan = diff.plan(mapOf("evt-1" to 101L), listOf(mapRow("evt-1", 100L)))

        assertEquals(listOf("evt-1"), plan.updates)
        assertTrue(plan.inserts.isEmpty())
    }

    @Test
    fun `an equal or older server LastEditTime is skipped`() {
        val plan = diff.plan(mapOf("evt-1" to 100L, "evt-2" to 50L), listOf(mapRow("evt-1", 100L), mapRow("evt-2", 99L)))

        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.touched.isEmpty())
    }

    @Test
    fun `mixed matrix - insert update and skip together`() {
        val plan =
            diff.plan(
                mapOf(
                    "new" to 500L,
                    "changed" to 123L,
                    "unchanged" to 42L,
                ),
                listOf(
                    mapRow("changed", 100L),
                    mapRow("unchanged", 42L),
                ),
            )

        assertEquals(listOf("new"), plan.inserts)
        assertEquals(listOf("changed"), plan.updates)
    }

    @Test
    fun `zero LastEditTime still triggers an insert for unknown events`() {
        val plan = diff.plan(mapOf("evt-1" to 0L), emptyList())

        assertEquals(listOf("evt-1"), plan.inserts)
    }
}
