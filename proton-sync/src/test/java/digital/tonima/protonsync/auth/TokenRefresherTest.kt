package digital.tonima.protonsync.auth

import digital.tonima.protonsync.api.ProtonApiClient
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.RefreshTokenResponse
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionState
import digital.tonima.protonsync.session.SessionStore
import digital.tonima.protonsync.session.StoredSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TokenRefresherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: SessionStore
    private lateinit var manager: SessionManager
    private lateinit var api: ProtonApiClient
    private lateinit var refresher: TokenRefresher

    @Before
    fun setUp() {
        store = SessionStore(File(tempFolder.root, "session.dat"), FakeCrypto())
        manager = SessionManager(store)
        api = mockk(relaxed = true)
        refresher = TokenRefresher(api, manager)
    }

    @Test
    fun `refreshIfNeeded is a no-op while an access token is in memory`() = runTest {
        login()

        assertTrue(refresher.refreshIfNeeded())
        coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    @Test
    fun `refreshIfNeeded rebuilds the access token from the stored refresh token`() = runTest {
        store.save(StoredSession(uid = "uid", refreshToken = "refresh", scope = "full"))
        manager.initialize()
        coEvery { api.refreshToken(any()) } returns
            RefreshTokenResponse(
                code = 1000,
                accessToken = "new-access",
                refreshToken = "new-refresh",
                uid = "uid",
                scope = "full",
            )

        assertTrue(refresher.refreshIfNeeded())

        assertEquals("new-access", manager.currentAccessToken())
        assertEquals(StoredSession("uid", "new-refresh", "full"), store.load())
    }

    @Test
    fun `a 401 refresh is single-flight — concurrent callers issue one API call`() = runTest {
        store.save(StoredSession(uid = "uid", refreshToken = "refresh", scope = "full"))
        manager.initialize()
        coEvery { api.refreshToken(any()) } coAnswers {
            delay(50)
            RefreshTokenResponse(
                code = 1000,
                accessToken = "new",
                refreshToken = "new-refresh",
                uid = "uid",
                scope = "full",
            )
        }

        val first = async { refresher.refreshAfterUnauthorized(null) }
        val second = async { refresher.refreshAfterUnauthorized(null) }
        assertTrue(first.await())
        assertTrue(second.await())

        coVerify(exactly = 1) { api.refreshToken(any()) }
    }

    @Test
    fun `a caller with a stale token dedupes when another thread already refreshed`() = runTest {
        login()
        val staleToken = manager.currentAccessToken()
        // Simulate the other thread's rotation:
        manager.onRefreshed(
            RefreshTokenResponse(
                code = 1000,
                accessToken = "rotated",
                refreshToken = "rotated-refresh",
                uid = "uid",
                scope = "full",
            ),
        )

        assertTrue(refresher.refreshAfterUnauthorized(staleToken))
        coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    @Test
    fun `failure marks the session needs_reauth`() = runTest {
        store.save(StoredSession(uid = "uid", refreshToken = "refresh", scope = "full"))
        manager.initialize()
        coEvery { api.refreshToken(any()) } throws RuntimeException("network down")

        assertFalse(refresher.refreshIfNeeded())
        assertEquals(SessionState.NEEDS_REAUTH, manager.currentState())
    }

    @Test
    fun `a non-success code marks needs_reauth`() = runTest {
        store.save(StoredSession(uid = "uid", refreshToken = "refresh", scope = "full"))
        manager.initialize()
        coEvery { api.refreshToken(any()) } returns RefreshTokenResponse(code = 1001)

        assertFalse(refresher.refreshIfNeeded())
        assertEquals(SessionState.NEEDS_REAUTH, manager.currentState())
    }

    @Test
    fun `a narrowed scope on refresh marks needs_reauth and keeps old tokens`() = runTest {
        store.save(StoredSession(uid = "uid", refreshToken = "refresh", scope = "full"))
        manager.initialize()
        coEvery { api.refreshToken(any()) } returns
            RefreshTokenResponse(code = 1000, accessToken = "new", refreshToken = "new-r", uid = "uid", scope = "mail")

        assertFalse(refresher.refreshIfNeeded())

        assertEquals(SessionState.NEEDS_REAUTH, manager.currentState())
        assertFalse(manager.currentAccessToken() == "new")
    }

    @Test
    fun `refreshing with no stored session fails fast`() = runTest {
        assertFalse(refresher.refreshIfNeeded())
        coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    private fun login() {
        assertTrue(
            manager.onAuthenticated(
                AuthResponse(
                    code = 1000,
                    accessToken = "access",
                    refreshToken = "refresh",
                    uid = "uid",
                    scope = "full",
                ),
            ),
        )
    }

    /** Session crypto test double that never touches AndroidKeystore. */
    private class FakeCrypto : digital.tonima.protonsync.session.SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(7) + plaintext
        override fun decrypt(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)
    }
}
