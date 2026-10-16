package digital.tonima.protonsync.status

import digital.tonima.protonsync.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for the settings card (architecture §8): session state, sync state,
 * last-sync time, per-calendar errors and the observability counters. A plain StateFlow
 * holder — the engine writes it, the settings UI collects it.
 *
 * User-visible states (§8): Disconnected / Connected · Last sync 12:34 / Syncing… /
 * Error: <reason> / Session expired. The card is the only notification surface (no
 * notifications, no analytics SDK).
 */
data class ProtonSyncUiState(
    val accountEmail: String? = null,
    val sessionState: SessionState = SessionState.LOGGED_OUT,
    val syncState: SyncState = SyncState.IDLE,
    val lastSyncEpochMs: Long? = null,
    val lastError: String? = null,
    val counters: SyncCounters = SyncCounters(),
    val calendarErrors: List<CalendarSyncError> = emptyList(),
)

/** Engine state shown by the settings card (architecture §8 aggregate). */
enum class SyncState {
    /** No run in flight; last run (if any) succeeded. */
    IDLE,

    /** A run is executing right now. */
    SYNCING,

    /** The last run failed with a retryable error ([ProtonSyncUiState.lastError]). */
    ERROR,

    /** The session is unrecoverable — the user must log in again. */
    NEEDS_REAUTH,
}

/** Observability counters (architecture §8), persisted in Room and mirrored here. */
data class SyncCounters(
    val eventsSynced: Int = 0,
    val eventsDeleted: Int = 0,
    val decryptFailures: Int = 0,
    val verifyFailures: Int = 0,
    val apiErrors: Int = 0,
    val lastRunDurationMs: Long = 0,
)

/** One failing calendar (partial-sync policy, invariant I4). */
data class CalendarSyncError(
    val calendarId: String,
    val message: String,
)

class ProtonSyncStatusProvider {

    private val mutableState = MutableStateFlow(ProtonSyncUiState())

    /** The settings card's state stream. */
    val state: StateFlow<ProtonSyncUiState> = mutableState.asStateFlow()

    /** Session observation for the settings VM (security-audit: session state is observable). */
    fun onSessionChanged(sessionState: SessionState, accountEmail: String?) {
        mutableState.update { current ->
            current.copy(
                sessionState = sessionState,
                accountEmail = accountEmail ?: current.accountEmail,
            )
        }
    }

    fun onSyncStarted() {
        mutableState.update { it.copy(syncState = SyncState.SYNCING) }
    }

    fun onSyncCompleted(
        lastError: String?,
        counters: SyncCounters,
        calendarErrors: List<CalendarSyncError>,
    ) {
        mutableState.update {
            it.copy(
                syncState = if (lastError == null) SyncState.IDLE else SyncState.ERROR,
                lastError = lastError,
                lastSyncEpochMs = if (lastError == null) System.currentTimeMillis() else it.lastSyncEpochMs,
                counters = counters,
                calendarErrors = calendarErrors,
            )
        }
    }

    fun onNeedsReauth() {
        mutableState.update { it.copy(syncState = SyncState.NEEDS_REAUTH, lastError = null) }
    }

    fun onLoggedOut() {
        mutableState.value = ProtonSyncUiState()
    }
}
