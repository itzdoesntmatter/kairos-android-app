package digital.tonima.core.analytics

import com.paulrybitskyi.hiltbinder.BindType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op [Analytics] binding used when no analytics backend (e.g. Firebase) is configured.
 * Keeps every `@Inject Analytics` call site compiling while dropping events on the floor.
 */
@Singleton
@BindType(installIn = BindType.Component.SINGLETON, to = Analytics::class)
class NoOpAnalytics
    @Inject
    constructor() : Analytics {
        override fun logEvent(
            name: String,
            params: Map<String, Any?>,
        ) {
            // Intentionally empty: analytics backend removed.
        }
    }
