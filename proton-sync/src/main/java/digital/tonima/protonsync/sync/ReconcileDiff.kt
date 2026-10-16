package digital.tonima.protonsync.sync

import digital.tonima.protonsync.db.NativeEventMapEntity

/**
 * Pure reconciliation of the server's event list against the local map (architecture §3,
 * sync-algorithm §3): decides per event whether to insert, update-in-place, or leave it
 * alone. No provider access, no API access — fully unit-testable.
 *
 * Rules implemented:
 * - No local map row → INSERT (first sighting of the event).
 * - `stored.lastModified < server.lastEditTime` → UPDATE (server changed it; the refresh
 *   rule is LastEditTime-based like the reference — sync-algorithm §3).
 * - Everything else → SKIP (cheap protection against provider churn).
 *
 * Deletion authority is NOT part of this diff: the all-time `/events/ids` pass drives
 * deletions through the [TombstoneEntity] state machine (architecture §3), because the
 * windowed body fetch is not proof that an event was deleted.
 */
class ReconcileDiff {

    fun plan(
        serverEvents: Map<String, Long>,
        localMap: List<NativeEventMapEntity>,
    ): DiffPlan {
        val localByEventId = localMap.associateBy { it.protonEventId }
        val inserts = mutableListOf<String>()
        val updates = mutableListOf<String>()
        for ((eventId, lastEditTime) in serverEvents) {
            val local = localByEventId[eventId]
            when {
                local == null -> inserts += eventId
                local.lastModified < lastEditTime -> updates += eventId
                else -> Unit
            }
        }
        return DiffPlan(inserts = inserts, updates = updates)
    }
}

/** The engine's to-do list for one calendar. */
data class DiffPlan(
    /** Server event IDs with no local row (and no tombstone) — insert them. */
    val inserts: List<String>,
    /** Server event IDs whose stored version is older than the server's — re-apply them. */
    val updates: List<String>,
) {
    /** Everything the engine must (re)write this run. */
    val touched: List<String> get() = inserts + updates
}
