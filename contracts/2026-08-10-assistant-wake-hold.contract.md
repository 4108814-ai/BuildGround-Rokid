---
task: assistant-wake-hold
date: 2026-08-10
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

The glasses panel still switches OFF mid-answer. `FLAG_KEEP_SCREEN_ON` on the
notice window (commit `ec2015b5`) did **not** stop it. Make the display stay on
from the moment the wearer engages the assistant until the answer is dismissed,
using the mechanism that is PROVEN to control this panel: the wake lock in
`DisplayWakePolicy`.

# Hard evidence (owner's run, `E:\Tools\Rokid\tmp\ink-notice-path\g3.log`)

```
23:48:01.842 ROKIDBUS notice state=shown seq=1786310969673 ttlMs=4000
23:48:01.892 ROKIDBUS wake seq=... decision=refused reason=not_requested interactive=true kind=notice
23:48:02.030 .. 23:48:05.282  ROKIDBUS remote RX /notice/update  (band still updating, every ~0.3-2 s)
23:48:06.288 DisplayPowerController Brightness reason changing to: 'manual [ dim ]'
23:48:06.306 DisplayPowerController Brightness reason changing to: 'screen_off'
23:48:06.320 SurfaceFlinger Setting power mode 0        <-- PANEL OFF while the band is live
23:48:06.358 .. 23:48:07.822  ROKIDBUS remote RX /notice/update  (updates keep arriving in the dark)
23:48:14.639 ROKIDBUS wake seq=-1 decision=wake reason=budget_available interactive=false kind=surface
23:48:14.654 SurfaceFlinger Setting power mode 2        <-- ink arrives and powers the panel back on
```

Two facts follow:
1. The panel dies from the ROM's **user-inactivity timeout** (dim → screen_off),
   not from our `NoticeSleepPolicy` (no ROKIDBUS sleep line precedes it), and the
   notice window's `FLAG_KEEP_SCREEN_ON` did not prevent it.
2. Our wake lock DOES drive this panel: at 23:48:14 a `decision=wake` is
   immediately followed by `power mode 2`. That is the mechanism that works here.

Also note the assistant notice logs `reason=not_requested`: the notice path never
even asks for a wake.

# Constraints

- MUST introduce an explicit, renewable **display hold** in `DisplayWakePolicy`
  (acquire/renew/release), and hold it for the whole assistant engaged episode:
  from the engaged notice (Listening / Transcribing / Thinking / streaming
  answer) until the episode ends (answer dismissed, surface hidden, session
  closed, error shown and expired). `DisplayWakePolicy` currently acquires
  `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` for 3 s and rate-limits wake
  requests to a 5 s window — a one-shot 3 s wake cannot cover a 12 s think, so
  the hold must renew itself (or use a held lock released explicitly) and must
  bypass the 5 s rate limit for renewals of an active hold.
- MUST release the hold reliably in EVERY exit path, including link loss, plugin
  crash, session close, and glasses-hub service destruction. A leaked hold that
  keeps the panel on forever is a worse bug than the one being fixed — add a
  hard safety ceiling (e.g. the episode cannot hold beyond ~90 s) after which the
  hold is dropped and logged.
- MUST log the hold transitions (acquire / renew / release / ceiling) with the
  same `ROKIDBUS` log style used by the existing wake lines, so the next device
  run can be diagnosed from logcat alone. This is required, not optional.
- KEEP the existing `FLAG_KEEP_SCREEN_ON` work from `ec2015b5` (it is harmless
  and may help on other firmware), but it MUST NOT be the only mechanism.
- MUST NOT hold the display for ordinary notices from other plugins, and MUST
  NOT keep the panel on after the assistant episode ends.
- MUST NOT touch the ink engine, templates, protocols, grants, or forbidden
  globs. No morph/animation work. Never pre-hide or pre-collapse the ink view.
- MUST add unit tests: hold acquired on engaged notice, renewed across a long
  think, released on each exit path, ceiling enforced, ordinary notices never
  hold.
- MUST NOT commit; no adb/device commands. If Gradle throws AccessDenied on
  `.gradle\...\fileHashes.lock`, that is a concurrent build — retry once, then
  stop and report.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Assistant suite/build/lint | test_commands[1] | BUILD SUCCESSFUL, 0 lint errors |
| 3 | New tests | — | hold lifecycle incl. renewal, every release path, ceiling |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Manual (owner, wake-word path): ask a question that takes 10 s+; the panel never
dims or switches off between the notice and the answer, and switches off
normally some time after the answer is dismissed.

# Context the executor cannot re-derive

- Branch `ink-surface`. Prior attempts: `9f3026fc` (layout settle + presentation
  gate), `ec2015b5` (notice-window KEEP_SCREEN_ON + engaged modes). Neither
  stopped the panel from switching off — this task supersedes their display
  handling without reverting them.
- The assistant's engaged modes already exist as `AssistantNoticeMode.ENGAGED`
  (`AssistantUiController`), sent over the wire as the notice's `interactive`
  flag, and mapped glasses-side by `NoticeDisplayHoldPolicy.noticeHoldsDisplay`
  (`NoticeOverlayRenderer.kt:26-31`). Reuse that engaged signal for the hold; if
  you find that the signal does not actually arrive glasses-side (verify by
  reading the code path end to end), fix that too and log it.
- The notice TTL is 4 s and is refreshed by the assistant's keepalive; the band
  itself stays alive across the think — the band is NOT the problem, the panel
  power is.
- `NoticeSleepPolicy` already skips sleeping while a surface is active
  (`condition=surface_active` in the logs) — that part works, leave it.
- The wake-word path cannot be triggered from adb, so device validation is the
  owner's; the unit tests and the new log lines must carry the proof.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- Holding the display would need a new permission or a protocol change — stop
  and report instead of inventing one.

# Autonomy

Executor decides: the hold API shape, renewal cadence, where the episode starts
and ends, test structure. Executor stops for: permission/protocol changes, any
animation work, or anything that could leave the panel on indefinitely.
