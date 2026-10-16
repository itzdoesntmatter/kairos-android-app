package digital.tonima.protonsync.account

import android.accounts.AccountManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProtonSyncAccountTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `ensureAccount registers the account without storing credentials`() {
        val account = ProtonSyncAccount.ensureAccount(context, "alice@proton.me")

        assertEquals("alice@proton.me", account.name)
        assertEquals(ProtonSyncAccount.ACCOUNT_TYPE, account.type)
        assertEquals(1, accounts().size)
        assertNull(AccountManager.get(context).getPassword(account))
    }

    @Test
    fun `ensureAccount is idempotent for the same email`() {
        val first = ProtonSyncAccount.ensureAccount(context, "alice@proton.me")
        val second = ProtonSyncAccount.ensureAccount(context, "alice@proton.me")

        assertEquals(first, second)
        assertEquals(1, accounts().size)
    }

    @Test
    fun `ensureAccount keeps distinct accounts for distinct emails`() {
        ProtonSyncAccount.ensureAccount(context, "alice@proton.me")
        ProtonSyncAccount.ensureAccount(context, "bob@protonmail.com")

        assertEquals(
            setOf("alice@proton.me", "bob@protonmail.com"),
            accounts().map { it.name }.toSet(),
        )
    }

    @Test
    fun `findAccount and hasAccount reflect registration`() {
        assertNull(ProtonSyncAccount.findAccount(context))
        assertFalse(ProtonSyncAccount.hasAccount(context))

        ProtonSyncAccount.ensureAccount(context, "alice@proton.me")

        assertEquals("alice@proton.me", ProtonSyncAccount.findAccount(context)?.name)
        assertTrue(ProtonSyncAccount.hasAccount(context))
    }

    @Test
    fun `ensureAccount rejects a blank account name`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtonSyncAccount.ensureAccount(context, " ")
        }
    }

    private fun accounts() = AccountManager.get(context).getAccountsByType(ProtonSyncAccount.ACCOUNT_TYPE)
}
