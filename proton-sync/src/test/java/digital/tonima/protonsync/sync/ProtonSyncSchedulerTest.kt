package digital.tonima.protonsync.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import digital.tonima.protonsync.account.ProtonSyncAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Scheduler contract tests (architecture §7): the configurable cadence (operator decision
 * `cadence: configurable`) reads `sync_interval_hours` from the bridge DataStore (default
 * 6 h), the logged-out guards stay strict no-ops, the onchange hook is REPLACE + logged-out
 * no-op, and logout cancels all four unique works (security-audit S-6).
 */
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
    fun `ensurePeriodic enqueues at the configured cadence - default 6h`() = runBlocking {
        registerAccount()
        val request = ProtonSyncScheduler.ensurePeriodicNow(context)!!

        assertEquals(TimeUnit.HOURS.toMillis(6), request.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, infos(ProtonSyncScheduler.WORK_PERIODIC).single().state)
    }

    @Test
    fun `the configured cadence overrides the default`() = runBlocking {
        registerAccount()
        ProtonSyncPreferences.setSyncIntervalHours(context, 1)

        val request = ProtonSyncScheduler.ensurePeriodicNow(context)!!

        assertEquals(TimeUnit.HOURS.toMillis(1), request.workSpec.intervalDuration)
        assertEquals(1, ProtonSyncPreferences.syncIntervalHours(context).first())
    }

    @Test
    fun `a corrupted stored interval falls back to the default`() = runBlocking {
        registerAccount()
        context.protonSyncDataStore.edit {
            it[ProtonSyncPreferences.SYNC_INTERVAL_HOURS] = 999
        }

        val request = ProtonSyncScheduler.ensurePeriodicNow(context)!!

        assertEquals(TimeUnit.HOURS.toMillis(6), request.workSpec.intervalDuration)
    }

    @Test
    fun `ensurePeriodic keeps the existing schedule on repeat calls`() = runBlocking {
        registerAccount()
        ProtonSyncScheduler.ensurePeriodicNow(context)
        val first = infos(ProtonSyncScheduler.WORK_PERIODIC).single()

        ProtonSyncScheduler.ensurePeriodicNow(context)

        val after = infos(ProtonSyncScheduler.WORK_PERIODIC).single()
        assertEquals(first.id, after.id)
    }

    @Test
    fun `reapplyInterval replaces the periodic work with the new cadence`() = runBlocking {
        registerAccount()
        ProtonSyncScheduler.ensurePeriodicNow(context)
        val first = infos(ProtonSyncScheduler.WORK_PERIODIC).single()
        ProtonSyncPreferences.setSyncIntervalHours(context, 24)

        val request = ProtonSyncScheduler.reapplyInterval(context)!!

        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.intervalDuration)
        val after = infos(ProtonSyncScheduler.WORK_PERIODIC).single()
        assertEquals(1, infos(ProtonSyncScheduler.WORK_PERIODIC).size)
        org.junit.Assert.assertNotEquals(first.id, after.id)
    }

    @Test
    fun `ensurePeriodic enqueues nothing for logged-out users`() = runBlocking {
        assertNull(ProtonSyncScheduler.ensurePeriodicNow(context))
        assertTrue(infos(ProtonSyncScheduler.WORK_PERIODIC).isEmpty())
    }

    @Test
    fun `enqueueImmediateSync enqueues one-shot connected work and replaces pendings`() {
        val request = ProtonSyncScheduler.enqueueImmediateSync(context)
        ProtonSyncScheduler.enqueueImmediateSync(context)

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(1, infos(ProtonSyncScheduler.WORK_ONCE).size)
    }

    @Test
    fun `onchange sync is a logged-out no-op and enqueues when connected`() {
        assertNull(ProtonSyncScheduler.enqueueOnChangeSync(context))
        assertTrue(infos(ProtonSyncScheduler.WORK_ON_CHANGE).isEmpty())

        registerAccount()
        ProtonSyncScheduler.enqueueOnChangeSync(context)
        ProtonSyncScheduler.enqueueOnChangeSync(context)

        assertEquals(1, infos(ProtonSyncScheduler.WORK_ON_CHANGE).size)
    }

    @Test
    fun `cancelAll cancels every unique work name`() = runBlocking {
        registerAccount()
        ProtonSyncScheduler.ensurePeriodicNow(context)
        ProtonSyncScheduler.enqueueImmediateSync(context)
        ProtonSyncScheduler.enqueueOnChangeSync(context)

        ProtonSyncScheduler.cancelAll(context)

        // TestWorkManager keeps cancelled work as WorkInfo records, so the assertion is on
        // state, not absence: nothing may remain enqueued/running after logout (S-6).
        assertTrue(infos(ProtonSyncScheduler.WORK_PERIODIC).all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(infos(ProtonSyncScheduler.WORK_ONCE).all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(infos(ProtonSyncScheduler.WORK_ON_CHANGE).all { it.state == WorkInfo.State.CANCELLED })
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
