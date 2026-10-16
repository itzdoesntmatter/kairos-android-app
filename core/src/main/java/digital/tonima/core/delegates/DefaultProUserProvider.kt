package digital.tonima.core.delegates

import com.paulrybitskyi.hiltbinder.BindType
import com.paulrybitskyi.hiltbinder.BindType.Component.SINGLETON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The app is free: no ads and no premium tier, so every user is a Pro user.
 *
 * Kept behind the [ProUserProvider] seam so the feature code that reads [isProUser]
 * keeps working unchanged, with the entitlement permanently granted.
 */
@BindType(installIn = SINGLETON, to = ProUserProvider::class)
class DefaultProUserProvider
    @Inject
    constructor() : ProUserProvider {
        private val _isProUser = MutableStateFlow(true)

        override val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

        override fun refresh() {
            // Entitlements no longer exist; nothing to refresh.
        }
    }
