# Proton Sync Bridge — Divergence Log

Purpose: every place the Kairós in-app bridge **intentionally differs** from the observed
behavior of the reference app (`bolausson/sync-for-proton` v1.11.5, RE'd during this
campaign). Each entry: reference behavior (with the spec + evidence it was documented
from), our behavior (with the file that implements it), and why.

Format is LUMEN-NOTES style: one entry per decision, ID'd, newest campaign facts first —
this file is append-only history, not a summary. If behavior changes, add an entry; do not
rewrite one.

Reading order for reviewers: entries marked `SECURITY` were mandated by the wave-1
security audit; `SCOPE` entries are campaign non-goals; `MECHANISM` entries are
implementation choices with no user-visible difference.

Specs referenced below live in the campaign workspace
(`reverse-engineering/sync-for-proton/specs/`), not in this repo. "RE evidence" = what the
decompiled reference app shows, as recorded in the specs.

---

## D-01 · SECURITY · Client identity: honest and unique instead of generic/borrowed

- Reference: every Proton API call sends `x-pm-appversion: Other` — a generic placeholder
  token — and a stock OkHttp `User-Agent` (`okhttp/4.12.0`); a desktop Chrome UA appears
  only on the web checkout page (auth-flow.md §1; calendar-api.md §1; RE evidence:
  the shared header interceptor and the event-loop client both hard-code `Other`).
- Ours: `x-pm-appversion: Kairos_<versionCode>` and
  `User-Agent: Kairos/<versionName> (Android <SDK_INT>; <Build.MODEL>)`
  (`ProtonApiClientFactory.ProtonClientIdentity`; hard rule I6 in architecture.md §0/§6).
- Why: two reasons, both load-bearing. (1) Proton's anti-abuse and app-version checks key
  off these headers; a truthful, unique identity is the only one that survives them
  reliably, and impersonating a Proton client (`ProtonMail/…`, official app identifiers —
  the `android-mail`-style identity some third-party bridges borrow) breaches Proton's ToS
  and gets accounts flagged (security-audit §4.5). (2) It keeps Kairós's traffic
  attributable to Kairós. Note for the record: the reference app does *not* spoof a Proton
  client either — it just identifies as nobody in particular; we identify as ourselves.
- Enforced by: `ProtonHeadersInterceptorTest` / `AuthHeadersGapTest` (assert the literal
  Proton identifiers never appear).

## D-02 · SECURITY · SRP group pinned; the server's modulus signature is not trusted blindly

- Reference: `POST /auth/v4/info` returns a PGP-armored, signed modulus; the reference
  extracts the base64 body after the `Hash:` header and **never verifies the signature**
  — whatever group arrive is used (auth-flow.md §2.2; RE evidence: armored-modulus parser
  with no verify call).
- Ours: the Proton 2048-bit modulus is a hard-coded constant
  (`SrpClient.PINNED_MODULUS_BASE64`, sourced from ProtonMail/go-srp's published test
  vectors); every server-provided modulus is compared byte-for-byte, constant-time, and a
  mismatch aborts login with an explicit error before any SRP math runs
  (`SrpClient.ModulusParser.parseAndVerify`).
- Why: security-audit CR-2. If TLS is ever stripped (rogue CA, captive portal), an
  unpinned group lets an attacker substitute a weak modulus and mount an offline
  dictionary attack on the SRP exchange. The modulus is public protocol constant material,
  so pinning is both cleanroom-safe and cheap. If Proton ever rotates the group, login
  fails loudly here instead of silently trusting the new one.

## D-03 · SCOPE · No subscription or license gate — the bridge is free

- Reference: sync is gated on a paid license. A pasted `sub_` ID is validated against the
  vendor's license server; the sync runner and the event loop both skip all work when the
  state is not Trial/Active, with expiry/grace/pause states (storage.md Appendix A; RE
  evidence: the license check precedes the sync runner body, and the event loop's poll
  gate consults the same state).
- Ours: **no license code exists anywhere in the bridge** (grep for
  subscription/license/paddle/checkout in `proton-sync/` and the settings module: zero
  hits outside a comment). Sync runs for any connected account.
- Why: campaign NON-GOAL, binding (ANALYSIS-NOTES.md cleanroom rule 3): Kairós is free and
  must not replicate the reference's licensing, payment or validation machinery. The RE
  documentation of it (storage.md Appendix A) exists for completeness only.

## D-04 · SECURITY · Explicit consent before writing into the shared device calendar

- Reference: no in-flow consent notice is documented in the RE specs — the reference ships
  `allowBackup="false"` and otherwise writes provider rows without a device-readability
  disclosure (nothing in the specs describes a consent step).
- Ours: a mandatory checkbox on the login form before the first connect — "I understand
  that my Proton Calendar events will be written into this device's system calendar and
  become readable by other calendar apps on this device" — persisted as
  `consent_granted` in the bridge DataStore; the Continue button is disabled until it is
  ticked (`ProtonCalendarSettingsSection`, `ProtonSyncSettingsViewModel`,
  `ProtonSyncPreferences`).
- Why: security-audit HI-2/ME-5. Writing E2E-protected Proton content into a world-readable
  (pre-Android-14) provider changes its exposure model; the user must know.

## D-05 · SCOPE · No contacts sync

- Reference: syncs Proton Contacts into the Android contacts provider as a second account
  (`me.proton.sync.address_book`), with its own sync-state table and conflict rules
  (storage.md §3 `contact_sync`; auth-flow.md §4; sync-algorithm.md §1.5).
- Ours: calendar events only. No contacts account type, no contacts code, no contacts
  permission (`proton-sync/` has zero contacts references).
- Why: campaign NON-GOAL (CAMPAIGN-PLAN.md) — Kairós has no contacts surface today. A
  possible later wave.

## D-06 · SECURITY · Observer gating: user edits only, rate-limited (no sync storms, no feedback loop)

- Reference: detects locally dirty rows by querying `dirty = 1` rows of its account from
  its own periodic event loop, and separately uses the system SyncAdapter framework whose
  scheduling it hands to Android (sync-algorithm.md §1.3/§1.5/§6.6).
- Ours: the app's existing `CalendarChangeObserver` gains a hook that enqueues an onchange
  sync **only** when the changed row belongs to our account type **and** carries
  `DIRTY=1`, with a persisted 15-minute minimum interval between onchange-triggered syncs
  (`CalendarChangeObserver.maybeEnqueueProtonSync`).
- Why: security-audit ME-1/ME-2. Our bridge writes provider rows itself, so "any change to
  our rows" would include our own sync writes — without an origin filter every sync would
  schedule another sync. Bridge writes go through the provider with
  `CALLER_IS_SYNCADAPTER=true`, so the provider records them with `DIRTY=0`; a user edit
  in any calendar app sets `DIRTY=1`. The 15-minute floor additionally contains apps that
  spam writes to our rows as a DoS/battery vector.

## D-07 · SECURITY · Signature verification on event parts: verify-or-drop instead of best-effort

- Reference: type-2 parts are plaintext iCal whose detached signature is verified
  best-effort — a failed verification is swallowed and the part still returned; when
  nothing decrypts, an `[Encrypted Event]` placeholder with the encrypted app's own
  `PRODID` is emitted (crypto-envelope.md §3; RE evidence: verification wrapped in a
  try/catch that skips the part without surfacing the failure).
- Ours: **strict verify-or-drop** — a part whose signature fails verification is dropped
  and counted (`verify_failures`, surfaced on the settings card); a payload that cannot be
  verified is never presented as authentic. When every content part fails, the caller
  emits an unambiguous `[Encrypted event — cannot decrypt]` placeholder containing only
  the server-sent time metadata, and there is no locally emitted `PRODID` at all (we write
  provider fields directly, never assembled VCALENDAR text)
  (`EventPartDecryptor`, security-audit §3.4/HI-3).
- Why: the server (or anything upstream) can inject type-2 plaintext; treating an
  unverifiable payload as authentic defeats the point of the encryption envelope. Genuine
  trade-off accepted: events with undecryptable/unverifiable content show as
  clearly-marked placeholders instead of (possibly forged) content.

## D-08 · MECHANISM · Event identity stored in `SYNC_DATA1`, not the legacy `_sync_id`

- Reference: writes the Proton event ID into the provider's legacy `_sync_id` column and
  reconciles on `calendar_id + _sync_id` (sync-algorithm.md §4.1; RE evidence: the
  VEVENT→provider converter sets `_sync_id`).
- Ours: the same value goes into `SYNC_DATA1` — the non-deprecated `EventsColumns`
  equivalent — and reconciliation matches on it, never on title/time
  (`NativeCalendarWriter`, invariant I2; architecture.md §0 deviation note).
- Why: `_sync_id` is deprecated and some OEM providers treat it specially; `SYNC_DATA1` is
  the sanctioned column for sync-adapter apps. Same semantics, recorded here because it is
  a deliberate contract difference; the device-verification of the write/read round-trip
  is part of the W3 instrumented gate.

## D-09 · SECURITY · Key material at rest: re-encrypted unlocked keys, never the password

- Reference: persists the mailbox password itself in AccountManager `userdata`
  (`mailbox_password` alongside `refresh_token`, `enc_private_key`), so it can re-unlock
  keys after process death (storage.md §5; auth-flow.md §4).
- Ours: the mailbox password is never retained. After login, the unlocked
  user/address/calendar key material is persisted **re-encrypted** (AES-256-GCM, Keystore
  alias `proton_keys_key`) to `files/proton-session/keys.dat` (`KeysStore`,
  architecture.md §5).
- Why: equivalent exposure (file + Keystore access implies device compromise anyway) with
  strictly less secret material stored — a plaintext-equivalent credential (the password)
  is never on disk, and the AccountManager userdata surface is not used at all.

## D-10 · SECURITY · No plaintext fallback path for any secret

- Reference: its SecureStorage wrapper falls back to `B64:` (base64 obfuscation only) when
  Keystore encryption fails (auth-flow.md §4; storage.md §2; RE evidence: fallback branch
  writing base64 when the Keystore path throws).
- Ours: no fallback of any kind — if the Keystore is unavailable, `AndroidKeyStoreSessionCrypto`
  throws and the caller refuses to persist rather than write anything weaker
  (`SessionCrypto`; security-audit §3.1/§5.2.6).
- Why: a silent downgrade to obfuscated plaintext is how "encrypted at rest" quietly
  becomes "readable at rest". Refusing to store is the safe failure.

## D-11 · SECURITY · Room metadata database is plaintext-by-design and holds no content

- Reference: the whole Room DB is SQLCipher-encrypted, with the SQLCipher passphrase
  itself stored (wrapped) in SharedPreferences; it caches decrypted event fragments
  (`cached_*` columns) to avoid re-decrypting (storage.md §1–§3).
- Ours: plaintext Room database `proton-sync.db` that stores **metadata only** — event-ID
  map, tombstones, per-calendar state, counters. No content columns, no `cached_*`
  fragments, no secrets (`SyncDatabase`, `Entities`; security-audit §5.1/§5.2.8 — the schema
  holds no content columns by construction; a reflection-based guard is test-plan item
  DB-03, not yet implemented).
- Why: encrypting a DB that holds no secrets is dead weight (2 MB native SQLite fork for
  nothing), and the no-content rule is what keeps the plaintext DB safe. The audit
  accepted this trade explicitly.

## D-12 · SCOPE · No core/v5 event-stream poller in v1

- Reference: runs a near-real-time poller (`EventLoopWorker`) against `/core/v5/events`
  to trigger syncs within minutes of server-side changes (calendar-api.md §5;
  sync-algorithm.md §1.3).
- Ours: no stream poller. Server-side edits arrive at the next scheduled run (≥ 15 min,
  cadence-dependent); incremental catch-up uses the per-event `LastEditTime` rule plus the
  all-time `/events/ids` pass (`ProtonSyncEngine`; architecture.md §7).
- Why: v1 scope — the poller carries a watermark state machine, paging and its own failure
  modes for a latency win the calendar use-case does not need. The v2 upgrade path
  (including the model-event watermark) is documented in architecture.md §7.

## D-13 · MECHANISM · Pull-only, server-authoritative: no push, no conflict UI

- Reference: two-way. Local edits are pushed (`PUT …/events/sync`), conflicts raise
  notifications with keep-local/keep-remote actions, failed occurrence-cancels ride a
  15 min × 2^n retry ladder, and bulk deletions get their own approval flow
  (sync-algorithm.md §5/§7/§8; calendar-api.md §4.6).
- Ours: pull-only. Local edits to Proton-owned rows are overwritten by the next pull (and
  the settings UI does not yet warn about that — no revert-guidance copy exists); there is
  no push endpoint call, no conflict notification, no
  pending-cancellation queue (`ProtonSyncEngine`; operator decision `pull-only: confirm-pull`,
  architecture.md D3/§3).
- Why: the write path is where the reference's complexity blows up (encrypt-to-attendees,
  overwrite semantics, rejection counters). A correct mirror delivers the campaign's value;
  the push wave is explicitly future scope.

## D-14 · SECURITY · Human verification in Custom Tabs with a host allowlist

- Reference: opens the verification URL in an embedded in-app web activity
  (`CaptchaActivity`), with the URL host checked against the allowlist
  {proton.me, protonmail.com, hcaptcha.com} (auth-flow.md §2.7).
- Ours: the same allowlist check, but the flow hands the verified URL to the UI for a
  **Custom Tab** (external browser context) — credentials and HV tokens stay out of an
  in-process web context (`LoginFlow.hvOutcome`; security-audit §2.5.22).
- Why: an embedded WebView can be meddled with in-process and drags a web engine into the
  credential path; Custom Tabs keep the trust boundary with the system browser.
- Status: the URL plumbing and token replay are implemented; the Custom-Tab launcher
  screen is not yet wired into the settings section (documented gap, runbook
  "Known limitations").

## D-15 · SECURITY · Backup posture: named exclusions instead of blanket allowBackup=false

- Reference: ships `android:allowBackup="false"` (security-audit §6.1; RE evidence:
  manifest attribute).
- Ours: `allowBackup` stays true (Kairós has other user data worth backing up) with
  explicit exclusions in **both** rule files and both transfer modes: `file:proton-session/`
  and `database:proton-sync.db(-wal,-shm)` (`backup_rules.xml`, `data_extraction_rules.xml`;
  security-audit §2.4.19).
- Why: same protection for the bridge's secrets, without disabling backups for the rest of
  the app. Named excludes are reviewable and testable; the audit made them a blocker in the
  same commit as the first session-file write.

## D-16 · MECHANISM · Hand-rolled SRP-6a client instead of adopting ProtonCore auth

- Reference: bundles its own vendored Proton client stack (Retrofit/OkHttp + session
  manager) — not reusable by us anyway (cleanroom rule 1).
- Ours: `SrpClient` is written from the byte-exact recipe in auth-flow.md §2.3 using JCA
  `BigInteger`/SHA-512 plus BouncyCastle's bcrypt — no ProtonCore `auth` dependency
  (operator decision D1: framework weight, not license, is the reason — `auth-data`
  transitively pulls Proton's whole session/DI framework) (architecture.md §4/§5).
- Why: implementing a public protocol (RFC 5054 + Proton's encodings) from documentation
  is not "rolling your own crypto"; the primitives are standard, the seam (`SrpClient`
  behind an interface) allows swapping in ProtonCore later, and the security audit treats
  this module as the highest-risk component (constant-time proof compare, degenerate-input
  rejection, zeroization — all implemented and tested).

## D-17 · Gap (not policy) · Clock-skew warning not surfaced

- Reference: reads the `Date` response header of `auth/v4/info`; if the device is ≥20 s off
  server time it warns that TOTP codes will be rejected (auth-flow.md §2.2).
- Ours: `ClockSkewDetector` implements the check with tests, but the Retrofit DTO path does
  not expose response headers, so it is not wired into `LoginFlow` and no warning reaches
  the UI today.
- Why recorded here: this is a *known gap*, not an intentional policy difference — the
  detector shipped ahead of its integration point. Listed so the W3/W4 reviewer closes it
  deliberately (wire it via a header-observing call adapter) instead of re-discovering it.

## D-18 · SCOPE · No data-backup feature, no billing surface, no analytics

- Reference: ships an encrypted calendar/contacts export-to-folder backup feature with
  scheduling and retention, a web checkout + license flow, and a settings surface for both
  (storage.md §4.1/§6; Appendix A).
- Ours: none of the three. The only bridge settings are the sync-frequency selector,
  Sync now, Disconnect, and the status card (`ProtonCalendarSettingsSection`). No analytics
  SDK exists in Kairós (none added); counters are Room-stored and rendered on the card.
- Why: campaign non-goals + Kairós's own posture (no billing, no telemetry). Recorded so
  the absence is a decision on file, not an oversight.

---

## Changelog

- 2026-10-16 — initial divergence log (W3 docs, LOD-14). Reference behaviors cited from
  the wave-1 RE specs at their final revision; Kairós behaviors verified against
  `feat/proton-sync-bridge` at `a16827a`.
