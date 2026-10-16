package digital.tonima.protonsync.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The bridge's DataStore: `proton-sync.preferences_pb` (architecture §0, §7). Holds the
 * user-configurable sync cadence — non-secret preferences only; NO session material ever
 * lands here (security-audit §5.1).
 */
internal val Context.protonSyncDataStore by preferencesDataStore(name = "proton-sync")

object ProtonSyncPreferences {

    /** sync_interval_hours ∈ {1, 6, 12, 24}, default 6 (operator decision `cadence: configurable`). */
    val SYNC_INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")

    /** Consent flag for the calendar-readability notice (security-audit HI-2/ME-5). */
    val CONSENT_GRANTED = "consent_granted"

    /** Minimum WorkManager periodic floor guards against a corrupt stored value. */
    private const val MIN_INTERVAL_MINUTES = 15L

    private val consentKey = androidx.datastore.preferences.core.booleanPreferencesKey(CONSENT_GRANTED)

    fun syncIntervalHours(context: Context): Flow<Int> =
        context.protonSyncDataStore.data.map { prefs ->
            val stored = prefs[SYNC_INTERVAL_HOURS] ?: DEFAULT_INTERVAL_HOURS
            if (stored in ALLOWED_INTERVALS) stored else DEFAULT_INTERVAL_HOURS
        }

    suspend fun setSyncIntervalHours(
        context: Context,
        hours: Int,
    ) {
        require(hours in ALLOWED_INTERVALS) { "sync interval must be one of $ALLOWED_INTERVALS" }
        context.protonSyncDataStore.edit { it[SYNC_INTERVAL_HOURS] = hours }
    }

    fun consentGranted(context: Context): Flow<Boolean> =
        context.protonSyncDataStore.data.map { it[consentKey] ?: false }

    suspend fun setConsentGranted(
        context: Context,
        granted: Boolean,
    ) {
        context.protonSyncDataStore.edit { it[consentKey] = granted }
    }

    /** WorkManager interval for [hours], clamped to the 15-minute framework floor. */
    fun intervalMinutes(hours: Int): Long =
        (hours * 60L).coerceAtLeast(MIN_INTERVAL_MINUTES)

    const val DEFAULT_INTERVAL_HOURS = 6

    val ALLOWED_INTERVALS = setOf(1, 6, 12, 24)
}
