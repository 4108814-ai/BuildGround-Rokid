---
task: ink-notice-path-bugs
date: 2026-08-09
status: active
scope_globs:
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/**"
  - "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/**"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/**"
  - "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/**"
forbidden_globs:
  - "ink-engine/**"
  - "phone-hub/**"
  - "bus-client/**"
  - "shared/**"
  - "plugins/sample/**"
  - "**/local.properties"
test_commands:
  - ".\\gradlew.bat :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug :glasses-hub:lintDebug --console=plain"
  - ".\\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-assistant:lintDebug --console=plain"
max_failures: 2
---

# Goal

Three bugs, ALL on the **notice-triggered path** (wearer says the wake word →
assistant answers → Ink card). They do NOT reproduce when the assistant plugin
is opened from the Nexus launcher first, which is why they survived earlier
fixes. On the current HEAD (morph reverted, instant swap) the owner still sees:

1. **Clipped / compressed Ink layout.** Proof: `E:\Tools\Rokid\tmp\ink-notice-path\broken-clipped.png`
   — "Ensoleillé" broken across two lines as "Ensoleill/é", "H 32 °C" and
   "L 19 °C" wrapping, the header row truncated to dots, "Precip Faible"
   squeezed. The SAME document rendered through the DEBUG_INK harness is
   pixel-perfect: `E:\Tools\Rokid\tmp\ink-morph-bug\harness-render-perfect.png`.
   So the Ink content is measured/laid out at the WRONG (too narrow / wrong
   inset) width on the notice path and never re-measured.
2. **Black gap.** The screen goes black for seconds between the "Thinking…"
   notice disappearing and the Ink card appearing.
3. **Display sleeps during/after the answer.** The screen turns off while the
   assistant session is still live and the Ink answer is showing. The assistant
   is supposed to hold the display awake for its answer. Glasses logs show
   `notice display sleep skipped condition=no_wake_ownership` in earlier runs —
   investigate wake ownership between the notice tier (`NoticeController` /
   `NoticeSleepPolicy` / `DisplayWakePolicy`) and the Ink surface show: whoever
   owns the wake episode when the notice hands over must not let the display
   sleep while the Ink surface is foreground.

When done: wake-word → answer → the Ink card renders IDENTICAL to the harness
render (nothing clipped/wrapped), with no black gap, and the display stays on
for the whole answer.

# Evidence (read these first)

- `E:\Tools\Rokid\tmp\ink-notice-path\glasses.log`, `phone.log` — full logs from
  the owner's failing runs tonight (notice path), ~22:45-22:58.
- `E:\Tools\Rokid\tmp\ink-notice-path\broken-clipped.png` — the clipped result.
- `E:\Tools\Rokid\tmp\ink-morph-bug\harness-render-perfect.png` — the correct
  target layout for the same weather document.
- `E:\Tools\Rokid\tmp\ink-morph-bug\glasses.log` / `phone.log` — an earlier
  reproduction with the morph still in.

# Root-cause hypotheses (confirm/refute with logs + code)

- H1 (clip): on the notice path, `SurfaceHudView`/`InkHudView` measure while the
  notice band is up and/or before `HudTopInset` settles, so the ink container's
  width/height (and therefore `rpx` resolution — 750 rpx = measured ink
  container width) are wrong; nothing forces a re-layout afterwards. Compare the
  measured container width on both paths. The launcher path warms the surface
  first, which is why it looks right there.
- H2 (black): the notice window is torn down before the Ink surface's first
  frame paints (assistant-side hide and/or notice TTL), leaving nothing drawn.
- H3 (sleep): the Ink surface show does not (re)acquire or extend the display
  wake episode that the notice owned, so `DisplayWakePolicy`'s episode expires
  mid-answer; possibly `wake decision=refused reason=already_interactive` means
  no new episode is created and the notice's episode then ends.

# Constraints

- MUST fix all three so the notice path matches the harness render exactly. The
  Ink content MUST be laid out at the full, correct surface bounds and MUST be
  re-measured/re-laid-out if the insets or the notice teardown change the
  available space (force a relayout after the notice window is gone, before or
  as the ink is revealed).
- MUST NOT reintroduce the morph animation. HEAD is the reverted instant swap
  (`0fcbc19d`, `13dfbc9c`) and that stays: the swap may be instant. Any future
  animation is out of scope for this task. NEVER pre-collapse, pre-hide, or
  pre-scale the ink view: the previous two attempts made the card invisible
  exactly that way. The ink view is always full-size and fully visible when the
  surface is active.
- MUST keep the display awake for the whole assistant answer while the Ink
  surface is foreground, using the EXISTING wake policy primitives — do not
  invent a new always-on wakelock, and do not keep the screen on after hide.
- MUST NOT change wire protocols, grants, the ink engine, the templates, or any
  forbidden glob. Notices for other plugins keep their exact behavior.
- MUST add unit tests for whatever you fix (layout/inset resolution math,
  wake-episode handoff decision, teardown ordering) in the Android-free style
  used by `InkRenderLogicTest`/`NoticeSleepPolicy` tests.
- MUST NOT commit; no adb/device commands. Transient SDK AccessDenied → retry
  once then stop and report.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Assistant suite/build/lint | test_commands[1] | BUILD SUCCESSFUL, 0 lint errors |
| 3 | New tests | — | cover the clip cause (correct measured width/inset on the notice path), the teardown ordering (no empty frame), and the wake-episode continuity |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Manual (reviewer, on device, wake-word path — NOT the launcher path): say the
wake word, ask a weather/numeric question, confirm the card matches the harness
render, no black gap, display stays on until the answer is dismissed.

# Context the executor cannot re-derive

- Branch `ink-surface`. HEAD has the morph REVERTED; do not resurrect it.
- The two paths differ: launcher-open warms the surface window/plugin foreground
  first; the wake-word path shows a notice, then the ink surface arrives cold.
  Reproduce the difference in reasoning from the logs (`launcher-return show`
  lines appear on the launcher path).
- `rpx` maps 750 rpx = measured ink container width (plan 020 §4) — a wrong
  measured width silently shrinks every dimension in the page, which is exactly
  what the clipped screenshot looks like.
- `HudTopInset.observe` drives the surface root padding and can change after
  attach; `SurfaceHudView.applyHudTopInset` requests layout.
- `DisplayWakePolicy.requestWake(..., SURFACE, requested=true)` runs on every
  full show; it refuses when already interactive and rate-limits to a 5 s window;
  `NoticeSleepPolicy` skips sleep when it does not own the wake episode.
- The assistant answer path: `AssistantUiController` streams the notice, then
  the ink tool shows the surface; `onInkAnswerShown` stops the stream.
- Trap: `align-items: baseline` clips mixed-size rows in FlexboxLayout (already
  avoided in the template — do not "fix" the template, it is proven correct by
  the harness render).

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- A fix would require a new phone→glasses message or a protocol change — stop
  and report instead.

# Autonomy

Executor decides: how to force the correct measure/relayout, teardown ordering
details, wake-episode mechanism within existing primitives, test structure.
Executor stops for: anything needing protocol/grant/engine/template changes, or
any temptation to re-add the morph.
