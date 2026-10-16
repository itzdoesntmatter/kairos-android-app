package digital.tonima.kairos.core.model

/**
 * Describes how Proton Calendar relates to this device from Kairós' point of view.
 *
 * Proton Calendar never writes its events into Android's system calendar provider, so a
 * third-party sync bridge (ProtonSync, Sync Provider for Proton, …) is required before its
 * events become visible to any app that reads [android.provider.CalendarContract].
 */
data class ProtonCalendarStatus(
    val protonCalendarAppInstalled: Boolean,
    val syncBridgeInstalled: Boolean,
    val protonCalendarVisible: Boolean,
)
