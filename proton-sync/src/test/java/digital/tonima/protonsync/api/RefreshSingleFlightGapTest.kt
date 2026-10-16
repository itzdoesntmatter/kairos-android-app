package digital.tonima.protonsync.api

import digital.tonima.protonsync.api.dto.AddressesResponse
import digital.tonima.protonsync.api.dto.AuthInfoRequest
import digital.tonima.protonsync.api.dto.AuthInfoResponse
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.CalendarEventDetailResponse
import digital.tonima.protonsync.api.dto.CalendarEventIdsResponse
import digital.tonima.protonsync.api.dto.CalendarEventListResponse
import digital.tonima.protonsync.api.dto.CalendarKeyResponse
import digital.tonima.protonsync.api.dto.CalendarListResponse
import digital.tonima.protonsync.api.dto.CalendarPassphraseResponse
import digital.tonima.protonsync.api.dto.CalendarSettingsResponse
import digital.tonima.protonsync.api.dto.KeySaltsResponse
import digital.tonima.protonsync.api.dto.RefreshTokenRequest
import digital.tonima.protonsync.api.dto.RefreshTokenResponse
import digital.tonima.protonsync.api.dto.SrpAuthRequest
import digital.tonima.protonsync.api.dto.TwoFactorRequest
import digital.tonima.protonsync.api.dto.UserResponse
import digital.tonima.protonsync.auth.TokenRefresher
import digital.tonima.protonsync.session.SessionCrypto
import digital.tonima.protonsync.session.SessionManager
import digital.tonima.protonsync.session.SessionState
import digital.tonima.protonsync.session.SessionStore
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response as RetrofitResponse
import java.io.File
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gap-fill for [TokenRefreshAuthenticatorTest] / [digital.tonima.protonsync.auth.TokenRefresherTest]
 * (specs/test-plan.md SESS-07, API-06): the single-flight property under **real concurrency** —
 * N threads hit a 401 with the same Bearer while one refresh is in flight, and exactly one
 * `POST /auth/v4/refresh` may leave the process (auth-flow.md §3, security-audit §2.3.13: Proton
 * rotates the refresh token on every refresh, so a second concurrent refresh invalidates the
 * session). Two refreshes are also asserted to be impossible by construction here: the fake
 * client counts calls and holds the first one open long enough for the other threads to queue on
 * the mutex.
 *
 * MockWebServer is not on this module's test classpath (it was not added by wave 2a — see the
 * LOD-12 report), so the concurrency is exercised against the real [TokenRefresher] mutex and the
 * real [TokenRefreshAuthenticator] with a counting fake; no HTTP stack is faked away.
 */
class RefreshSingleFlightGapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `concurrent 401s trigger exactly one refresh and every caller replays with the new token`() {
        val api = CountingApi { freshTokens() }
        val manager = loggedIn()
        val authenticator = TokenRefreshAuthenticator(manager, TokenRefresher(api, manager))
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val replayed = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit {
                    barrier.await()
                    val request = authenticator.authenticate(null, unauthorizedResponse("Bearer stale"))
                    replayed.add(request?.header("Authorization").orEmpty())
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("exactly one refresh may be in flight process-wide", 1, api.refreshCalls.get())
        assertEquals(List(threads) { "Bearer fresh" }, replayed.toList())
        assertEquals("fresh", manager.currentAccessToken())
    }

    @Test
    fun `a 401 on a request without a bearer never triggers a refresh`() {
        val api = CountingApi { freshTokens() }
        val manager = loggedIn()
        val authenticator = TokenRefreshAuthenticator(manager, TokenRefresher(api, manager))

        assertNull(authenticator.authenticate(null, unauthorizedResponse(authorization = null)))

        assertEquals(0, api.refreshCalls.get())
        assertEquals(SessionState.ACTIVE, manager.currentState())
    }

    @Test
    fun `a failed refresh marks the session needs_reauth and aborts the chain`() {
        val api = CountingApi { throw RuntimeException("refresh rejected") }
        val manager = loggedIn()
        val authenticator = TokenRefreshAuthenticator(manager, TokenRefresher(api, manager))

        assertNull(authenticator.authenticate(null, unauthorizedResponse("Bearer stale")))

        assertEquals(1, api.refreshCalls.get())
        assertEquals(SessionState.NEEDS_REAUTH, manager.currentState())
    }

    @Test
    fun `a later 401 with the same stale token retries the refresh — observation O-2`() {
        // Observation O-2 (LOD-12, for the 2b worker): the authenticator has no circuit breaker —
        // once the state is NEEDS_REAUTH, the session still holds the old access token, so the
        // next 401 whose Bearer equals it attempts another refresh (two API calls here). The
        // architecture puts "stop retrying" on the caller (architecture §8); the worker must gate
        // on NEEDS_REAUTH before issuing more calls, otherwise a revoked session is hammered once
        // per request chain.
        val api = CountingApi { throw RuntimeException("refresh rejected") }
        val manager = loggedIn()
        val authenticator = TokenRefreshAuthenticator(manager, TokenRefresher(api, manager))

        assertNull(authenticator.authenticate(null, unauthorizedResponse("Bearer stale")))
        assertNull(authenticator.authenticate(null, unauthorizedResponse("Bearer stale")))

        assertEquals(2, api.refreshCalls.get())
    }

    private fun loggedIn(): SessionManager {
        val manager = SessionManager(SessionStore(File(tempFolder.root, "session.dat"), FakeCrypto()))
        manager.onAuthenticated(
            AuthResponse(
                code = 1000,
                accessToken = "stale",
                refreshToken = "refresh-1",
                uid = "uid",
                scope = "full",
            ),
        )
        return manager
    }

    private fun freshTokens() =
        RefreshTokenResponse(
            code = 1000,
            accessToken = "fresh",
            refreshToken = "fresh-refresh",
            uid = "uid",
            scope = "full",
        )

    private fun unauthorizedResponse(authorization: String?): Response {
        val builder = Request.Builder().url("https://mail-api.proton.me/calendar/v1")
        authorization?.let { builder.header("Authorization", it) }
        return Response.Builder()
            .request(builder.build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    /**
     * Hand-written fake of the API surface: only `refreshToken` is implemented, and it holds the
     * call open for [REFRESH_HOLD_MS] so concurrent callers really queue on the refresher's mutex.
     */
    private class CountingApi(
        private val onRefresh: () -> RefreshTokenResponse,
    ) : ProtonApiClient {

        val refreshCalls = AtomicInteger(0)

        override suspend fun refreshToken(body: RefreshTokenRequest): RefreshTokenResponse {
            refreshCalls.incrementAndGet()
            Thread.sleep(REFRESH_HOLD_MS)
            return onRefresh()
        }

        override suspend fun getAuthInfo(body: AuthInfoRequest): AuthInfoResponse = error("unused in this test")
        override suspend fun authenticate(body: SrpAuthRequest): AuthResponse = error("unused in this test")
        override suspend fun submitTwoFactor(body: TwoFactorRequest): AuthResponse = error("unused in this test")
        override suspend fun revokeSession(): RetrofitResponse<Unit> = error("unused in this test")
        override suspend fun getUsers(): UserResponse = error("unused in this test")
        override suspend fun getAddresses(): AddressesResponse = error("unused in this test")
        override suspend fun getKeySalts(): KeySaltsResponse = error("unused in this test")
        override suspend fun getCalendars(): CalendarListResponse = error("unused in this test")
        override suspend fun getCalendarKeys(calendarId: String): CalendarKeyResponse = error("unused in this test")
        override suspend fun getCalendarPassphrase(calendarId: String): CalendarPassphraseResponse =
            error("unused in this test")

        override suspend fun getCalendarSettings(calendarId: String): CalendarSettingsResponse =
            error("unused in this test")

        override suspend fun getEvents(
            calendarId: String,
            page: Int,
            pageSize: Int,
            start: Long,
            end: Long,
            timezone: String,
            type: Int,
            metaDataOnly: Int?,
        ): CalendarEventListResponse = error("unused in this test")

        override suspend fun getEventIds(calendarId: String, limit: Int, afterId: String?): CalendarEventIdsResponse =
            error("unused in this test")

        override suspend fun getEvent(calendarId: String, eventId: String): CalendarEventDetailResponse =
            error("unused in this test")

        private companion object {
            const val REFRESH_HOLD_MS = 150L
        }
    }

    private class FakeCrypto : SessionCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(7) + plaintext
        override fun decrypt(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)
    }
}
