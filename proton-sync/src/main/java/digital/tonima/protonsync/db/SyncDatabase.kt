package digital.tonima.protonsync.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * `proton-sync.db` — the sync-metadata Room database (architecture §0). Metadata only: event
 * ID map, tombstones, per-calendar state, observability counters. NO secrets, NO event
 * content (security-audit §5.2; enforced by schema review, checklist §7.10).
 *
 * Migration strategy (storage.md §3 semantics, architecture §5.1): explicit, tested
 * migrations only; [MIGRATION_1_2] is the pattern all future migrations follow. A schema
 * change with no migration entry fails loudly at open time (Room default) rather than
 * silently dropping metadata — a destructive fallback is deliberately NOT wired, because
 * silently losing the event-ID map would orphan provider rows. If a destructive reset ever
 * becomes necessary (e.g. an incompatible pre-release schema), it will be an explicit,
 * logged, release-notes item — not a default.
 *
 * Schema history is exported by KSP to `proton-sync/schemas/` and fed to the migration test
 * as fixtures.
 */
@Database(
    entities = [
        NativeEventMapEntity::class,
        CalendarSyncStateEntity::class,
        TombstoneEntity::class,
        SyncRunStatsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun nativeEventMapDao(): NativeEventMapDao

    abstract fun calendarSyncStateDao(): CalendarSyncStateDao

    abstract fun tombstoneDao(): TombstoneDao

    abstract fun syncRunStatsDao(): SyncRunStatsDao

    companion object {
        const val DATABASE_NAME = "proton-sync.db"

        /**
         * v1 → v2: `calendar_sync_state` gains the nullable `lastError` column (per-calendar
         * error surface, architecture §8). Data-preserving: existing rows keep all columns,
         * the new column starts NULL. The v1 schema never shipped to production — this
         * migration is the executed proof of the migration strategy.
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE calendar_sync_state ADD COLUMN lastError TEXT",
                    )
                }
            }

        fun create(context: Context): SyncDatabase =
            Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
