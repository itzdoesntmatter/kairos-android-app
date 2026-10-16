plugins {
    id("android-library-convention")
    alias(libs.plugins.kotlin.serialization)
}

// Platform layer of the in-app Proton Calendar sync bridge: Android account ownership,
// CalendarProvider writes, WorkManager scheduling, and (since wave 2a) the Proton auth
// flow, encrypted session store, and the Retrofit/OkHttp API client.
android {
    namespace = "digital.tonima.protonsync"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.logcat)

    // Networking (Retrofit 3 + OkHttp 5, already used by core:data — no new transports).
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlin.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    // bcrypt for the SRP password hash and the key-password KDF (architecture D1: the JCA
    // primitives are BigInteger + SHA-512; bcrypt comes from BouncyCastle, exact-pinned).
    implementation(libs.bouncycastle)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
