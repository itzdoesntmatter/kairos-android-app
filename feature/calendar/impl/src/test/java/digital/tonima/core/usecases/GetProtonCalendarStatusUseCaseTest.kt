package digital.tonima.core.usecases

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import digital.tonima.core.data.repository.CalendarRepository
import digital.tonima.kairos.core.model.KairosProtonSync
import digital.tonima.kairos.core.model.ProtonCalendarStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Proton-calendar status probe. Since wave 2b it reports a fourth fact — whether the
 * IN-APP sync bridge is connected, which the bridge defines as "an Android account of
 * [KairosProtonSync.ACCOUNT_TYPE] exists" (architecture §2) — so the suite runs on a real
 * (Robolectric) Application: the account lookup and the `PackageManager.NameNotFoundException`
 * handling are exactly the behaviour under test, not something to mock away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GetProtonCalendarStatusUseCaseTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val calendarRepository = mockk<CalendarRepository>()

    private val useCase = GetProtonCalendarStatusUseCase(context, calendarRepository)

    private fun installPackage(packageName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { this.packageName = packageName },
        )
    }

    @Test
    fun `no Proton app, no bridge and no visible calendar`() =
        runTest {
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(false, false, false, false), useCase())
        }

    @Test
    fun `Proton Calendar app installed but no bridge and no visible calendar`() =
        runTest {
            installPackage(ProtonPackages.PROTON_CALENDAR)
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(true, false, false, false), useCase())
        }

    @Test
    fun `sync bridge installed and Proton calendar visible`() =
        runTest {
            installPackage(ProtonPackages.SYNC_BRIDGES[0])
            coEvery { calendarRepository.hasProtonCalendar() } returns true

            assertEquals(ProtonCalendarStatus(false, true, true, false), useCase())
        }

    @Test
    fun `any installed bridge marks syncBridgeInstalled`() =
        runTest {
            installPackage(ProtonPackages.SYNC_BRIDGES[1])
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(false, true, false, false), useCase())
        }

    @Test
    fun `a Kairos Proton account marks the in-app bridge as connected`() =
        runTest {
            assertTrue(
                AccountManager
                    .get(context)
                    .addAccountExplicitly(Account("me@proton.me", KairosProtonSync.ACCOUNT_TYPE), null, null),
            )
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(false, false, false, true), useCase())
        }

    @Test
    fun `an account of a foreign type does not count as the in-app bridge`() =
        runTest {
            AccountManager
                .get(context)
                .addAccountExplicitly(Account("me@example.com", "com.example.other"), null, null)
            coEvery { calendarRepository.hasProtonCalendar() } returns false

            assertEquals(ProtonCalendarStatus(false, false, false, false), useCase())
        }
}
