package digital.tonima.protonsync.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAOs of the sync-metadata database. All queries are account-scoped even though v1 has a
 * single account (D4) — the schema reserves the UID dimension so multi-account is not a
 * migration cliff (architecture §0).
 */
@Dao
interface NativeEventMapDao {
    @Query(
        "SELECT * FROM native_event_map WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId",
    )
    suspend fun byCalendar(accountName: String, protonCalendarId: String): List<NativeEventMapEntity>

    @Query(
        "SELECT * FROM native_event_map WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId AND protonEventId = :protonEventId",
    )
    suspend fun find(
        accountName: String,
        protonCalendarId: String,
        protonEventId: String,
    ): NativeEventMapEntity?

    @Upsert
    suspend fun upsert(entries: List<NativeEventMapEntity>)

    /** Binds the native row id after a successful provider insert (post-write bookkeeping). */
    @Query(
        "UPDATE native_event_map SET androidEventId = :androidEventId " +
            "WHERE accountName = :accountName AND protonCalendarId = :protonCalendarId " +
            "AND protonEventId = :protonEventId",
    )
    suspend fun bindAndroidEventId(
        accountName: String,
        protonCalendarId: String,
        protonEventId: String,
        androidEventId: Long,
    )

    @Query(
        "DELETE FROM native_event_map WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId AND protonEventId = :protonEventId",
    )
    suspend fun delete(
        accountName: String,
        protonCalendarId: String,
        protonEventId: String,
    )

    @Query(
        "DELETE FROM native_event_map WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId AND parentProtonEventId = :masterId",
    )
    suspend fun deleteExceptions(
        accountName: String,
        protonCalendarId: String,
        masterId: String,
    )

    @Query(
        "DELETE FROM native_event_map WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId",
    )
    suspend fun deleteAllForCalendar(accountName: String, protonCalendarId: String)

    @Query("DELETE FROM native_event_map WHERE accountName = :accountName")
    suspend fun deleteAllForAccount(accountName: String)
}

@Dao
interface CalendarSyncStateDao {
    @Query(
        "SELECT * FROM calendar_sync_state WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId",
    )
    suspend fun find(accountName: String, protonCalendarId: String): CalendarSyncStateEntity?

    @Query("SELECT * FROM calendar_sync_state WHERE accountName = :accountName")
    suspend fun byAccount(accountName: String): List<CalendarSyncStateEntity>

    @Upsert
    suspend fun upsert(state: CalendarSyncStateEntity)

    @Query(
        "UPDATE calendar_sync_state SET androidCalendarId = :androidCalendarId " +
            "WHERE accountName = :accountName AND protonCalendarId = :protonCalendarId",
    )
    suspend fun bindAndroidCalendarId(
        accountName: String,
        protonCalendarId: String,
        androidCalendarId: Long,
    )

    @Query(
        "UPDATE calendar_sync_state SET lastSyncEpochMs = :epochMs, errorCount = 0, lastError = NULL " +
            "WHERE accountName = :accountName AND protonCalendarId = :protonCalendarId",
    )
    suspend fun markSuccess(
        accountName: String,
        protonCalendarId: String,
        epochMs: Long,
    )

    @Query(
        "UPDATE calendar_sync_state SET errorCount = errorCount + 1, lastError = :message " +
            "WHERE accountName = :accountName AND protonCalendarId = :protonCalendarId",
    )
    suspend fun recordError(
        accountName: String,
        protonCalendarId: String,
        message: String,
    )

    @Query(
        "DELETE FROM calendar_sync_state WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId",
    )
    suspend fun deleteForCalendar(accountName: String, protonCalendarId: String)

    @Query("DELETE FROM calendar_sync_state WHERE accountName = :accountName")
    suspend fun deleteAllForAccount(accountName: String)
}

@Dao
interface TombstoneDao {
    @Query(
        "SELECT * FROM tombstone WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId",
    )
    suspend fun byCalendar(accountName: String, protonCalendarId: String): List<TombstoneEntity>

    @Upsert
    suspend fun upsert(tombstone: TombstoneEntity)

    @Query(
        "DELETE FROM tombstone WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId AND protonEventId = :protonEventId",
    )
    suspend fun delete(
        accountName: String,
        protonCalendarId: String,
        protonEventId: String,
    )

    @Query(
        "DELETE FROM tombstone WHERE accountName = :accountName " +
            "AND protonCalendarId = :protonCalendarId",
    )
    suspend fun deleteAllForCalendar(accountName: String, protonCalendarId: String)

    @Query("DELETE FROM tombstone WHERE accountName = :accountName")
    suspend fun deleteAllForAccount(accountName: String)
}

@Dao
interface SyncRunStatsDao {
    @Query("SELECT * FROM sync_run_stats WHERE accountName = :accountName")
    suspend fun find(accountName: String): SyncRunStatsEntity?

    @Upsert
    suspend fun upsert(stats: SyncRunStatsEntity)

    @Query("DELETE FROM sync_run_stats WHERE accountName = :accountName")
    suspend fun deleteAllForAccount(accountName: String)
}
