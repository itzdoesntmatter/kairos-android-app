package digital.tonima.protonsync.sync

import android.content.Context
import android.content.pm.PackageManager
import digital.tonima.protonsync.account.ProtonSyncAccount
import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.ProtonApiClientFactory
import digital.tonima.protonsync.api.ProtonClientIdentity
import digital.tonima.protonsync.auth.LoginFlow
import digital.tonima.protonsync.auth.LogoutUseCase
import digital.tonima.protonsync.auth.SrpClient
import digital.tonima.protonsync.auth.TokenRefresher
import digital.tonima.protonsync.calendar.NativeCalendarWriter
import digital.tonima.protonsync.calendar.ProtonCalendarWriter
import digital.tonima.protonsync.crypto.EventPartDecryptor
import digital.tonima.protonsync.crypto.KeysStore
import digital.tonima.protonsync.crypto.ProtonCorePgpCrypto
import digital.tonima.protonsync.crypto.ProtonCryptoKeyring
import digital.tonima.protonsync.db.SyncDatabase
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionStore
import digital.tonima.protonsync.status.ProtonSyncStatusProvider

/**
 * The bridge's process-wide object graph (manual DI — architecture §1 keeps Hilt out of
 * `:proton-sync`). Everything the worker, the engine and the settings UI share is a
 * singleton here: one SessionManager (the OkHttp authenticator, the engine and the login
 * flow must agree on the session), one keyring, one status provider, one database.
 *
 * [reset] wipes the graph (tests; logout hygiene destroys the underlying state itself).
 */
object ProtonSyncModule {

    @Volatile private var sessionManager: SessionManager? = null
    @Volatile private var statusProvider: ProtonSyncStatusProvider? = null
    @Volatile private var keyring: ProtonCryptoKeyring? = null
    @Volatile private var keysStore: KeysStore? = null
    @Volatile private var apiClient: ProtonApiClient? = null
    @Volatile private var database: SyncDatabase? = null
    @Volatile private var engine: SyncEngine? = null
    @Volatile private var initializedSession = false
    private val lock = Any()

    fun statusProvider(context: Context): ProtonSyncStatusProvider {
        statusProvider?.let { return it }
        synchronized(lock) {
            statusProvider?.let { return it }
            return ProtonSyncStatusProvider().also { statusProvider = it }
        }
    }

    fun sessionManager(context: Context): SessionManager {
        sessionManager?.let { return it }
        synchronized(lock) {
            sessionManager?.let { return it }
            val manager = SessionManager(SessionStore.create(context))
            if (!initializedSession) {
                manager.initialize()
                initializedSession = true
            }
            sessionManager = manager
            return manager
        }
    }

    fun keysStore(context: Context): KeysStore {
        keysStore?.let { return it }
        synchronized(lock) {
            keysStore?.let { return it }
            return KeysStore.create(context).also { keysStore = it }
        }
    }

    fun keyring(context: Context): ProtonCryptoKeyring {
        keyring?.let { return it }
        synchronized(lock) {
            keyring?.let { return it }
            return ProtonCryptoKeyring(ProtonCorePgpCrypto(), keysStore(context)).also { keyring = it }
        }
    }

    fun apiClient(context: Context): ProtonApiClient {
        apiClient?.let { return it }
        synchronized(lock) {
            apiClient?.let { return it }
            val manager = sessionManager(context)
            val factory = ProtonApiClientFactory(identity(context))
            // Construction cycle break: the refresher's own /auth/v4/refresh rides on the
            // bare client (same headers/pinning/rate limiting, no 401 authenticator); the
            // full client then carries the single-flight authenticator (see
            // ProtonApiClientFactory.createBare).
            val bare = factory.createBare(manager)
            val client = factory.create(
                sessionManager = manager,
                tokenRefresher = TokenRefresher(api = bare, sessionManager = manager),
            )
            apiClient = client
            return client
        }
    }

    fun database(context: Context): SyncDatabase {
        database?.let { return it }
        synchronized(lock) {
            database?.let { return it }
            return SyncDatabase.create(context).also { database = it }
        }
    }

    /** Builds the production engine for [context] (the worker's default engine). */
    fun buildEngine(context: Context): SyncEngine {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            val account = ProtonSyncAccount.findAccount(context)
                ?: return NoSessionEngine(statusProvider(context))
            val manager = sessionManager(context)
            val ring = keyring(context)
            val api = apiClient(context)
            val built =
                ProtonSyncEngine(
                    accountName = account.name,
                    api = api,
                    sessionManager = manager,
                    keyring = ring,
                    decryptor = EventPartDecryptor(ProtonCorePgpCrypto(), ring),
                    parser = IcalEventParser(),
                    writer = NativeCalendarWriter(context),
                    db = database(context),
                    statusProvider = statusProvider(context),
                )
            engine = built
            return built
        }
    }

    fun buildLoginFlow(context: Context): LoginFlow =
        LoginFlow(
            context = context,
            api = apiClient(context),
            srpClient = SrpClient(),
            sessionManager = sessionManager(context),
            keyring = keyring(context),
            statusProvider = statusProvider(context),
        )

    fun buildLogoutUseCase(context: Context): LogoutUseCase =
        LogoutUseCase(
            context = context,
            api = apiClient(context),
            sessionManager = sessionManager(context),
            keysStore = keysStore(context),
            keyring = keyring(context),
            statusProvider = statusProvider(context),
            database = database(context),
        )

    /** Test seam and logout hygiene: drops every cached dependency. */
    fun reset() {
        synchronized(lock) {
            keyring?.close()
            sessionManager = null
            statusProvider = null
            keyring = null
            keysStore = null
            apiClient = null
            database = null
            engine = null
            initializedSession = false
        }
    }

    private fun identity(context: Context): ProtonClientIdentity {
        val info =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        return ProtonClientIdentity(
            versionName = info?.versionName ?: "unknown",
            versionCode = (info?.versionCode ?: 0).toLong(),
        )
    }

    /**
     * Engine substitute for the logged-out race (the worker checks the account first, this
     * is the belt-and-braces path): reports success so a stray schedule cannot retry-storm.
     */
    private class NoSessionEngine(
        private val statusProvider: ProtonSyncStatusProvider,
    ) : SyncEngine {
        override suspend fun syncFull(): SyncResult {
            statusProvider.onNeedsReauth()
            return SyncResult.successWithNote("no Proton account — nothing to sync")
        }

        override suspend fun syncIncremental(since: Long): SyncResult = syncFull()
    }
}
