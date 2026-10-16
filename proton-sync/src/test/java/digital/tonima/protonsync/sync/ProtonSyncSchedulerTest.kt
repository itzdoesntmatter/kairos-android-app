package digital.tonima.protonsync.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import digital.tonima.protonsync.account.ProtonSyncAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProtonSyncSchedulerTest {
    private lateinit var context: Application
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `ensurePeriodic enqueues unique periodic work with a 15-minute connected constraint`() {
        registerAccount()

        val request = ProtonSyncScheduler.ensurePeriodic(context)!!

        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, infos(ProtonSyncScheduler.WORK_PERIODIC).single().state)
    }

    @Test
    fun `ensurePeriodic keeps the existing schedule on repeat calls`() {
        registerAccount()
        ProtonSyncScheduler.ensurePeriodic(context)
        val first = infos(ProtonSyncScheduler.WORK_PERIODIC).single()

        ProtonSyncScheduler.ensurePeriodic(context)

        val after = infos(ProtonSyncScheduler.WORK_PERIODIC).single()
        assertEquals(first.id, after.id)
    }

    @Test
    fun `ensurePeriodic enqueues nothing for logged-out users`() {
        assertNull(ProtonSyncScheduler.ensurePeriodic(context))
        assertTrue(infos(ProtonSyncScheduler.WORK_PERIODIC).isEmpty())
    }

    @Test
    fun `enqueueImmediateSync enqueues one-shot connected work`() {
        val request = ProtonSyncScheduler.enqueueImmediateSync(context)

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, infos(ProtonSyncScheduler.WORK_ONCE).single().state)
    }

    @Test
    fun `enqueueImmediateSync replaces a previous pending immediate sync`() {
        ProtonSyncScheduler.enqueueImmediateSync(context)
        ProtonSyncScheduler.enqueueImmediateSync(context)

        assertEquals(1, infos(ProtonSyncScheduler.WORK_ONCE).size)
    }

    private fun registerAccount() {
        AccountManager.get(context).addAccountExplicitly(
            Account("alice@proton.me", ProtonSyncAccount.ACCOUNT_TYPE),
            null,
            null,
        )
    }

    private fun infos(uniqueName: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(uniqueName).get(5, TimeUnit.SECONDS)
}
