package digital.tonima.protonsync.sync

/**
 * Contract for the Proton sync engine (fetch → decrypt → reconcile → CalendarProvider).
 * [ProtonSyncWorker] calls only this interface; the production implementation is
 * [ProtonSyncEngine].
 */
interface SyncEngine {
    /** Pulls the full calendar mirror and reconciles it into the native CalendarProvider. */
    suspend fun syncFull(): SyncResult

    /**
     * Pulls events since [since] (epoch millis, the last-success cursor) and reconciles
     * them. A zero cursor behaves as a full sync (first run after login).
     */
    suspend fun syncIncremental(since: Long): SyncResult
}

/** Outcome of one sync pass, aggregated by the engine across calendars. */
data class SyncResult(
    val status: Status,
    val note: String? = null,
    val eventsSynced: Int = 0,
    val eventsDeleted: Int = 0,
) {
    enum class Status {
        /** Everything asked for was synced. */
        SUCCESS,

        /** Synced, but with a caveat worth surfacing (see [SyncResult.note]). */
        SUCCESS_WITH_NOTE,

        /** The run failed; the worker decides whether retrying makes sense. */
        FAILURE,
    }

    companion object {
        fun success(
            eventsSynced: Int = 0,
            eventsDeleted: Int = 0,
        ) = SyncResult(Status.SUCCESS, eventsSynced = eventsSynced, eventsDeleted = eventsDeleted)

        fun successWithNote(
            note: String,
            eventsSynced: Int = 0,
            eventsDeleted: Int = 0,
        ) = SyncResult(Status.SUCCESS_WITH_NOTE, note, eventsSynced, eventsDeleted)

        fun failure(note: String) = SyncResult(Status.FAILURE, note)
    }
}
