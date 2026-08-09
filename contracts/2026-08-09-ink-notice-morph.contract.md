---
task: ink-notice-morph
date: 2026-08-09
status: active
scope_globs:
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/**"
  - "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/**"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/**"
  - "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/**"
  - "shared/src/main/java/com/anezium/rokidbus/shared/**"
  - "shared/src/test/java/com/anezium/rokidbus/shared/**"
forbidden_globs:
  - "ink-engine/**"
  - "phone-hub/**"
  - "bus-client/**"
  - "plugins/sample/**"
  - "**/local.properties"
  - "settings.gradle.kts"
  - "gradle/**"
test_commands:
  - ".\\gradlew.bat :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug :glasses-hub:lintDebug --console=plain"
  - ".\\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-assistant:lintDebug --console=plain"
  - ".\\gradlew.bat :shared:testDebugUnitTest --console=plain"
max_failures: 2
---

# Goal

The owner's exact wish, on hardware: while the Assistant is thinking, its notice
band shows "Thinking…"; the moment the answer's Ink page is ready, **the band
visually becomes the Ink window** — one continuous grow animation from the
band's card into the Ink card, no flash, no double-render, no dead frame. The
wearer perceives a single object that thinks, then expands into the answer.

# Non-goals

- No Ink rendering inside the notice tier itself (the notice window is
  non-focusable/non-touchable; Ink needs input — the Ink content must end up
  owned by the foreground surface tier exactly as today).
- No wire-protocol changes beyond one additive field/reason listed below; no
  SDK/public API changes; no engine changes.
- No general cross-tier morph framework — this is one choreographed handoff:
  assistant notice → same-owner Ink surface. Other notices keep today's
  behavior byte for byte.

# Constraints (the architecture is FIXED — implement it, do not redesign it)

- MUST implement the morph as a **placeholder swap inside the surface host**,
  not by animating the notice window: when `SurfaceController` is about to
  present a `kind:"ink"` surface whose OWNER also has the assistant notice
  band visible, the surface layer first renders a placeholder styled
  identically to the current notice band (same text, same card chrome, same
  on-screen bounds — the notice tier must expose its current band snapshot:
  text + measured bounds), the real notice is hidden in the same frame
  (a new close reason, see below), then the placeholder animates its bounds
  into the Ink card's final bounds while the Ink content cross-fades in.
  Plan 013 doctrine applies: never animate window layout params; animate child
  bounds inside the fixed host window; one HudMotionValue drives the whole
  morph (`HudMotion.STANDARD_MS`, enter interpolator; retarget/cancel-safe).
- MUST make the swap atomic and idempotent: if the notice is gone before the
  ink show arrives (timeout, phone-side hide racing), skip the morph and show
  Ink exactly as today. If Ink fails after the morph started, finish by
  restoring nothing — the failsafe/close paths from slice 2/3 stay intact.
- MUST add ONE additive notice close reason (e.g. `handoff`) on the existing
  `/notice/closed` payload (shared constants) so the phone/assistant can tell
  a morph from a timeout; the assistant treats `handoff` like its own hide
  (no error, no retry).
- MUST rework the assistant side minimally on top of commit `7007a6cd`
  (`onInkAnswerShown` currently dismisses the notice phone-side): the phone
  MUST NOT race the glasses' swap — on ink-shown it stops keepalive/updates
  and suppresses the redundant final render as today, but leaves the visible
  band's removal to the glasses handoff (with a bounded fallback: if no
  `handoff` close arrives within ~2 s, hide it phone-side as today).
- MUST detect the owner match glasses-side using existing state only (the
  notice tier knows its owner — `notice closed owner=assistant` is already
  logged; the surface envelope carries `ownerPluginId`). No new phone→glasses
  coordination messages beyond the surface show that already flows.
- MUST keep every existing test green; MUST add unit tests for: owner-match
  detection, morph-skipped-when-no-notice, close-reason emission, assistant
  fallback timer, and the placeholder geometry math (Android-free where
  feasible, `InkRenderLogicTest` style).
- MUST NOT touch DPAD/BACK semantics: during the morph the incoming ink
  surface is already the active surface (input as today); the morph is purely
  visual.
- MUST NOT commit; no adb/device commands; transient SDK AccessDenied → retry
  once then stop.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Assistant suite/build/lint | test_commands[1] | BUILD SUCCESSFUL, 0 lint errors |
| 3 | Shared suite | test_commands[2] | BUILD SUCCESSFUL |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Manual (reviewer, on device): assistant question that triggers a template →
"Thinking…" band grows into the Ink card in one continuous motion; unrelated
notice over an Ink page unchanged; notice timeout without ink unchanged;
ink render with NO prior notice unchanged.

# Plan sketch

1. Notice tier: expose current band snapshot (owner, text, bounds) + a
   `closeForHandoff()` that emits the new reason.
2. SurfaceController/SurfaceHudView: on ink show with owner match, capture
   snapshot, insert morph placeholder view, same-frame close the notice,
   drive one HudMotionValue from band bounds → measured ink bounds with
   content cross-fade, then remove the placeholder.
3. Shared: the additive close reason constant.
4. Assistant: stop hiding the band eagerly on ink-shown; add the 2 s fallback;
   map `handoff` close to silent success.
5. Tests; full verification; report.

# Context the executor cannot re-derive

- Branch `ink-surface`, all of plan 020 M1-M3 + `7007a6cd` landed. Read that
  commit first — this task refines its behavior.
- `NoticeOverlayRenderer` window is non-focusable and closes by removing only
  its window (:229). Notice routing precedes surface routing in
  `GlassesHub` (:288). `HudOverlayStack` reasserts notice-above-surface
  ordering when surface windows are (re)created (:28) — the placeholder swap
  must account for one frame where both could exist: hide the notice FIRST in
  the same main-thread batch, then reveal the placeholder, so z-order can
  never show both.
- `HudMotionValue` handles one float with retarget/cancel (`HudMotion.kt`);
  motion conventions in `plans/013-hud-motion.md:42-84` (no decorative loops,
  180/280/240 ms vocabulary).
- The ink card's final bounds are known after the `InkHudView` layout pass —
  the morph needs a post-layout hook (the slice-2 renderer already signals
  first-frame commit for the `ready` event; reuse that timing).
- The overlay hosts both `SurfaceHudView` and the notice as SEPARATE windows
  under the accessibility service; the placeholder must live inside
  `SurfaceHudView`'s window (full-screen) so child-bounds animation is free.
- Assistant timings: notice updates stream every ~0.25-3 s while thinking;
  `onInkAnswerShown` (AssistantUiController.kt:227) is the current dismissal
  point added by `7007a6cd`.
- Trap: `align-items: baseline` in FlexboxLayout clips mixed-size text rows —
  irrelevant to the placeholder (raw views), listed so you do not "fix" it.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff touches files outside scope_globs.
- The band snapshot cannot be captured without notice-tier redesign — stop and
  report the minimal API you would need instead of building a redesign.

# Autonomy

Executor decides alone: placeholder view construction, exact cross-fade
curve inside the fixed vocabulary, test structure. Executor stops for:
anything requiring a new phone→glasses message, input-semantics changes, or
notice-tier behavior changes visible to other plugins.
