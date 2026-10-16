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
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The complete v1 Proton API surface (architecture §6), served from
 * `https://mail-api.proton.me/`. The auth-header interceptor adds `Authorization` +
 * `x-pm-uid` to every call except `/auth/v4/info` and `POST /auth/v4` (auth-flow.md §1).
 *
 * Explicitly out of v1 (architecture §6): `/calendar/v1/{id}/bootstrap`,
 * `/calendar/v1/{id}/modelevents/latest`, `PUT /calendar/v1/{id}/events/sync` (push — v1 is
 * pull-only, D3), the attendee sub-resource (declared but unused by the reference engine,
 * calendar-api.md §4.4), `/keys?Email=` (attendee public keys — push-only), and the
 * `core/v5` event stream (v2 upgrade path, architecture §7).
 */
interface ProtonApiClient {

    // ---- auth/v4 (auth-flow.md §1.1) ----

    @POST("auth/v4/info")
    suspend fun getAuthInfo(@Body body: AuthInfoRequest): AuthInfoResponse

    @POST("auth/v4")
    suspend fun authenticate(@Body body: SrpAuthRequest): AuthResponse

    @POST("auth/v4/2fa")
    suspend fun submitTwoFactor(@Body body: TwoFactorRequest): AuthResponse

    @POST("auth/v4/refresh")
    suspend fun refreshToken(@Body body: RefreshTokenRequest): RefreshTokenResponse

    /** Best-effort session revocation on logout (auth-flow.md §4). `Response<Unit>` skips body parsing. */
    @DELETE("auth/v4")
    suspend fun revokeSession(): Response<Unit>

    // ---- identity / key unlock (auth-flow.md §2.6) ----

    @GET("users")
    suspend fun getUsers(): UserResponse

    @GET("addresses")
    suspend fun getAddresses(): AddressesResponse

    @GET("core/v4/keys/salts")
    suspend fun getKeySalts(): KeySaltsResponse

    // ---- calendar family (calendar-api.md §2) ----

    @GET("calendar/v1")
    suspend fun getCalendars(): CalendarListResponse

    @GET("calendar/v1/{calendarId}/keys")
    suspend fun getCalendarKeys(@Path("calendarId") calendarId: String): CalendarKeyResponse

    @GET("calendar/v1/{calendarId}/passphrase")
    suspend fun getCalendarPassphrase(
        @Path("calendarId") calendarId: String,
    ): CalendarPassphraseResponse

    @GET("calendar/v1/{calendarId}/settings")
    suspend fun getCalendarSettings(
        @Path("calendarId") calendarId: String,
    ): CalendarSettingsResponse

    /**
     * Windowed event fetch. [start]/[end] are Unix seconds; [type] is the part bucket 0..2
     * (calendar-api.md §4.2); [metaDataOnly] trims the response for cheap scans.
     */
    @GET("calendar/v1/{calendarId}/events")
    suspend fun getEvents(
        @Path("calendarId") calendarId: String,
        @Query("Page") page: Int,
        @Query("PageSize") pageSize: Int,
        @Query("Start") start: Long,
        @Query("End") end: Long,
        @Query("Timezone") timezone: String,
        @Query("Type") type: Int,
        @Query("MetaDataOnly") metaDataOnly: Int? = null,
    ): CalendarEventListResponse

    /** All-time event IDs — the deletion authority (calendar-api.md §4.5). */
    @GET("calendar/v1/{calendarId}/events/ids")
    suspend fun getEventIds(
        @Path("calendarId") calendarId: String,
        @Query("Limit") limit: Int,
        @Query("AfterID") afterId: String? = null,
    ): CalendarEventIdsResponse

    /** Single-event re-fetch (calendar-api.md §4.4). */
    @GET("calendar/v1/{calendarId}/events/{eventId}")
    suspend fun getEvent(
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String,
    ): CalendarEventDetailResponse
}
