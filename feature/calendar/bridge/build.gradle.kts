plugins {
    id("android-feature-bridge-convention")
}

android {
    namespace = "digital.tonima.kairos.feature.calendar.bridge"
}

// EventIntent lives here (not in :impl) so other features can build and dispatch calendar
// intents as a plain data vocabulary, without depending on the calendar implementation.
dependencies {
    api(project(":core"))
    api(project(":core:navigation"))
}
