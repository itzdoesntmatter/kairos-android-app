package digital.tonima.core.usecases

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import digital.tonima.core.data.repository.CalendarRepository
import digital.tonima.kairos.core.model.ProtonCalendarStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports how Proton Calendar relates to this device so the UI can decide whether to guide the
 * user toward installing a sync bridge.
 *
 * Proton Calendar never writes its events into the system calendar provider, so a Proton user
 * whose events are not yet visible needs a third-party bridge before Kairós can see them.
 */
@Singleton
class GetProtonCalendarStatusUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val calendarRepository: CalendarRepository,
    ) {
        suspend operator fun invoke(): ProtonCalendarStatus =
            ProtonCalendarStatus(
                protonCalendarAppInstalled = isPackageInstalled(ProtonPackages.PROTON_CALENDAR),
                syncBridgeInstalled = ProtonPackages.SYNC_BRIDGES.any { isPackageInstalled(it) },
                protonCalendarVisible = calendarRepository.hasProtonCalendar(),
            )

        private fun isPackageInstalled(packageName: String): Boolean =
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
    }
