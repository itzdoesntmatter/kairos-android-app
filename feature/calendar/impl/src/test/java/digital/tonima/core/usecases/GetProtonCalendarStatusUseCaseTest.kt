package digital.tonima.core.usecases

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import digital.tonima.core.data.repository.CalendarRepository
import digital.tonima.kairos.core.model.ProtonCalendarStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GetProtonCalendarStatusUseCaseTest {
    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()
    private val calendarRepository = mockk<CalendarRepository>()

    private val useCase = GetProtonCalendarStatusUseCase(context, calendarRepository)

    @Before
    fun setUp() {
        every { context.packageManager } returns packageManager
    }

    @Test
    fun `no Proton app, no bridge and no visible calendar`() =
        runTest {
            stubAllPackagesMissing()
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(false, false, false), useCase())
        }

    @Test
    fun `Proton Calendar app installed but no bridge and no visible calendar`() =
        runTest {
            every { packageManager.getPackageInfo(ProtonPackages.PROTON_CALENDAR, 0) } returns mockk<PackageInfo>()
            every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[0], 0) } throws
                PackageManager.NameNotFoundException()
            every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[1], 0) } throws
                PackageManager.NameNotFoundException()
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(true, false, false), useCase())
        }

    @Test
    fun `sync bridge installed and Proton calendar visible`() =
        runTest {
            every { packageManager.getPackageInfo(ProtonPackages.PROTON_CALENDAR, 0) } throws
                PackageManager.NameNotFoundException()
            every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[0], 0) } returns mockk<PackageInfo>()
            every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[1], 0) } throws
                PackageManager.NameNotFoundException()
            coEvery { calendarRepository.hasProtonCalendar() } returns true

            assertEquals(ProtonCalendarStatus(false, true, true), useCase())
        }

    @Test
    fun `any installed bridge marks syncBridgeInstalled`() =
        runTest {
            every { packageManager.getPackageInfo(ProtonPackages.PROTON_CALENDAR, 0) } throws
                PackageManager.NameNotFoundException()
            every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[0], 0) } throws
                PackageManager.NameNotFoundException()
            every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[1], 0) } returns mockk<PackageInfo>()
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(false, true, false), useCase())
        }

    private fun stubAllPackagesMissing() {
        every { packageManager.getPackageInfo(ProtonPackages.PROTON_CALENDAR, 0) } throws
            PackageManager.NameNotFoundException()
        every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[0], 0) } throws
            PackageManager.NameNotFoundException()
        every { packageManager.getPackageInfo(ProtonPackages.SYNC_BRIDGES[1], 0) } throws
            PackageManager.NameNotFoundException()
    }
}
