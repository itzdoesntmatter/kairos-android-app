plugins {
    id("android-library-convention")
}

// Platform layer of the in-app Proton Calendar sync bridge: Android account ownership,
// CalendarProvider writes, and WorkManager scheduling. No Proton networking/auth/crypto here —
// the wave-2 SyncEngine implementation plugs in behind the sync/SyncEngine interface.
android {
    namespace = "digital.tonima.protonsync"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
