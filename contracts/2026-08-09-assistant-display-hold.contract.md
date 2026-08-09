---
task: assistant-display-hold
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

**The glasses display physically switches OFF while the assistant is thinking.**
That is the owner's "black screen for 5 seconds", not a rendering gap. Proven by
his own run tonight (`E:\Tools\Rokid\tmp\ink-notice-path\g2.log`):

```
23:32:47.179 ROKIDBUS wake seq=... decision=refused reason=not_requested interactive=true kind=notice
23:32:51.689 SurfaceFlinger Setting power mode 0   <-- SCREEN OFF, 4.5 s after the notice
23:32:59.815 ROKIDBUS wake seq=-1 decision=wake reason=budget_available interactive=false kind=surface
23:32:59.829 SurfaceFlinger Setting power mode 2   <-- ink arrives and has to switch the screen back ON
23:33:00.408 ROKIDBUS notice display sleep skipped condition=surface_active
```

So: the assistant's "Thinking…" notice never requests a display hold
(`decision=refused reason=not_requested`), the ROM's display timeout fires
mid-thought, the screen goes dark, and the Ink answer arriving ~8 s later must
wake it again. Then the Ink page lays out WHILE the display is powering back on,
which is very likely the second bug: the card comes out compressed/clipped
(`broken-clipped.png` — "Ensoleillé" split across lines, values wrapped), while
the exact same document rendered via the DEBUG_INK harness is perfect
(`E:\Tools\Rokid\tmp\ink-morph-bug\harness-render-perfect.png`).

When this task is done: from the wake word until the answer is dismissed, the
display **never turns off**, and the Ink card renders identical to the harness
render — no compression, no clipping, no black gap.

# Non-goals

- No morph/animation. HEAD is the instant swap and it stays.
- No new wakelock type, no permanent screen-on, no change to other plugins'
  notice or wake behavior.

# Constraints

- **The owner has prescribed the mechanism — implement exactly this**: set
  `FLAG_KEEP_SCREEN_ON` on the **notice window itself**, and ONLY while the
  wearer has engaged a response (the listening / review-style modes where the
  wearer is actively in an exchange — identify the exact states in
  `NoticeController` / the assistant's notice modes). Not for ordinary notices,
  not permanently: the flag goes on when the engaged episode starts and comes
  off the moment it ends (answer dismissed, session closed, error, or the notice
  reverts to a non-engaged mode). Note `NoticeOverlayRenderer`'s current
  doctrine says the notice window never keeps the screen on and that wake is
  owned by `NoticeController` — this task deliberately amends that for the
  engaged modes only; keep the doctrine (and the comment) accurate afterwards.
- The hold MUST cover the whole think time (a slow model takes 12 s+) with no
  gap, and MUST NOT keep the screen on after the answer is gone. A 3 s
  `DisplayWakePolicy` wake cannot cover it — that is why the window flag is the
  right tool.
- MUST make the Ink layout correct even when it is presented right after a
  display power transition: do not trust bounds measured while the display is
  powering on. Build on the existing `InkLayoutSettlePolicy` /
  `InkPresentationGate` (added in `9f3026fc`) — they were not enough, find out
  why from the code and the log timing and make them hold until the metrics are
  genuinely final. Remember `750rpx = measured ink container width`, so a bad
  measurement silently shrinks every dimension of the page.
- MUST NOT pre-hide, pre-collapse, pre-scale or otherwise make the ink view
  invisible while waiting for anything: two earlier attempts shipped a card that
  never appeared. Worst case must be an instant, correct, fully visible card.
- MUST keep the display asleep-able for every other tier exactly as today; no
  other plugin's notices may start holding the screen.
- MUST add unit tests: the hold is requested when the assistant notice opens,
  survives long think times, is released on answer end/close/error; the layout
  gate does not release on metrics captured during a power transition.
- MUST NOT commit; no adb/device commands. If the Gradle lock or SDK throws
  AccessDenied, retry once, then stop and report (that error is caused by a
  concurrent build, not by the repo).

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Assistant suite/build/lint | test_commands[1] | BUILD SUCCESSFUL, 0 lint errors |
| 3 | New tests | — | display-hold lifecycle + layout gate across a power transition |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Manual (owner, wake-word path): say the wake word, ask a weather question; the
screen stays lit the whole time; the card appears complete and uncompressed.

# Context the executor cannot re-derive

- Branch `ink-surface`. Latest relevant commit: `9f3026fc` (layout settle +
  presentation gate + notice sleep policy). It did NOT fix the owner's report.
- Full logs of the failing run: `E:\Tools\Rokid\tmp\ink-notice-path\g2.log`
  (glasses) and `phone.log`; the earlier reproduction is in
  `E:\Tools\Rokid\tmp\ink-morph-bug\`.
- `DisplayWakePolicy` refuses when already interactive, rate-limits wake
  requests to a 5 s window, and acquires `SCREEN_BRIGHT_WAKE_LOCK |
  ACQUIRE_CAUSES_WAKEUP` for 3 s — a 3 s wake cannot cover a 12 s think, so a
  repeated/extended hold is required, not a single wake.
- `notice display sleep skipped condition=surface_active` proves the new sleep
  policy works once the surface is up; the hole is BEFORE that, during thinking.
- The assistant streams `/notice/update` every ~0.25-3 s while thinking
  (`AssistantUiController`), then shows the ink surface.
- The wake-word path cannot be triggered from adb (KEYCODE_ASSIST does not open
  our assistant; the debug ask channel needs an already-open session), so device
  validation is the owner's; make the unit tests carry the proof instead.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- Holding the display would need a new permission, a new wire message, or a
  protocol change — stop and report instead of inventing one.

# Autonomy

Executor decides: how to express the hold with existing primitives, where the
episode starts/ends, how to make the layout gate power-transition-safe, test
structure. Executor stops for: protocol/permission changes, animation, or any
change that could make the card invisible.
