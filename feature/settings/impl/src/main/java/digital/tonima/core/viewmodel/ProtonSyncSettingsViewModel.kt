package digital.tonima.core.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import digital.tonima.protonsync.auth.HvUrlPolicy
import digital.tonima.protonsync.auth.HvUrlResolver
import digital.tonima.protonsync.auth.LoginFlow
import digital.tonima.protonsync.status.ProtonSyncStatusProvider
import digital.tonima.protonsync.status.ProtonSyncUiState
import digital.tonima.protonsync.sync.ProtonSyncModule
import digital.tonima.protonsync.sync.ProtonSyncPreferences
import digital.tonima.protonsync.sync.ProtonSyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the "Calendar sync" settings section (architecture §4, §8): login flow state,
 * session observation, the consent gate (security-audit HI-2/ME-5), logout (S-6), manual
 * sync and the configurable cadence (operator decision `cadence: configurable`).
 *
 * Passwords enter here as CharArray (converted from the TextField String), are passed to
 * [LoginFlow] and zeroized as soon as the call returns — they never live in [LoginUiState].
 */
data class LoginUiState(
    val showingLogin: Boolean = false,
    val email: String = "",
    val twoFactorCode: String = "",
    val mailboxPassword: String = "",
    val requiresTwoFactor: Boolean = false,
    val requiresMailboxPassword: Boolean = false,
    val hvUrl: String? = null,
    /** The redirect chain of [hvUrl] is being resolved/validated (security-audit §2.2.22). */
    val hvResolving: Boolean = false,
    /**
     * The validated URL a Custom Tab may open: set once the resolver has re-checked every
     * redirect hop against the allowlist, cleared as soon as the launcher consumed it.
     */
    val hvResolvedUrl: String? = null,
    val consentChecked: Boolean = false,
    val consentGranted: Boolean = false,
    val inFlight: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ProtonSyncSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val application: Context,
    ) : ViewModel() {

    private val statusProvider: ProtonSyncStatusProvider = ProtonSyncModule.statusProvider(application)
    private val loginFlow: LoginFlow = ProtonSyncModule.buildLoginFlow(application)

    private val mutableLoginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = mutableLoginState.asStateFlow()

    /** The settings card's session/sync status (session state observation, architecture §4). */
    val status: StateFlow<ProtonSyncUiState> = statusProvider.state

    init {
        viewModelScope.launch {
            val consent = ProtonSyncPreferences.consentGranted(application).first()
            mutableLoginState.update { it.copy(consentGranted = consent, consentChecked = consent) }
            // Re-sync the displayed session state from the singleton after process restarts.
            val current = status.value
            statusProvider.onSessionChanged(
                current.sessionState,
                current.accountEmail,
            )
        }
    }

    fun openLogin() {
        mutableLoginState.update { it.copy(showingLogin = true, error = null) }
    }

    fun dismissLogin() {
        loginFlow.cancel()
        mutableLoginState.update {
            it.copy(
                showingLogin = false,
                error = null,
                requiresTwoFactor = false,
                requiresMailboxPassword = false,
                hvUrl = null,
                hvResolving = false,
                hvResolvedUrl = null,
                twoFactorCode = "",
                mailboxPassword = "",
            )
        }
    }

    fun onEmailChanged(email: String) = mutableLoginState.update { it.copy(email = email) }

    fun onTwoFactorCodeChanged(code: String) = mutableLoginState.update { it.copy(twoFactorCode = code) }

    fun onMailboxPasswordChanged(password: String) = mutableLoginState.update { it.copy(mailboxPassword = password) }

    fun onConsentChanged(checked: Boolean) {
        mutableLoginState.update { it.copy(consentChecked = checked) }
        if (checked) setConsentGranted()
    }

    fun setConsentGranted() {
        mutableLoginState.update { it.copy(consentGranted = true, consentChecked = true) }
        viewModelScope.launch { ProtonSyncPreferences.setConsentGranted(application, true) }
    }

    /** Step 1: email + password (+ the consent gate enforced by the UI before this call). */
    fun submitLogin(passwordString: String) {
        val state = mutableLoginState.value
        if (state.inFlight || state.email.isBlank() || passwordString.isBlank()) return
        val password = passwordString.toCharArray()
        mutableLoginState.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            val outcome = loginFlow.submitCredentials(state.email, password)
            password.fill('\u0000')
            handleOutcome(outcome)
        }
    }

    fun submitTwoFactor() {
        val state = mutableLoginState.value
        if (state.inFlight || state.twoFactorCode.isBlank()) return
        mutableLoginState.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            val outcome = loginFlow.submitTwoFactor(state.twoFactorCode)
            handleOutcome(outcome)
        }
    }

    fun submitMailboxPassword() {
        val state = mutableLoginState.value
        if (state.inFlight || state.mailboxPassword.isBlank()) return
        val password = state.mailboxPassword.toCharArray()
        mutableLoginState.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            val outcome = loginFlow.submitMailboxPassword(password)
            password.fill('\u0000')
            handleOutcome(outcome)
        }
    }

    /**
     * Human verification, step 1 (security-audit §2.2.22 / release-gate C2): resolve the
     * challenge URL's redirect chain and re-check every hop against the allowlist before
     * anything can reach a Custom Tab. The validated URL lands in [LoginUiState.hvResolvedUrl]
     * for the launcher; a refusal lands in the error line and nothing opens.
     *
     * If the chain cannot be resolved at all (network trouble), the original URL is still
     * allowlist-checked and may open — a flaky network must not dead-end an HV-gated login.
     */
    fun resolveHvUrl() {
        val state = mutableLoginState.value
        val url = state.hvUrl ?: return
        if (state.hvResolving) return
        mutableLoginState.update { it.copy(hvResolving = true, hvResolvedUrl = null, error = null) }
        viewModelScope.launch {
            val resolution = ProtonSyncModule.hvUrlResolver(application).resolve(url)
            mutableLoginState.update { current ->
                when (resolution) {
                    is HvUrlResolver.Resolution.Resolved ->
                        current.copy(hvResolving = false, hvResolvedUrl = resolution.url, error = null)
                    is HvUrlResolver.Resolution.Refused ->
                        current.copy(hvResolving = false, hvResolvedUrl = null, error = resolution.reason)
                    is HvUrlResolver.Resolution.Unresolved ->
                        current.copy(
                            hvResolving = false,
                            hvResolvedUrl = url.takeIf { HvUrlPolicy.isAllowed(it) },
                            error = null,
                        )
                }
            }
        }
    }

    /** The launcher took [LoginUiState.hvResolvedUrl] — consumed so recomposition cannot re-open it. */
    fun onHvUrlOpened() = mutableLoginState.update { it.copy(hvResolving = false, hvResolvedUrl = null) }

    /** The launcher's own allowlist re-check refused the URL at the point of use (§2.2.22). */
    fun onHvLaunchBlocked() =
        mutableLoginState.update {
            it.copy(
                hvResolving = false,
                hvResolvedUrl = null,
                error = "Refusing to open human verification on an untrusted host.",
            )
        }

    /**
     * Human verification, step 2: the user solved the challenge in the browser and came back.
     * The HV token was adopted in memory when the challenge was parsed ([LoginFlow] set it on
     * the single-use SessionManager slot — it is never typed by the user and never persisted);
     * this replays the auth call, which sends it once and consumes it.
     */
    fun resumeAfterHv() {
        if (mutableLoginState.value.inFlight) return
        mutableLoginState.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            handleOutcome(loginFlow.resumeWithHvToken())
        }
    }

    fun logout() {
        viewModelScope.launch {
            ProtonSyncModule.buildLogoutUseCase(application).logout()
            ProtonSyncModule.reset()
            mutableLoginState.update {
                LoginUiState(consentGranted = it.consentGranted)
            }
        }
    }

    fun syncNow() {
        ProtonSyncScheduler.enqueueImmediateSync(application)
    }

    fun setSyncInterval(hours: Int) {
        viewModelScope.launch {
            ProtonSyncPreferences.setSyncIntervalHours(application, hours)
            ProtonSyncScheduler.reapplyInterval(application)
        }
    }

    private fun handleOutcome(outcome: LoginFlow.LoginOutcome) {
        mutableLoginState.update { current ->
            when (outcome) {
                is LoginFlow.LoginOutcome.Success ->
                    current.copy(
                        inFlight = false,
                        showingLogin = false,
                        error = null,
                        requiresTwoFactor = false,
                        requiresMailboxPassword = false,
                        twoFactorCode = "",
                        mailboxPassword = "",
                        hvUrl = null,
                        hvResolving = false,
                        hvResolvedUrl = null,
                    )
                is LoginFlow.LoginOutcome.RequiresTwoFactor ->
                    current.copy(inFlight = false, requiresTwoFactor = true, error = null)
                is LoginFlow.LoginOutcome.RequiresMailboxPassword ->
                    current.copy(inFlight = false, requiresMailboxPassword = true, error = null)
                is LoginFlow.LoginOutcome.RequiresHumanVerification ->
                    current.copy(inFlight = false, hvUrl = outcome.url, error = null)
                LoginFlow.LoginOutcome.Fido2Only ->
                    current.copy(
                        inFlight = false,
                        error = "This account uses FIDO2-only two-factor authentication, which is not supported yet.",
                    )
                LoginFlow.LoginOutcome.InvalidTwoFactorCode ->
                    current.copy(inFlight = false, error = "Invalid two-factor code.")
                is LoginFlow.LoginOutcome.Error ->
                    current.copy(inFlight = false, error = outcome.message)
            }
        }
    }

    override fun onCleared() {
        loginFlow.cancel()
        super.onCleared()
    }
}
