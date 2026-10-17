plugins {
    id("android-library-convention")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
}

// Platform layer of the in-app Proton Calendar sync bridge: Android account ownership,
// CalendarProvider writes, WorkManager scheduling, the Proton auth flow (SRP-6a), encrypted
// session + key-material store, the Retrofit/OkHttp API client, and (since wave 2b) the
// OpenPGP crypto envelope, the Room sync-metadata store and the sync engine itself.
android {
    namespace = "digital.tonima.protonsync"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Exported Room schemas double as the migration-test fixtures (android-library
        // convention; assets are picked up by MigrationTestHelper under Robolectric).
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

ksp {
    // Room schema export feeds the migration tests (storage.md: explicit migrations,
    // destructive fallback documented in SyncDatabase).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // api: ProtonSyncScheduler's public signatures expose androidx.work types
    // (PeriodicWorkRequest / OneTimeWorkRequest), so consumers must see them.
    api(libs.androidx.work.runtime.ktx)
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
    // OpenPGP signature verification of the SRP modulus (ModulusVerifier) — the PGP
    // classes live in bcpkix + bcpg, not bcprov.
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.pgp)

    // OpenPGP envelope crypto (architecture D1 / §5): ProtonCore crypto-android wraps the
    // exact gomobile Go library the reference app binds, making us envelope-exact with
    // crypto-envelope.md. GPL-3.0 — distribution obligations documented in architecture §9.13.
    implementation(libs.proton.crypto.android)
    implementation(libs.proton.crypto.common)

    // iCal parsing of decrypted event parts (architecture §3: ical4j, the reference's parser
    // family; RRULE validation + date parsing for the CalendarContract column mapping).
    implementation(libs.ical4j)

    // Sync metadata Room DB ("proton-sync.db": event-ID map, tombstones, per-calendar state).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // User-configurable sync cadence (architecture §0: "proton-sync.preferences_pb",
    // key sync_interval_hours ∈ {1,6,12,24}, default 6).
    implementation(libs.datastore.preferences.core)

    // ACCOUNT_TYPE constant lives in core:model (leaf module) so feature/calendar:impl can
    // use it without depending on this module (architecture §1).
    implementation(project(":core:model"))

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.datastore.preferences.core)
}
