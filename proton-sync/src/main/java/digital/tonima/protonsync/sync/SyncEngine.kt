package digital.tonima.protonsync.sync

import android.util.Log

/**
 * Wave-2 contract for the Proton sync engine (fetch → decrypt → reconcile → CalendarProvider).
 * [ProtonSyncWorker] calls only this interface, so the whole skeleton compiles and runs before
 * any Proton networking exists.
 */
interface SyncEngine {
    /** Pulls the full calendar mirror and reconciles it into the native CalendarProvider. */
    suspend fun syncFull(): SyncResult

    /** Pulls events changed since [since] (epoch millis) and reconciles them. */
    suspend fun syncIncremental(since: Long): SyncResult

    companion object {
        /**
         * Default engine until the wave-2 Proton client lands: logs a clear message and
         * reports success-with-note so scheduled work completes without spurious retries.
         */
        val NotImplemented: SyncEngine = NotImplementedSyncEngine
    }
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

private object NotImplementedSyncEngine : SyncEngine {
    private const val TAG = "ProtonSyncEngine"
    private const val NOTE =
        "The Proton sync engine is not implemented yet (it arrives with the Proton API client " +
            "in wave 2); this run intentionally synced nothing."

    override suspend fun syncFull(): SyncResult = notImplemented()

    override suspend fun syncIncremental(since: Long): SyncResult = notImplemented()

    private fun notImplemented(): SyncResult {
        Log.i(TAG, NOTE)
        return SyncResult.successWithNote(NOTE)
    }
}
