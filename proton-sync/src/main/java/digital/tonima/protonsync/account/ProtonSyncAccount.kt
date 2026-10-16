package digital.tonima.protonsync.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import digital.tonima.kairos.core.model.KairosProtonSync

/**
 * Owns the Android account that backs every native calendar row written by the in-app Proton
 * sync bridge.
 *
 * Why [ACCOUNT_TYPE] exists and why it is exactly "digital.tonima.kairos.proton":
 *
 * 1. CalendarProvider rows must belong to an account: every Calendars row carries ACCOUNT_NAME
 *    + ACCOUNT_TYPE, and the provider purges all rows of an account when that account is
 *    removed. Registering a real account therefore buys logout cleanup for free (architecture
 *    D2) instead of a manual row sweep on disconnect.
 * 2. It must not be [android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL]: local-account
 *    calendars are the device-local "Kairos Imports" family the app already creates, and
 *    Proton-owned rows must stay distinguishable from those.
 * 3. It must not be a third-party account type ("com.google" etc.): the bridge is
 *    self-contained and never speaks the sync-adapter framework, so no other package must be
 *    able to claim these rows.
 * 4. The value is part of the sync contract (architecture spec §0). Renaming it orphans every
 *    row the old type owns — migrate instead of rename if it ever has to change.
 *
 * Token safety: this class only registers the account shell. It stores no password and never
 * touches AccountManager's auth-token store; Proton session material (refresh token, unlocked
 * key material) lives in the wave-2 encrypted session store, away from AccountManager.
 */
object ProtonSyncAccount {
    /** The sync contract constant (architecture §0), single-sourced in core:model. */
    const val ACCOUNT_TYPE = KairosProtonSync.ACCOUNT_TYPE

    /** Returns the single Proton account, or null while logged out (v1: one account, D4). */
    fun findAccount(context: Context): Account? =
        AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE).firstOrNull()

    fun hasAccount(context: Context): Boolean = findAccount(context) != null

    /**
     * Registers the account for [accountName] (the user's Proton email) when absent and
     * returns it. Idempotent: repeated calls return the existing account and never create a
     * duplicate. The password is intentionally null — credentials never pass through the
     * system account store.
     */
    fun ensureAccount(
        context: Context,
        accountName: String,
    ): Account {
        require(accountName.isNotBlank()) { "accountName must be the user's Proton email" }
        val manager = AccountManager.get(context)
        manager.getAccountsByType(ACCOUNT_TYPE).firstOrNull { it.name == accountName }?.let { return it }
        manager.addAccountExplicitly(Account(accountName, ACCOUNT_TYPE), null, null)
        return requireNotNull(
            manager.getAccountsByType(ACCOUNT_TYPE).firstOrNull { it.name == accountName },
        ) { "AccountManager refused to register the Proton sync account for $accountName" }
    }
}
