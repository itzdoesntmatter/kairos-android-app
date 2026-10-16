plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.detekt)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.jacoco.convention)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.spotless)
    alias(libs.plugins.stability.analyzer)
}

android {
    namespace = "digital.tonima.kairos"
    compileSdk = rootProject.extra["COMPILE_SDK_VERSION"].toString().toInt()

    defaultConfig {
        applicationId = "digital.tonima.kairos"
        minSdk = rootProject.extra["MIN_SDK_VERSION"].toString().toInt()
        targetSdk = rootProject.extra["TARGET_SDK_VERSION"].toString().toInt()
        versionCode = findProperty("android.injected.version.code")?.toString()?.toInt()
            ?: rootProject.extra["APP_VERSION_CODE"].toString().toInt()
        versionName = findProperty("android.injected.version.name")?.toString()
            ?: rootProject.extra["APP_VERSION_NAME"].toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD") ?: "default_store_password_app"
            keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS") ?: "default_key_alias_app"
            keyPassword = System.getenv("ANDROID_SIGNING_KEY_ALIAS_PASSWORD") ?: "default_key_password_app"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    androidResources {
        // Lists the app's translations in the system per-app language picker (Android 13+).
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    jvmToolchain(21)
}

// The baselineprofile plugin adds release-like build types (benchmarkRelease, nonMinifiedRelease) for
// :baselineprofile. They copy the release signing, but the release key only exists in CI, so they are
// signed with the debug key. Release builds are untouched.
androidComponents {
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.signingConfig.setConfig(android.signingConfigs.getByName("debug"))
    }
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.signingConfig.setConfig(android.signingConfigs.getByName("debug"))
    }
}

dependencies {
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    baselineProfile(project(":baselineprofile"))

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.calendar)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size.class1)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.compose.icons.extended)
    implementation(libs.coroutines.play.services)
    implementation(libs.google.inapp.update)
    implementation(libs.google.inapp.update.ktx)
    implementation(libs.guava)
    implementation(libs.hilt.android)
    implementation(libs.hilt.binder)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.worker)
    implementation(libs.logcat)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":core"))
    implementation(project(":core:data"))
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":feature:alarm:impl"))
    implementation(project(":feature:calendar:bridge"))
    implementation(project(":feature:calendar:impl"))
    implementation(project(":feature:settings:bridge"))
    implementation(project(":feature:settings:impl"))
    implementation(project(":proton-sync"))

    ksp(libs.androidx.hilt.compiler)
    ksp(libs.hilt.binder.compiler)
    ksp(libs.hilt.compiler)

    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}

// Robolectric 4.17 reflects into JDK internals (jdk.internal.access.SharedSecrets and friends)
// to bootstrap ApplicationSharedMemory; on JDK 17+ that throws IllegalAccessException unless
// these packages are explicitly opened to the test JVM. See
// https://github.com/robolectric/robolectric/releases/tag/robolectric-4.17.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
}

apply(from = "../spotless.gradle")
