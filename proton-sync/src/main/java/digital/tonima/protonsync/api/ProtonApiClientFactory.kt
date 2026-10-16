package digital.tonima.protonsync.api

import android.os.Build
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import digital.tonima.protonsync.auth.TokenRefresher
import digital.tonima.protonsync.session.SessionManager
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Honest client identity (invariant I6, security-audit §4.5): the app version comes from the
 * caller (the app module's real versionName/versionCode) — there is deliberately no silent
 * default, because a fabricated identity would violate both the ToS posture and the hard rule
 * against impersonation. Proton's own client identifiers are never used.
 */
data class ProtonClientIdentity(
    val versionName: String,
    val versionCode: Long,
) {
    /** `x-pm-appversion` value — the truthful third-party pattern. */
    val appVersionHeader: String = "Kairos_$versionCode"

    /** `User-Agent` per architecture §6 / security-audit §4.5. */
    val userAgent: String =
        "Kairos/$versionName (Android ${Build.VERSION.SDK_INT}; ${Build.MODEL})"
}

/**
 * Builds the Proton API stack: Retrofit 3 + OkHttp 5 with certificate pinning, the common
 * header interceptor, the single-flight 401 refresh authenticator, and the 429 backoff
 * policy (architecture §6).
 *
 * Certificate pinning (security-audit §4.1): SPKI SHA-256 pins for the live
 * `mail-api.proton.me` chain — leaf, issuing intermediate, and root (3 pins, so a CA
 * rollover degrades to an update instead of a hard break). Pins were extracted from the live
 * TLS chain at implementation time (cleanroom-safe public observation):
 *
 * ```
 * openssl s_client -connect mail-api.proton.me:443 -servername mail-api.proton.me -showcerts
 * openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
 * ```
 *
 * Pin rotation ships via app update; a pin failure surfaces as a distinct
 * `CertificatePinner` failure (non-retryable, logged by OkHttp) — never silently bypassed.
 * DoH is disabled by design (architecture §6); no trust-manager overrides anywhere.
 */
class ProtonApiClientFactory(
    private val identity: ProtonClientIdentity,
) {

    fun create(
        sessionManager: SessionManager,
        tokenRefresher: TokenRefresher,
    ): ProtonApiClient =
        buildClient(
            sessionManager = sessionManager,
            authenticator =
                TokenRefreshAuthenticator(
                    sessionManager = sessionManager,
                    tokenRefresher = tokenRefresher,
                ),
        )

    /**
     * The same stack WITHOUT the 401 authenticator — used to break the construction cycle
     * between the API client and its [TokenRefresher]: the refresher's own `auth/v4/refresh`
     * call rides on this client (same headers, rate limiting and pinning; refresh never
     * needs the authenticator — a rejected refresh means `needs_reauth`, not a retry).
     */
    fun createBare(sessionManager: SessionManager): ProtonApiClient =
        buildClient(sessionManager = sessionManager, authenticator = null)

    private fun buildClient(
        sessionManager: SessionManager,
        authenticator: okhttp3.Authenticator?,
    ): ProtonApiClient {
        val json =
            Json {
                ignoreUnknownKeys = true
                // Requests carry mandatory defaulted wire fields (Intent, GrantType, …) —
                // kotlinx omits default values unless told otherwise.
                encodeDefaults = true
            }

        val okHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // No automatic retry on connection failure: a half-committed auth POST must
                // never be re-sent; 5xx/429 retries live at the interceptor/worker level
                // (security-audit §4.6, architecture §8).
                .retryOnConnectionFailure(false)
                .certificatePinner(
                    CertificatePinner.Builder()
                        .add("mail-api.proton.me", SPKI_PIN_LEAF)
                        .add("mail-api.proton.me", SPKI_PIN_INTERMEDIATE)
                        .add("mail-api.proton.me", SPKI_PIN_ROOT)
                        .build(),
                )
                .addInterceptor(
                    ProtonHeadersInterceptor(
                        sessionManager = sessionManager,
                        appVersionHeader = identity.appVersionHeader,
                        userAgent = identity.userAgent,
                    ),
                )
                .addInterceptor(RateLimitInterceptor())
                // Response-body cap (security-audit §3.5b / N-1). Added after the rate-limit
                // policy, i.e. closer to the network, so it caps each individual attempt rather
                // than a re-read of an already-buffered response.
                .addInterceptor(ResponseSizeLimitInterceptor())
                .apply {
                    if (authenticator != null) authenticator(authenticator)
                }
                .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ProtonApiClient::class.java)
    }

    companion object {
        const val BASE_URL = "https://mail-api.proton.me/"

        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L

        /**
         * Live-chain SPKI SHA-256 pins for mail-api.proton.me, captured 2026-10-16:
         * leaf `proton.me` (Let's Encrypt YR2), intermediate `Let's Encrypt YR2`, and root
         * `ISRG Root YR`. The leaf rotates quarterly — pin expiry is diagnosable and ships a
         * fix via app update (accepted tradeoff, security-audit §4.1).
         */
        const val SPKI_PIN_LEAF = "sha256/CT56BhOTmj5ZIPgb/xD5mH8rY3BLo/MlhP7oPyJUEDo="
        const val SPKI_PIN_INTERMEDIATE = "sha256/nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E="
        const val SPKI_PIN_ROOT = "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="
    }
}
