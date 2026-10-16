<img src="/images/kairos-banner.webp" width="1024" height="500">

[![Android CI - Kairos Multi-Module](https://github.com/tonimadev/kairos-android-app/actions/workflows/android-ci.yaml/badge.svg)](https://github.com/tonimadev/kairos-android-app/actions/workflows/android-ci.yaml) [![codecov](https://codecov.io/gh/tonimadev/kairos-android-app/graph/badge.svg?token=TKC92HM5VY)](https://codecov.io/gh/tonimadev/kairos-android-app)

# Kairós - Calendar Alarms for Android & Wear OS

> ⚠️ **Disclaimer:** This project is largely AI-generated ("vibe-coded") — written with AI coding agents and minimal human review. It may contain bugs, security issues, or unexpected behavior, and comes with no warranty. Review the code before relying on it for anything important.

Kairós is a modern application that transforms your calendar appointments into unmissable full-screen alarms, both on your smartphone and on your wrist with **Wear OS**. It intelligently synchronizes with the device's calendar and ensures you never miss an important event.

**Kairós is now completely free** — no ads, no in-app purchases, and every feature available to everyone.

## 🚀 Download

Kairós is distributed directly from GitHub — download the latest phone and Wear OS APKs from the
[Releases](https://github.com/itzdoesntmatter/kairos-android-app/releases) page.

<sub>Prefer to build it yourself? See [Getting Started](#-getting-started).</sub>

---

## ✨ Key Features

- **Full-Screen Alarms**: Wakes the device even when locked with visual and sound alerts (Smartphone and Wear OS).
- **Full Wear OS Integration**:
  - **Complications**: View the next event directly on the watch face.
  - **Tiles**: Instant access to event information with a side swipe.
- **Smart Synchronization**: Uses WorkManager for efficient background scheduling without draining the battery.
- **Native Integration**: Reads events from any calendar account configured on the device (e.g., Google Calendar).
- **Total Control**: Enable or disable alarms globally or for specific events.

<p align="center">
  <img src="/images/watch1.webp" width="200">
  <img src="/images/watch2.webp" width="200">
  <img src="/images/tile.gif" width="300">
</p>

---

## 🔐 Proton Calendar

Kairós reads events from Android's system calendar provider, so it works with **any** calendar
that is synced to the device — Google, Outlook, Exchange, and others.

**Proton Calendar** is the exception: it offers no public API and no CalDAV, and its Android app
keeps events end-to-end encrypted inside its own app, so events never reach the system provider
that Kairós (and every other calendar app) reads from.

To use Proton Calendar with Kairós, install a third-party sync bridge that mirrors your Proton
events into the device calendar. Kairós then picks them up automatically, exactly like any other
calendar:

- [ProtonSync](https://github.com/carlostkd/ProtonSync) — open-source, two-way sync
- [Sync Provider for Proton](https://apps.olausson.de/sync_provider_for_proton/) — two-way sync,
  multi-account, recurring-event support

Once a bridge has synced your Proton calendar, its events appear in Kairós alongside everything
else. The "Manage calendars" screen detects when Proton Calendar is installed but no Proton
events are visible yet, and guides you through installing a bridge.

---

## 📱 Screenshots

<p align="center">
  <img src="/images/screenshot-home.webp" width="220" alt="Home: the day's events as alarm cards">
  <img src="/images/screenshot-alarm.webp" width="220" alt="Full-screen event alarm">
  <img src="/images/screenshot-settings.webp" width="220" alt="Alarm settings">
</p>

<sub>Store listing graphics (feature graphic and framed screenshots in all 10 supported languages) live in `fastlane/metadata/android/<locale>/images/`.</sub>

---

## 🛠 Tech Stack

This project follows modern Android development principles with MVVM + MVI architecture.

- **Language**: 100% Kotlin
- **UI**: Jetpack Compose (Phone & Wear OS)
- **Architecture**: MVVM + MVI, Clean Architecture, Multi-module (feature `bridge`/`impl` pairs)
- **Navigation**: Navigation 3 (`NavDisplay` + `NavBackStack`), via an `AppNavigator` seam every feature depends on
- **Async**: Kotlin Coroutines + Flow
- **Persistence & Background**: DataStore, Room, WorkManager, AlarmManager
- **DI**: Hilt
- **Sync**: Wearable Data Layer (Play Services)
- **Testing**: JUnit4, Robolectric, Turbine, MockK

---

## 🏗 Architecture

### Multi-Module Structure

```
kairos-android-app/
├── app/                        → Composition root: MainActivity, AppNavigator impl (Navigation 3),
│                                  Activity/Receivers/Workers, cross-feature screen wiring
├── core/                       → Foundation: analytics, permissions, alarm scheduling/sound,
│                                  wear sync, review, in-app update — depended on by everything
├── core/data/                  → Shared domain/data layer: repositories + usecases used by 2+
│                                  features (calendar data, weather/directions, focus mode, etc.)
├── core/model/                 → Pure Kotlin domain models (Event, Weather, AlarmOffset, …)
├── core/navigation/            → The `AppNavigator` / `FeatureNavKey` / `BaseIntent` contracts
├── core/ui/                    → Shared design system (KairosTheme, Dimensions, …)
├── feature/calendar/{bridge,impl}/ → Home screen, event CRUD, import/manage calendars, auth
├── feature/settings/{bridge,impl}/ → Settings screen, permission gating
├── feature/alarm/impl/             → Alarm MVI (screen itself lives in `app/`, shared with `wear/`)
├── wear/                       → Wear OS UI, Tiles, Complications
├── build-logic/                → Convention plugins (android-library/-compose/-feature-*, Jacoco)
└── gradle/                     → Version catalog (libs.versions.toml)
```

Each `:feature:*:bridge` module holds only contracts (NavKeys, MVI `Intent` sealed classes) with
no Hilt/Compose — it's the one seam a feature's `:impl` is allowed to depend on across features.
`:feature:*:impl` holds the ViewModel + screens and depends on `:core`, `:core:data`, and its own
`:bridge`. `:app` is the only module allowed to depend on every feature `:impl`.

### MVVM + MVI Pattern

The app uses **unidirectional data flow** across all features:

```
View (Compose) ──EventIntent──▶ ViewModel ──▶ UseCases / Repositories
       ▲                            │
       │                            ▼
       └──── UiState (StateFlow) ───┘
             (Includes SideEffects)
```

- **`EventIntent`** — sealed class representing every user action.
- **`EventScreenUiState`** — single immutable state driving the UI.
- **`Side Effects`** — one-shot events (snackbar, confirmation dialogs) modeled as part of `UiState` to survive configuration changes. Actual screen navigation goes through `AppNavigator` (Navigation 3) instead of a `UiState` flag.
- **`EventViewModel`** — processes intents, delegates to UseCases, emits state updates.

### Where Things Live

| Concern | Module | Package |
|---|---|---|
| ViewModels, Intents, UiState, SideEffects | `feature/<name>/impl` | `digital.tonima.core.viewmodel` |
| Navigation contracts (`NavKey`, `Intent`) shared across features | `feature/<name>/bridge` | `digital.tonima.core.viewmodel` / `digital.tonima.feature.<name>.bridge` |
| Shared usecases + repositories (2+ features) | `core/data` | `digital.tonima.core.data.usecases` / `.repository` |
| Domain entities (Event, AlarmOffset, Weather, …) | `core/model` | `digital.tonima.kairos.core.model` |
| Alarm scheduling/sound, wear sync, permissions, analytics | `core` | `digital.tonima.core.*` |
| `AppNavigator` / `FeatureNavKey` contracts | `core/navigation` | `digital.tonima.kairos.core.navigation` |

---

## 🏁 Getting Started

### Prerequisites
- Android Studio Jellyfish+
- JDK 21
- Android SDK 36 (Compile/Target)

### Installation and Execution
1. Clone the repository:
   ```bash
   git clone https://github.com/tonimadev/kairos-android-app.git
   ```
2. Open in Android Studio and wait for Gradle synchronization.
3. To run via CLI:
   - **Phone**: `./gradlew :app:installDebug`
   - **Wear OS**: `./gradlew :wear:installDebug`

### Testing and Quality
- Run all unit tests: `./gradlew testDebugUnitTest`
- Generate coverage report (Jacoco): `./gradlew createJacocoMergedCoverageReport`
- Apply code style: `./gradlew spotlessApply`

---

## ⚙️ Additional Configuration

For **Release** builds, configure signing keys in `local.properties` or environment variables. See `build.gradle.kts` for details on expected properties.

---

## 🤝 Contributing
Contributions are welcome! Make sure to run `./gradlew spotlessApply` before opening a Pull Request.

---
Developed by **tonimadev**

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE).
