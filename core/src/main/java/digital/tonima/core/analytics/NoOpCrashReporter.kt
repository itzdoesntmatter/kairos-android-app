package digital.tonima.core.analytics

import com.paulrybitskyi.hiltbinder.BindType
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op [CrashReporter] binding used when no crash-reporting backend (e.g. Firebase Crashlytics)
 * is configured. Still logs the failure locally so it is visible in logcat on debug builds.
 */
@Singleton
@BindType(installIn = BindType.Component.SINGLETON, to = CrashReporter::class)
class NoOpCrashReporter
    @Inject
    constructor() : CrashReporter {
        override fun recordNonFatal(
            throwable: Throwable,
            message: String,
        ) {
            logcat(LogPriority.ERROR) { "$message\n${throwable.asLog()}" }
        }
    }
