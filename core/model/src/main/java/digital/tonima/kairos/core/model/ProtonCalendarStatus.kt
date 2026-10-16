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
    /**
     * True when the in-app bridge is connected: an Android account of type
     * [KairosProtonSync.ACCOUNT_TYPE] exists (architecture §2 — the account IS the
     * connection state). Replaces the external-bridge package probe for the in-app path.
     */
    val inAppBridgeConnected: Boolean = false,
)
