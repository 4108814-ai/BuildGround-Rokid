---
task: relay-guardian-bind
date: 2026-08-09
status: active
scope_globs:
  - "plugins/relay/src/**"
  - "phone-hub/src/**"
  - "shared/src/main/java/com/anezium/rokidbus/shared/**"
  - "shared/src/test/**"
forbidden_globs:
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "**/CHANGELOG.md"
  - "glasses-hub/**"
  - "bus-client/**"
  - "sdk/**"
  - "docs/**"
  - "plugins/!(relay)/**"
test_commands:
  - "gradlew.bat :plugin-relay:testDebugUnitTest :phone-hub:testDebugUnitTest --console=plain"
  - "gradlew.bat :plugin-relay:lintDebug --console=plain"
  - "gradlew.bat :plugin-relay:assembleDebug :phone-hub:assembleDebug --console=plain"
max_failures: 2
---

# Goal

Notification capture in the Relay plugin survives aggressive OEM process killing
(reference case: Oppo Find N6, ColorOS 16, Android 16). Today the plugin's
`NotificationListenerService` process is dormant between glasses sessions; ColorOS
kills it force-stop-style (no `onListenerDisconnected`, package possibly stopped),
and capture silently dies until the user opens the Relay UI. When this contract is
done: (1) the phone hub holds a permanent guardian bind on a dedicated Relay
service for as long as the glasses link is up, so the Relay process shares the
hub's foreground importance and is revived by bind auto-recreate if killed;
(2) Relay detects a granted-but-disconnected listener and repairs it with the
correct unbind/rebind sequence; (3) both sides record enough redacted diagnostics
that a remote tester can prove what happened without adb.

# Non-goals

- Do NOT move the NotificationListenerService into the hub. That migration was
  evaluated and deliberately deferred; the guardian bind is the chosen design.
- No foreground service, no permanent notification, no wakelock, no AlarmManager,
  no JobScheduler anywhere in this change.
- No release preparation: no version bumps, no changelog entries, no tags.
- No redesign of existing Relay settings cards; you only ADD a diagnostics block.
- No changes to message capture/filtering/replay/display logic (SENT_LINGER,
  replay window, redaction detection, wake entitlements are all out of scope).

# Constraints

- MUST create a new `RelayGuardianService` in `plugins/relay`: a **plain
  `android.app.Service`** in the default process. It MUST NOT extend
  `NexusPluginService` and MUST NOT create a bus client — binding
  `RelayPluginService` for guardianship is forbidden (a second service instance
  would create a duplicate bus client under the same plugin id; see
  `bus-client/.../NexusPluginService.kt:53` and `NotificationControl.kt:20`).
- The guardian service MUST be exported and protected by a new **signature-level
  permission** (suggested name `com.anezium.rokidbus.permission.BIND_PLUGIN_GUARDIAN`).
  Declare the `<permission>` (protectionLevel `signature`) in BOTH the relay
  manifest and the phone-hub manifest (identical name; duplicate same-signature
  definitions are legal and dodge install-order grant issues), and add
  `<uses-permission>` in the hub manifest. All Nexus APKs share one signing key.
- Discovery MUST be generic platform plumbing, Relay just the first consumer: a
  new service-level `<meta-data>` key (add the constant to
  `shared/.../BusConstants` next to the existing plugin meta keys, e.g.
  `com.anezium.rokidbus.plugin.GUARDIAN_SERVICE`, value = the guardian service
  class name) on the plugin's PLUGIN-action service. The hub binds guardians only
  for plugins that are installed AND currently hold an approved grant (reuse the
  registry/grant machinery the hub already uses to enumerate plugins).
- Hub side: a guardian coordinator MUST bind with
  `Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT` (exactly the flag set the
  session bind already uses — see `AndroidExternalPluginRuntime.kt:47-54` for the
  precedent and the Freecess rationale). MUST NOT use `BIND_WAIVE_PRIORITY`,
  `BIND_NOT_PERCEPTIBLE`, `BIND_ABOVE_CLIENT`, or `BIND_ALLOW_ACTIVITY_STARTS`.
- Guardian bind lifetime: bound while the glasses link is up — same predicate the
  hub already uses at `BusHubService.kt:5021-5023`
  (`state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0`),
  hooked into the same link-state-change path. Add a short unbind linger
  (~30 s) so link flaps do not churn binds. On `onBindingDied`: unbind, then
  re-bind with bounded backoff (e.g. 1 s / 5 s / 30 s, cap 5 min). On
  `onServiceDisconnected`: keep the connection (the system restarts the service).
- Immediately BEFORE every (re)bind attempt the hub MUST read and record Relay's
  `ApplicationInfo.flags & FLAG_STOPPED` (reading it after the bind destroys the
  evidence). Record it in the hub's existing logging AND pass nothing — Relay's
  own diagnostics must not depend on the hub.
- Relay side, listener health: track listener lifecycle in a small persistent
  diagnostics store — connect generation counter, connectedSince, last
  `onListenerDisconnected`, last raw `onNotificationPosted` wall time, last
  accepted capture wall time. `RelayNotificationListener` already exists at
  `plugins/relay/.../RelayNotificationListener.kt` (`onListenerConnected` does an
  active-notification rebuild — keep that exactly as is).
- Repair state machine (runs in the guardian service via a main-thread `Handler`
  on **awake uptime only** — `postDelayed`, never alarms): every 5 minutes while
  the guardian lives, and immediately on guardian create and on CDM
  `onDeviceAppeared`: (1) check
  `NotificationManager.isNotificationListenerAccessGranted`; if revoked, record
  `NO_ACCESS` and do nothing (never loop, never toggle components); (2) if
  granted but no live listener connection, call
  `NotificationListenerService.requestRebind(component)`; (3) if no new connect
  generation within 15 s, on API 34+ call static
  `requestUnbind(component)` then `requestRebind(component)`; on API 30–33 use a
  live instance's `requestUnbind()` if one exists, else bare `requestRebind`
  again. Clean cycles are rate-limited to at most one per 15 minutes; repeated
  failures back off 1 min / 5 min / 15 min. Every transition goes to the ring
  buffer with a reason code.
- MUST NOT ever call `PackageManager.setComponentEnabledSetting` on the listener
  component (AOSP can observe the disabled state and permanently drop the user's
  notification-access approval).
- Diagnostics ring buffer: 128–256 events, persisted (SharedPreferences or a
  small file), containing ONLY state names, reason codes, timestamps, counters,
  generations. MUST NOT contain notification text, titles, sender names, source
  package names, reply tokens, MAC addresses, or serials. On guardian create,
  also snapshot the plugin's own recent
  `ActivityManager.getHistoricalProcessExitReasons` (API 30+) and, on API 35+,
  `ApplicationStartInfo` incl. `wasForceStopped()`, into the ring buffer.
- Relay settings UI: append a diagnostics card (NexusUi + BusTheme components
  ONLY — `docs/PLUGINS.md` §4 forbids stock widgets; follow the style of the
  existing cards in `RelaySettingsActivity.kt`) showing at least: notification
  access state, listener connected-since / generation, last raw callback, last
  accepted capture, guardian-bound state, last repair attempt + result, last
  process exit reason, force-stopped-before-start flag, companion link state
  split into Linked / observation registered / service bound. Plus a
  "Copy diagnostics" pill button that puts the ring buffer (redacted by
  construction) on the clipboard.
- CDM observation fix (bounded): `CompanionDeviceCoordinator` currently starts
  presence observation only for associations that expose a MAC, while the card
  says "Linked" for any association. On API 36+ register observation with
  `ObservingDevicePresenceRequest.Builder().setAssociationId(...)` so
  addressless associations are observed too; keep the address-based path for
  API 31–35. Record which path was used. MUST NOT collapse the existing
  `hasAssociation` (unfiltered) vs `associatedAddresses` (MAC-filtered)
  distinction — it deliberately prevents stacking a second association (a prior
  review "deduplicated" these two reads and shipped a bug; the code comment
  explains it).
- `RelayCompanionService.onDeviceAppeared`/`onDeviceDisappeared` MUST record
  events and trigger an immediate health evaluation (cheap intra-package call —
  do not add IPC).
- Plugins have **minSdk 30** and **no androidx dependencies** — platform APIs
  only, inline `checkSelfPermission`/SDK_INT guards in EVERY function that needs
  them (relay lint does not recognize `runCatching` or helper-wrapped guards;
  see the existing inline-guard style in `CompanionDeviceCoordinator.kt`).
- Kotlin, matching the surrounding code style. Comments only for constraints the
  code cannot express, in the voice of the existing comments.
- New logic MUST be unit-testable pure-JVM: extract the bind-lifetime decisions,
  repair state machine (timing injected), backoff, rate limiting, ring-buffer
  eviction, and diagnostics redaction into plain classes with tests in
  `plugins/relay/src/test` / `phone-hub/src/test`, mirroring how
  `SelfArmBootRepairPolicy` isolates policy from plumbing.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Relay + hub unit tests | `gradlew.bat :plugin-relay:testDebugUnitTest :phone-hub:testDebugUnitTest --console=plain` | BUILD SUCCESSFUL, new policy/state-machine tests included |
| 2 | Relay lint (minSdk 30 traps) | `gradlew.bat :plugin-relay:lintDebug --console=plain` | BUILD SUCCESSFUL, zero new errors |
| 3 | Both APKs compile | `gradlew.bat :plugin-relay:assembleDebug :phone-hub:assembleDebug --console=plain` | BUILD SUCCESSFUL |
| 4 | No androidx crept into the plugin | `git grep -l "androidx" -- plugins/relay/src` | no NEW files vs main (baseline: run on main first) |
| 5 | Guardian is not a bus service | `git grep -n "NexusPluginService" -- plugins/relay/src/main/java/com/anezium/rokidbus/plugin/relay/RelayGuardianService.kt` | no match |
| 6 | No forbidden keepalive tech | `git grep -nE "startForeground|WakeLock|AlarmManager|JobScheduler|setComponentEnabledSetting" -- plugins/relay/src/main phone-hub/src/main` | no NEW matches vs main |
| 7 | Diff stays in scope | `git diff --stat main` | only files under scope_globs |

# Plan sketch

1. `shared`: add the guardian meta-data key constant to `BusConstants`.
2. Relay: diagnostics store + ring buffer (pure class + persistence seam), tests.
3. Relay: listener lifecycle recording in `RelayNotificationListener` (minimal
   touch: record events; do not alter capture behavior).
4. Relay: repair state machine as a pure policy class + tests; then
   `RelayGuardianService` wiring it to a Handler, manifest entry, signature
   permission, guardian meta-data on the plugin service.
5. Relay: CDM observation fix + companion event recording + immediate health
   trigger.
6. Relay: settings diagnostics card + Copy diagnostics.
7. Hub: guardian coordinator (discovery via registry meta-data + grant check,
   bind/unbind on the link-state path, linger, backoff, FLAG_STOPPED pre-read,
   logging), policy extracted pure + tests, manifest permission bits.
8. Run all test commands; fix; commit in coherent steps as you go (commit
   messages in English, no AI attribution).

# Context the executor cannot re-derive

- Field failure: capture dies silently on ColorOS 16 despite notification access
  granted, battery Unrestricted, autostart on, CDM association Linked. Opening
  any Relay UI revives it. Root-cause model and design rationale (including why
  `requestRebind` alone is unreliable: it is a no-op while NMS still believes the
  listener is bound) come from a design report — do not re-litigate the design.
- The hub stays a foreground service while glasses are linked; its importance
  propagates through `BIND_IMPORTANT` (precedent + OEM-freezer rationale at
  `AndroidExternalPluginRuntime.kt:47-54`).
- Link-state predicate + change hook: `BusHubService.kt:5010-5029`; link bits are
  in `LinkStateBits`.
- `BusHubService.kt` is ~5400 lines; read only the regions you need.
- Existing settings cards to imitate: `RelaySettingsActivity.kt` (e.g.
  `notificationAccessCard()` at :204, harness card at :350).
- Relay's own past lint lessons: inline permission guards per function; no
  `androidx.annotation` available.
- `glasses-hub:lintDebug` fails on main for pre-existing reasons — do not touch
  glasses-hub at all.
- The Windows shell is PowerShell; `gradlew.bat` from the repo root.

# Escalation triggers (mechanical)

- Any test_command still failing after 2 fix attempts.
- Diff touches files outside scope_globs or matching forbidden_globs.
- The guardian design cannot be implemented without changing `bus-client`,
  `sdk`, or any gradle file — STOP and report; do not work around.
- Any need to weaken a MUST NOT (e.g. "just a short FGS", "just one alarm") —
  STOP and report.

# Autonomy

You may decide alone: exact class/file names (except `RelayGuardianService`),
ring-buffer encoding, event vocabulary, test structure, linger/backoff exact
values within the stated orders of magnitude, how the settings card lays out the
fields. You may NOT decide: anything listed in Constraints/Non-goals, new
dependencies, new permissions beyond the one specified, behavior changes to
capture/display paths.
