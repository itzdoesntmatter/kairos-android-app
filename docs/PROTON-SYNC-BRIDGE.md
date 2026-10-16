# Proton Calendar Sync Bridge — Runbook

Purpose: everything an operator, support person or developer needs to run, verify and
debug the in-app Proton Calendar sync bridge in Kairós. Wire-level design lives in the
campaign specs (`specs/architecture.md`, `specs/security-audit.md`, `specs/auth-flow.md`,
`specs/calendar-api.md`, `specs/crypto-envelope.md`, `specs/sync-algorithm.md`,
`specs/storage.md`, `specs/test-plan.md` — maintained outside this repo); this file is the
operational view.

Code state: branch `feat/proton-sync-bridge`, wave-2 code landed — `95a2f1a` (wave 2a:
auth/SRP, session store, API client), `06ee66d` (QA gap-fill tests), `a16827a` (wave 2b:
crypto envelope, sync engine, key storage, settings UI, observer gating). Instrumented
tests and the signed release build are the W3 gate; there is no release artifact yet.

## What the bridge does

Pulls Proton Calendar events into Android's native Calendar Provider under a Kairós-owned
sync account, so Kairós's existing native-calendar read path (widgets, alarms, "next
event") picks them up with zero extra plumbing.

- Direction: **pull-only, server-authoritative**. Proton → device. Local edits to
  Proton-owned rows are overwritten by the next pull (v1 policy).
- Scope: **calendar events only**. No contacts, no mail, no push, no Proton-side writes.
- Cost: **free**. No subscription/license code of any kind (the reference app gates sync
  behind a license; Kairós deliberately does not — see the divergence log).
- Account model: **one Proton account** per install. Logging in while connected performs
  a full logout-then-login.

## Usage

### Enable / connect

1. Open Kairós → **Settings** → **Calendar sync** section (below the skip-weekends /
   all-day sections).
2. Tap **Connect Proton Calendar**.
3. Enter your Proton email and password. First connect requires ticking the consent
   checkbox: *"I understand that my Proton Calendar events will be written into this
   device's system calendar and become readable by other calendar apps on this
   device."* The consent flag is stored (DataStore `consent_granted`) and not asked
   again.
4. If the account has TOTP two-factor auth, a 6-digit code field appears. FIDO2-only
   accounts abort with an explicit message (TOTP only in v1).
5. On success the card flips to **Connected · \<email\>**, the Android account is
   registered, keys are unlocked and an initial sync is queued immediately.

There is no separate on/off switch: connecting = enabled. Disconnecting = disabled.

### Day-to-day controls (Settings → Calendar sync card)

| Control | Effect |
|---|---|
| **Connected · \<email\>** | Session + sync state; shows last sync time, sync error, or "Session expired". |
| **Sync frequency** | One of every hour / 6 h / 12 h / 24 h (default 6 h). Writes `sync_interval_hours` and re-enqueues the periodic work immediately. |
| **Sync now** | Enqueues an immediate (expedited) one-shot sync, replacing any pending one. |
| **Disconnect** | Full logout (below). |
| Counters line | `N synced · N deleted · N decrypt failures · N verify failures`. |

### Logout / disconnect

**Disconnect** performs, in order, each step completing even if the previous one fails:

1. `DELETE /auth/v4` — best-effort server-side session revocation (offline logout still
   completes).
2. Wipes `files/proton-session/` (`session.dat` + `keys.dat`), clears in-memory tokens,
   closes the keyring.
3. `removeAccountExplicitly` — the Calendar Provider purges every event/calendar row the
   Kairós Proton account owns. This is the row-deletion strategy on disconnect.
4. Clears the Room sync-metadata database.
5. Cancels all four unique WorkManager jobs.

Local calendar rows therefore disappear on disconnect by design; reconnecting re-syncs.

## Architecture summary

Module map (all new code lives in `:proton-sync` unless noted):

```
:proton-sync (digital.tonima.protonsync)
  account/   ProtonSyncAccount (ACCOUNT_TYPE), AuthenticatorService (exported=false stub)
  auth/      SrpClient, LoginFlow, LogoutUseCase, TokenRefresher, BcryptPasswordHasher
  api/       ProtonApiClient (Retrofit), ProtonHeadersInterceptor, RateLimitInterceptor,
             TokenRefreshAuthenticator, dto/ (wire DTOs)
  crypto/    ProtonCrypto facade + ProtonCorePgpCrypto (ProtonCore crypto-android),
             ProtonCryptoKeyring, EventPartDecryptor, KeyPasswordDeriver, KeysStore
  session/   SessionManager, SessionStore, SessionCrypto (Keystore AES-GCM)
  sync/      ProtonSyncEngine, ProtonCalendarWriter, NativeCalendarWriter, ReconcileDiff,
             IcalEventParser, ProtonSyncWorker, ProtonSyncScheduler, ProtonSyncPreferences,
             ProtonSyncModule (manual DI graph)
  db/        SyncDatabase (Room, metadata only), entities, DAOs
  status/    ProtonSyncStatusProvider (state card source of truth)

Existing modules touched by wave 2 (owned by the code task, listed for map completeness):
  feature/settings/impl  ProtonCalendarSettingsSection (Compose UI) + ProtonSyncSettingsViewModel
  core/model             KairosProtonSync.ACCOUNT_TYPE = "digital.tonima.kairos.proton"
  app                    SettingsScreen entry, KairosApplication ensurePeriodic(), manifest,
                         backup/data-extraction rule files, CalendarChangeObserver hook
```

Data flow, connect path: Settings UI → `LoginFlow` (SRP-6a against `mail-api.proton.me`,
optional TOTP) → `SessionManager` persists the refresh token to
`files/proton-session/session.dat` and unlocks user/address keys → `KeysStore` persists the
unlocked key material to `files/proton-session/keys.dat` → Android account registered →
initial sync queued.

Data flow, sync path (one run per calendar):

```
GET /calendar/v1                       list of Proton calendars
  per calendar:
    keys + passphrase endpoints        unlock the calendar key (lazily, cached in keyring)
    GET /calendar/v1/{id}/events       three Type buckets (0/1/2), 90-day chunks, page walk
    EventPartDecryptor                 OpenPGP decrypt (ProtonCore crypto) + signature policy
    IcalEventParser                    iCal/VEVENT text → ProtonEventData
    ReconcileDiff                      insert / update-in-place / skip (LastEditTime rule)
    NativeCalendarWriter               CalendarProvider write (Events/Attendees/Reminders,
                                       CALLER_IS_SYNCADAPTER=true, SYNC_DATA1 = proton event ID)
    GET /calendar/v1/{id}/events/ids   all-time deletion authority → tombstones
```

The CalendarProvider write triggers the existing `CalendarChangeObserver` →
`PhoneEventSyncWorker` + `AlarmSchedulingWorker`, which is why widgets/alarms update with
no new read-side code.

## Sync behavior and scheduling

WorkManager jobs (all unique names):

| Trigger | Work name | Policy |
|---|---|---|
| Login success | `proton-sync-calendar-once` | REPLACE, expedited |
| Periodic | `proton-sync-calendar-periodic` | KEEP; REPLACE when the frequency setting changes |
| User edit of a Proton-owned row | `proton-sync-calendar-onchange` | REPLACE (see gating below) |
| Manual "Sync now" | `proton-sync-calendar-once` | REPLACE, expedited |
| `proton-sync-session-refresh` | — (name reserved) | Nothing enqueues it today: token refresh runs inline in the OkHttp authenticator (single-flight, on 401). The name exists in the contract so logout cancels it with the rest. |

All sync work requires `NetworkType.CONNECTED`. There is no boot receiver — WorkManager
persists the schedule across reboots, and `KairosApplication.onCreate()` re-asserts
`ensurePeriodic()` (idempotent, no-op while logged out).

Run structure, incremental rule and deletions:

- First run after login: full window [1970-01-01, 2037-12-31]; afterwards bounded
  (now − 180 days … now + 365 days, extended back to the last-sync cursor).
- An event is re-fetched only when missing locally or when
  `stored.lastModified < LastEditTime` (+ `sync-retime` cases) — every run is incremental
  in effect. A window-too-big 400 response halves the 90-day chunk (min 1 day) and retries.
- Deletions are driven only by the all-time `/events/ids` pass. A locally-known event
  absent from that list gets a **tombstone** and is deleted only after **2 successful
  sync runs**; if the ids pass fails, the whole deletion pass defers to the next run.
  A calendar deleted server-side deletes its native calendar (cascading its events).
- One failing calendar never aborts the others (partial sync); its error is recorded and
  surfaced as `N calendar(s) failed; the rest synced`.
- Calendar-default reminders are propagated to previously-synced events that have none.
- No real-time event stream in v1: server-side edits arrive at the next periodic run
  (≥ 15 min). The core/v5 event-stream poller is the documented v2 upgrade path.

Observer gating (why edits and syncs don't loop):

- The existing `CalendarChangeObserver` gains a hook: a change that resolves to a row of
  our ACCOUNT_TYPE **with `DIRTY=1`** (i.e. a real user edit — bridge writes go through
  the provider with `CALLER_IS_SYNCADAPTER=true`, so they never set DIRTY) enqueues an
  onchange sync.
- A persisted last-trigger timestamp caps onchange-triggered syncs at **one per 15
  minutes**, independent of the 3-second observer debounce.

User-visible states (settings card): Disconnected / Connected · last sync / Syncing… /
Sync error: \<reason\> (+ retry) / Session expired — log in again. No notifications; the
card is the only surface.

## Security posture

What is encrypted where:

| Material | At rest | Notes |
|---|---|---|
| Refresh token + UID + scope | `files/proton-session/session.dat` — AES-256-GCM under a non-exportable Android Keystore key (alias `proton_session_key`), 12-byte random IV, `setUnlockedDeviceRequired(true)` | No plaintext fallback of any kind — if the Keystore is unavailable the app refuses to persist rather than degrade |
| Unlocked user/address/calendar key material | `files/proton-session/keys.dat` — same container, dedicated alias `proton_keys_key`, `PSK1` magic + JSON payload | The mailbox password itself is never retained |
| Access token | Memory only | Rebuilt from refresh on process start |
| Sync metadata (event-ID map, tombstones, per-calendar state, counters) | Room `proton-sync.db` — **plaintext, metadata only by rule** | No titles, descriptions, payloads, tokens or keys; enforced by test |
| Decrypted event content | Memory only (decrypt → ContentValues → provider) | Once in the provider it is device-readable — hence the consent gate |

- Transport: TLS to `https://mail-api.proton.me/` with a `CertificatePinner` (3 SPKI
  SHA-256 pins: leaf, intermediate, root — rotating pins ship via app update). Cleartext
  explicitly disabled (`network_security_config.xml`, no host exceptions). DoH disabled.
- Honest client identity: `x-pm-appversion: Kairos_<versionCode>`, `User-Agent:
  Kairos/<versionName> (Android <SDK>; <model>)`. Never a Proton client identifier.
- Login screens take `FLAG_SECURE` while credential fields are visible; passwords enter
  as `CharArray` and are zeroized after use.
- Backup exclusion (both rule files, both modes): `file:proton-session/` and
  `database:proton-sync.db(-wal,-shm)` are excluded from cloud backup and device transfer.
- Permissions added: `AUTHENTICATE_ACCOUNTS` (signature-level) plus existing
  calendar/INTERNET. `GET_ACCOUNTS` deliberately not requested (own-type queries need no
  permission).
- Human verification (when Proton demands it): the WebUrl must be on the allowlist
  {proton.me, protonmail.com, hcaptcha.com} before use; Custom Tabs only, never an
  embedded WebView. (UI wiring status: see Known limitations.)

Open audit items (not blockers for the current state; tracked for W3):

- `CalendarRepositoryImpl.isProtonBacked()` still matches any account whose name/type
  *contains* `proton` — a spoofable provenance path for the Proton read filter
  (security-audit ME-4; tighten to the exact account type for bridge rows).

## Known limitations

- **No contacts sync.** Contacts are a non-goal for this campaign.
- **Pull-only.** Edits made on the device to Proton-owned events are reverted by the next
  pull; there is no push path and no conflict-resolution UI (v1 decision).
- **No real-time updates.** No core/v5 event stream; server-side changes appear at the
  next scheduled sync (≥ 15 min; frequency setting dependent).
- **Human-verification UI not wired.** `LoginFlow` produces the verified HV URL and the
  status ViewModel exposes it, but the settings section does not yet open a Custom Tab or
  resume the login (`resumeAfterHv` is unreferenced; the `proton_sync_hv_*` strings are
  unused). Accounts that trigger HV cannot complete login in this build — W3 fix.
- **Instrumented behavior unverified on this box** (no device attached): real Keystore
  round-trip, real CalendarProvider recurrence/all-day/`CALLER_IS_SYNCADAPTER` semantics,
  live login (SRP against a real account), certificate-pin rotation drill, end-to-end
  sync. These are the wave-3 device gate; unit coverage is 38 suites / 260 tests green.
- **VERIFY-NEEDED items from the RE specs** (could not be confirmed dynamically — no live
  capture was possible during the campaign): the SRP replay vector against a captured
  `/auth/v4/info` exchange; the modulus-signature handling variants; crypto envelope
  known-answer corpus (PKESK/SEIPD layout, armor conventions, `profile.ProtonV1`
  semantics, key-selection internals); `/events/ids` pagination values on a live account;
  `x-pm-apiversion` wire value observed on the reference app ("3.7" quirk) vs ours ("3");
  deletion-reason semantics of the reference's push path (not implemented by us). Each is
  listed in the corresponding spec's VERIFY-NEEDED section.
- **Single account, no multi-account UI** (schema reserves the seam).
- **GPL-3.0 obligations.** The APK links ProtonCore crypto (GPL-3.0): licenses/copyright
  notices must ship in the app and the release must carry the corresponding-source
  pointer before public distribution (security-audit §9 item 13; flagged for legal
  review).
- **Release build not produced yet.** W3 owns `assembleDebug` + lint + signing + GitHub
  release from the operator terminal.

## Troubleshooting

| Symptom | Check | Fix |
|---|---|---|
| No events in Kairós | Settings → Calendar sync card state. If "Connected", check last sync time and the counters line. | Disconnected → Connect. |
| Still no events | Kairós calendar filter: the bridge writes into the system provider, but Kairós only shows calendars enabled in its own filter (Manage Calendars / calendar filter toggles). | Enable the Proton calendar(s) in the filter. |
| Still no events | Calendar app on the device: is the Proton calendar visible there? The bridge sets `VISIBLE=1` on insert but never re-forces it — a hidden calendar stays hidden. | Un-hide it in the Calendar app. |
| Still no events | Calendar permission: without READ_CALENDAR the worker deliberately no-ops. | Grant calendar permission, then "Sync now". |
| Counters show decrypt failures / events titled `[Encrypted event — cannot decrypt]` | The event could not be decrypted with the unlocked keys (or its signature failed strict verification — verify failures counter). | Re-login (rebuilds key material from the server); if it persists, collect logcat tags `ProtonPartDecryptor`/`ProtonSyncEngine` and file it. |
| "Session expired — log in again" | Refresh token was revoked/rejected. | **Disconnect**, then Connect again (logout-then-login is the only supported re-auth path). |
| "Sync error: \<reason\>" | Partial sync: at least one calendar failed; the rest synced. Reasons range from passphrase fetch failures to API drift. | Tap "Sync now"; if it persists, logcat tag `ProtonSyncEngine` names the failing calendar. |
| Events keep reverting after local edits | Expected: pull-only, server-authoritative. | Edit in Proton instead; the next pull re-applies the server version. |
| Deleted Proton events still show | Deletion grace: an event must be absent from the authoritative ids list for 2 successful runs before it is deleted; a failed ids pass defers deletions entirely. | Wait for 2 clean runs; check last sync advancing. |
| No sync while offline / battery saver | Sync requires network (CONNECTED) and runs at the configured cadence; Doze defers it. | "Sync now" when online. |
| Login hangs at "Continue" with "Verification required" | Known limitation: the human-verification screen is not wired in this build. | W3 follow-up; until then HV-gated accounts cannot finish login. |

Diagnostics that live on the device (for developers collecting bug reports):

- Logcat tags: `ProtonSyncEngine`, `ProtonSyncWorker`, `ProtonLoginFlow`,
  `ProtonPartDecryptor`, `ProtonKeysStore`, `ProtonLogout`, `CalendarChangeObserver`.
- State locations: DataStore `proton-sync.preferences_pb` (`sync_interval_hours`,
  `consent_granted`); SharedPreferences `kairos_proton_onchange_gate`; Room
  `proton-sync.db` (metadata); `files/proton-session/{session.dat,keys.dat}` (opaque
  ciphertext by design — do not expect to read them).

## Developer reference

Build/test commands verified on the campaign box (JDK 21, `ANDROID_HOME` set):

```
./gradlew :proton-sync:testDebugUnitTest
```

Last verified: 2026-10-16 18:58 EDT — `BUILD SUCCESSFUL in 16s` / `44 actionable tasks:
2 executed, 42 up-to-date`; fresh test XMLs: **38 suites / 260 tests / 0 failures /
0 errors** (`proton-sync/build/test-results/testDebugUnitTest/*.xml`).

Wave commits on `feat/proton-sync-bridge` (from `git log --oneline`):

```
a16827a LOD-11 wave 2b: Proton sync crypto envelope, engine, storage, settings UI, observer gating
06ee66d LOD-12 QA wave 2: gap-fill tests for landed 2a auth/session/api (55 new tests)
95a2f1a LOD-8 wave 2a: Proton auth (SRP-6a client), encrypted session store, API client
12008bb feat: add :proton-sync module — sync account, native calendar writer, WorkManager skeleton
```

Module size at `a16827a`: 46 main sources / ~6,600 lines; 39 test sources / ~6,700 lines.

Related documents: `docs/PROTON-SYNC-DIVERGENCE-LOG.md` (where we intentionally differ
from the reference app); campaign specs under
`reverse-engineering/sync-for-proton/specs/` (architecture, security-audit, auth-flow,
calendar-api, crypto-envelope, sync-algorithm, storage, test-plan).

## Changelog

- 2026-10-16 — initial runbook (W3 docs, LOD-14). Covers wave-2 code at `a16827a`; release
  build and instrumented verification are outstanding W3 gates.
