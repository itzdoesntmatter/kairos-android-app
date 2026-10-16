package digital.tonima.core.usecases

/**
 * Package names and endpoints involved in Proton Calendar integration.
 *
 * Single source of truth shared by the status use case (detection) and the calendar screens
 * (launching the right app / opening the install page).
 */
object ProtonPackages {
    /** Proton Calendar's Android app. */
    const val PROTON_CALENDAR = "me.proton.android.calendar"

    /**
     * Third-party sync bridges that mirror Proton Calendar into the system calendar provider,
     * which is the only way Kairós can read Proton events. Ordered by preference.
     */
    val SYNC_BRIDGES =
        listOf(
            "com.protosync.app", // ProtonSync (open source)
            "me.proton.sync", // Sync Provider for Proton
        )

    /** Where to send a user who has no sync bridge installed yet. */
    const val SYNC_BRIDGE_URL = "https://github.com/carlostkd/ProtonSync"
}
