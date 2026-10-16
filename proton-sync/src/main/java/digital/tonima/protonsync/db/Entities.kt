package digital.tonima.protonsync.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entities of `proton-sync.db` (architecture §0, storage.md §3 semantics).
 *
 * CONTENT RULE (security-audit §5.2 item 8, checklist §7.10): sync METADATA only — event
 * IDs, native row IDs, tombstones, sync tags and timestamps. No column may store event
 * content, decrypted fragments (the reference DB's `cached_*` columns are explicitly
 * forbidden), tokens, keys or derived material. That rule is what keeps this database
 * plaintext-safe.
 */

/**
 * The events ↔ native rows map — the reconciliation identity store. `SYNC_DATA1` in the
 * provider mirrors [protonEventId] (sync contract I2).
 *
 * Exception rows of a recurring event are mapped here too: [isException] + [parentProtonEventId]
 * track which native rows carry the master's ORIGINAL_SYNC_ID, so master deletion can clean
 * them up even when the ids pass already forgot them.
 */
@Entity(
    tableName = "native_event_map",
    primaryKeys = ["accountName", "protonCalendarId", "protonEventId"],
)
data class NativeEventMapEntity(
    val accountName: String,
    val protonCalendarId: String,
    val protonEventId: String,
    /** Null until the first successful provider write; never a secret, just a row id. */
    val androidEventId: Long?,
    /** Server `LastEditTime` (Unix seconds) — the incremental refresh rule (sync-algorithm §3). */
    val lastModified: Long,
    val isException: Boolean,
    /** Master proton event id for recurrence-exception rows, null for masters. */
    val parentProtonEventId: String?,
)

/** Per-calendar sync state: native row binding, last-success time, last error. */
@Entity(
    tableName = "calendar_sync_state",
    primaryKeys = ["accountName", "protonCalendarId"],
)
data class CalendarSyncStateEntity(
    val accountName: String,
    val protonCalendarId: String,
    /** Native Calendars row id; null until the calendar row exists. */
    val androidCalendarId: Long?,
    val displayName: String,
    val color: Int,
    /** Last successful sync completion of this calendar (epoch ms). */
    val lastSyncEpochMs: Long,
    /** Last error message (added in schema v2); null while healthy. */
    val lastError: String?,
    /** Consecutive failure count — drives the worst-of aggregation (architecture §8). */
    val errorCount: Int,
)

/**
 * Deletion tombstone: a local row that the authoritative all-time `/events/ids` pass did not
 * list (architecture §3). Deleted only after [SUCCESSFUL_RUNS_REQUIRED] successful sync
 * runs — guards against pagination races and flaky partial lists.
 */
@Entity(
    tableName = "tombstone",
    primaryKeys = ["accountName", "protonCalendarId", "protonEventId"],
)
data class TombstoneEntity(
    val accountName: String,
    val protonCalendarId: String,
    val protonEventId: String,
    val successfulRunsSince: Int,
    val createdAtEpochMs: Long,
) {
    companion object {
        /** Deletion threshold (architecture §3: "deleted after 2 successful sync runs"). */
        const val SUCCESSFUL_RUNS_REQUIRED = 2
    }
}

/** Observability counters (architecture §8) — cheap, no analytics SDK, surfaced in settings. */
@Entity(tableName = "sync_run_stats")
data class SyncRunStatsEntity(
    @PrimaryKey val accountName: String,
    val eventsSynced: Int,
    val eventsDeleted: Int,
    val decryptFailures: Int,
    val verifyFailures: Int,
    val apiErrors: Int,
    val lastRunDurationMs: Long,
    val lastSuccessEpochMs: Long,
)
