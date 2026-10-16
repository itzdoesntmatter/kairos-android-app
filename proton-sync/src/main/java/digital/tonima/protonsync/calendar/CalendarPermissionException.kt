package digital.tonima.protonsync.calendar

import android.Manifest

/**
 * Thrown when code attempts a CalendarProvider read/write without the required runtime
 * calendar permission(s) — the precondition every writer entry point enforces before touching
 * the provider.
 */
class CalendarPermissionException(
    /** The permission(s) that were missing when the operation was attempted. */
    val missingPermissions: Set<String>,
) : IllegalStateException(
        "CalendarProvider access requires calendar permissions; missing: " +
            "${missingPermissions.sorted().joinToString()}. " +
            "Request READ_CALENDAR and WRITE_CALENDAR before starting the Proton sync bridge — " +
            "without them the bridge cannot mirror events into the native calendar.",
    ) {
    companion object {
        /** Every permission the bridge needs to read and write its own calendar rows. */
        val REQUIRED_PERMISSIONS =
            setOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
    }
}
