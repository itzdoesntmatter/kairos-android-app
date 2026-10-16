package digital.tonima.protonsync.calendar

import android.provider.CalendarContract

/**
 * The writer port the sync engine programs against (architecture §3): everything that
 * mirrors Proton data into the native CalendarProvider, abstracted so the engine is
 * unit-testable without a real provider. [NativeCalendarWriter] is the production
 * implementation; the contract is its documentation plus this surface.
 */
interface ProtonCalendarWriter {

    /**
     * Creates or updates the native calendar row hosting one Proton calendar. Identity is
     * (ACCOUNT_NAME, ACCOUNT_TYPE, NAME); returns the native calendar id.
     *
     * [accessLevel] follows the reference's proven split (sync-algorithm §6.2): CAL_ACCESS_OWNER
     * for personal calendars and members with permission bit 16, CAL_ACCESS_READ otherwise.
     */
    fun ensureCalendar(
        accountName: String,
        calendarName: String,
        displayName: String,
        color: Int? = null,
        accessLevel: Int = CalendarContract.Calendars.CAL_ACCESS_OWNER,
    ): Long

    /** Deletes one bridge calendar row plus everything inside it. */
    fun deleteCalendar(
        accountName: String,
        calendarId: Long,
    ): Boolean

    /** Deletes every calendar row owned by [accountName] (belt-and-braces logout sweep). */
    fun deleteCalendarsForAccount(accountName: String): List<Long>

    /**
     * Inserts or updates the native row(s) for one Proton event (master + its exception
     * rows) and returns the native event id. Idempotent: same payload twice writes once.
     */
    fun upsertEvent(
        accountName: String,
        calendarId: Long,
        data: ProtonEventData,
    ): Long

    /** Deletes one Proton event's native rows — master, exception rows and instances (I3). */
    fun deleteEvent(
        accountName: String,
        calendarId: Long,
        protonEventId: String,
    ): Boolean

    /**
     * Propagates the calendar's default notifications to previously-synced events of this
     * calendar that have no reminders of their own (sync-algorithm §2k, architecture §3).
     * Idempotent: events that already carry any reminder are untouched.
     */
    fun applyDefaultReminders(
        accountName: String,
        calendarId: Long,
        reminders: List<Reminder>,
    )
}
