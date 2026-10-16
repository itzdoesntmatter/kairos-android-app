package digital.tonima.core.delegates

import com.paulrybitskyi.hiltbinder.BindType
import com.paulrybitskyi.hiltbinder.BindType.Component.SINGLETON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The app is free: no ads and no premium tier, so every user is both a Pro and an AI user.
 *
 * Kept behind the [ProUserProvider] seam so the feature code that reads [isProUser] and
 * [isAiUser] keeps working unchanged, with every entitlement permanently granted.
 */
@BindType(installIn = SINGLETON, to = ProUserProvider::class)
class DefaultProUserProvider
    @Inject
    constructor() : ProUserProvider {
        private val _isProUser = MutableStateFlow(true)
        private val _isAiUser = MutableStateFlow(true)

        override val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()
        override val isAiUser: StateFlow<Boolean> = _isAiUser.asStateFlow()

        override fun refresh() {
            // Entitlements no longer exist; nothing to refresh.
        }
    }
