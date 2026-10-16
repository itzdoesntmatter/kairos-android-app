package digital.tonima.kairos.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import digital.tonima.core.service.AlarmSchedulingWorker
import digital.tonima.kairos.core.model.KairosProtonSync
import digital.tonima.kairos.ui.widget.UpcomingEventWidgetRefresh
import digital.tonima.protonsync.sync.ProtonSyncScheduler
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.ConcurrentHashMap

object CalendarChangeObserver {
    private const val UNIQUE_WORK_NAME = "phone-event-sync-onchange"
    private const val RESCHEDULE_WORK_NAME = "reschedule-alarms-onchange"
    private const val DEBOUNCE_MS = 3000L

    /** Proton onchange hook (architecture §1 item 6) — gate prefs file + keys. */
    private const val PROTON_GATE_PREFS = "kairos_proton_onchange_gate"
    private const val KEY_LAST_TRIGGER_MS = "last_trigger_epoch_ms"

    /** ME-1: ≥15 min between onchange-triggered syncs (security-audit §6.3). */
    private const val PROTON_MIN_INTERVAL_MS = 15 * 60 * 1000L

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context
    private var observer: ContentObserver? = null

    /**
     * Changed row URIs collected during the debounce window. A base-URI notification (no
     * specific row) is represented by null — attributable changes are what the Proton hook
     * examines; the existing watch-sync path fires for everything.
     */
    private val changedUris = ConcurrentHashMap.newKeySet<Uri>()

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debounceRunnable =
        Runnable {
            tryEnqueueSync()
        }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext

            // The observer callback runs on the MAIN looper, not a private HandlerThread:
            // onChange only adds to a concurrent set and re-posts the (already main-looper)
            // debounce, so a binder-thread notification is cheaply forwarded to the same
            // thread that executes the sync work — no cross-thread lag, and Robolectric can
            // drive the whole pipeline deterministically.
            val handler = Handler(Looper.getMainLooper())

            observer =
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        scheduleDebounced(uri = null)
                    }

                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        scheduleDebounced(uri)
                    }
                }

            try {
                val cr = appContext.contentResolver
                cr.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer!!)
                cr.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, observer!!)
                initialized = true
                logcat { "CalendarChangeObserver: registered content observers." }
            } catch (t: Exception) {
                logcat(
                    LogPriority.ERROR,
                ) { "CalendarChangeObserver: failed to register observer: ${t.localizedMessage}" }
            }
        }
    }

    private fun scheduleDebounced(uri: Uri?) {
        uri?.let { changedUris += it }
        debounceHandler.removeCallbacks(debounceRunnable)
        debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    private fun tryEnqueueSync() {
        UpcomingEventWidgetRefresh.refreshNow(appContext)
        val uris = changedUris.toList()
        changedUris.clear()
        val hasPerm =
            ContextCompat
                .checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_CALENDAR,
                ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            logcat(LogPriority.WARN) { "CalendarChangeObserver: READ_CALENDAR not granted; skipping immediate sync." }
            return
        }
        try {
            val constraints = Constraints.Builder().build()
            val request =
                OneTimeWorkRequestBuilder<PhoneEventSyncWorker>()
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            // Reschedule the phone's own alarms too, so an event edited, moved or deleted in the
            // calendar app does not keep ringing at its old time until the next periodic run.
            val reschedule =
                OneTimeWorkRequestBuilder<AlarmSchedulingWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                RESCHEDULE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                reschedule,
            )
            logcat { "CalendarChangeObserver: enqueued watch sync and alarm rescheduling due to calendar change." }
        } catch (t: Exception) {
            logcat(LogPriority.ERROR) { "CalendarChangeObserver: failed to enqueue sync: ${t.localizedMessage}" }
        }

        maybeEnqueueProtonSync(uris)
    }

    /**
     * The in-app Proton bridge hook (architecture §1 item 6, security-audit ME-1/ME-2):
     * enqueue `proton-sync-calendar-onchange` only when a changed row belongs to OUR account
     * type AND carries the DIRTY marker of a user edit.
     *
     * Origin filter (ME-2): every bridge write goes through the provider with
     * CALLER_IS_SYNCADAPTER=true (invariant I1), which the provider records with DIRTY=0 —
     * sync's own writes can therefore never re-trigger the hook, while a user edit in any
     * calendar app sets DIRTY=1. A deleted row (no dirty flag to read) is excluded — the
     * periodic sync re-creates it, and the min-interval gate below bounds any residual
     * feedback.
     *
     * Storm guard (ME-1): a persisted last-trigger timestamp caps onchange-triggered syncs
     * at one per 15 minutes, independent of the 3 s debounce.
     */
    private fun maybeEnqueueProtonSync(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val now = System.currentTimeMillis()
        val gatePrefs = appContext.getSharedPreferences(PROTON_GATE_PREFS, Context.MODE_PRIVATE)
        if (now - gatePrefs.getLong(KEY_LAST_TRIGGER_MS, 0L) < PROTON_MIN_INTERVAL_MS) return
        if (!uris.any { isDirtyProtonChange(it) }) return
        gatePrefs.edit().putLong(KEY_LAST_TRIGGER_MS, now).apply()
        ProtonSyncScheduler.enqueueOnChangeSync(appContext)
        logcat { "CalendarChangeObserver: enqueued proton onchange sync for a user-edited Proton row." }
    }

    /** True when [uri] resolves to a row of our account type whose DIRTY flag is 1. */
    private fun isDirtyProtonChange(uri: Uri): Boolean {
        val eventId = resolveEventId(uri) ?: return false
        val resolver = appContext.contentResolver
        val cursor =
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                "${CalendarContract.Events.DIRTY}=1 AND ${BaseColumns._ID}=? AND " +
                    "${CalendarContract.Events.CALENDAR_ID} IN (SELECT ${BaseColumns._ID} FROM " +
                    // The CalendarProvider SQLite table name (CalendarDatabaseHelper.TABLES_CALENDARS);
                    // CalendarContract exposes no TABLE_NAME constant.
                    "Calendars WHERE " +
                    "${CalendarContract.Calendars.ACCOUNT_TYPE}=?)",
                arrayOf(eventId.toString(), KairosProtonSync.ACCOUNT_TYPE),
                null,
            ) ?: return false
        cursor.use { return it.moveToFirst() }
    }

    /** Maps a changed row URI to its Events row id, across the two observed tables. */
    private fun resolveEventId(uri: Uri): Long? {
        val segments = uri.pathSegments
        val resolver = appContext.contentResolver
        return when {
            // Events (or a child table like .../events/<id>/attendees): the event id is the
            // segment right after "events".
            segments.contains("events") -> {
                val index = segments.indexOf("events")
                segments.getOrNull(index + 1)?.toLongOrNull()
            }
            // Instances: the changed row is an INSTANCE row; its EVENT_ID is the event.
            segments.contains("instances") -> {
                val index = segments.indexOf("instances")
                val instanceId = segments.getOrNull(index + 1)?.toLongOrNull() ?: return null
                resolver
                    .query(
                        CalendarContract.Instances.CONTENT_URI,
                        arrayOf(CalendarContract.Instances.EVENT_ID),
                        "${BaseColumns._ID}=?",
                        arrayOf(instanceId.toString()),
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID))
                        } else {
                            null
                        }
                    }
            }
            else -> null
        }
    }
}
