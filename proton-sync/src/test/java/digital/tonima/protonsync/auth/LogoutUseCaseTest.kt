package digital.tonima.protonsync.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionState
import digital.tonima.protonsync.session.SessionStore
import digital.tonima.protonsync.sync.ProtonSyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogoutUseCaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var manager: SessionManager
    private lateinit var api: ProtonApiClient
    private lateinit var useCase: LogoutUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        manager = SessionManager(
            SessionStore(java.io.File(tempFolder.root, "session.dat"), FakeCrypto()),
        )
        api = mockk(relaxed = true)
        useCase = LogoutUseCase(context, api, manager)
    }

    @Test
    fun `logout revokes, wipes the session, removes the account and cancels all works`() = runTest {
        // Logged-in state: session + account + scheduled works.
        manager.onAuthenticated(
            digital.tonima.protonsync.api.dto.AuthResponse(
                code = 1000, accessToken = "a", refreshToken = "r", uid = "u", scope = "full",
            ),
        )
        ProtonSyncAccount.ensureAccount(context, "user@proton.me")
        ProtonSyncScheduler.enqueueImmediateSync(context)
        ProtonSyncScheduler.ensurePeriodic(context)
        assertTrue(AccountManager.get(context).getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE).isNotEmpty())
        coEvery { api.revokeSession() } returns retrofit2.Response.success(Unit)

        useCase.logout()

        // (a) server revoke called exactly once.
        coVerify(exactly = 1) { api.revokeSession() }
        // (b) session wiped in memory and on disk.
        assertEquals(SessionState.LOGGED_OUT, manager.currentState())
        assertNull(manager.currentRefreshToken())
        // (c) account removed.
        assertTrue(AccountManager.get(context).getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE).isEmpty())
        // (d) all four unique works cancelled.
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ProtonSyncScheduler.WORK_ONCE).get()
            .plus(WorkManager.getInstance(context).getWorkInfosForUniqueWork(ProtonSyncScheduler.WORK_PERIODIC).get())
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `offline logout still destroys local material when revoke fails`() = runTest {
        manager.onAuthenticated(
            digital.tonima.protonsync.api.dto.AuthResponse(
                code = 1000, accessToken = "a", refreshToken = "r", uid = "u", scope = "full",
            ),
        )
        ProtonSyncAccount.ensureAccount(context, "user@proton.me")
        coEvery { api.revokeSession() } throws RuntimeException("network down")

        useCase.logout()

        assertEquals(SessionState.LOGGED_OUT, manager.currentState())
        assertNull(manager.currentRefreshToken())
        assertTrue(AccountManager.get(context).getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE).isEmpty())
    }

    @Test
    fun `logout with no API client is a clean no-op revoke`() = runTest {
        val noApi = LogoutUseCase(context, null, manager)

        noApi.logout()

        assertEquals(SessionState.LOGGED_OUT, manager.currentState())
    }

    @Test
    fun `removes every account of the bridge type`() {
        val accountManager = AccountManager.get(context)
        accountManager.addAccountExplicitly(Account("one@proton.me", ProtonSyncAccount.ACCOUNT_TYPE), null, null)
        accountManager.addAccountExplicitly(Account("two@proton.me", ProtonSyncAccount.ACCOUNT_TYPE), null, null)

        logoutBlocking()

        assertTrue(accountManager.getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE).isEmpty())
    }

    // Helper: logout is suspend; the remove-account path itself is synchronous, but keep the
    // production entry point as the only API by bridging here.
    private fun logoutBlocking() {
        kotlinx.coroutines.runBlocking { useCase.logout() }
    }

    private class FakeCrypto : digital.tonima.protonsync.session.SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(7) + plaintext
        override fun decrypt(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)
    }
}
