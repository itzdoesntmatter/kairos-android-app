package digital.tonima.protonsync.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.NetworkErrorException
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * Stub authenticator (architecture §2, D2): the Android `Account` backing our calendar rows
 * exists purely for CalendarProvider ownership + logout-cleanup semantics. Login happens
 * in-app through the settings screen — never through the system account UI — so every
 * [AbstractAccountAuthenticator] method returns null/exception, exactly like the
 * reference's stub authenticator (architecture §2).
 *
 * Credentials NEVER pass through here: no password, no auth token, no userdata
 * (security-audit P-1, §2.4.17). Session material lives in the encrypted session store.
 */
class KairosProtonAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
    ): Bundle? = null

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle? = null

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle? = null

    override fun getAccountRemovalAllowed(
        response: AccountAuthenticatorResponse?,
        account: Account?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }
}

/**
 * Binds the stub authenticator to the system (architecture §1 item 3): `exported=false` with
 * only the AccountAuthenticator intent-filter — the system, not other apps, reaches it
 * (security-audit §9.7). Declared in this module's manifest.
 */
class AuthenticatorService : Service() {

    private lateinit var authenticator: KairosProtonAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = KairosProtonAuthenticator(this)
    }

    override fun onBind(intent: Intent): IBinder = authenticator.iBinder
}
