---
task: assistant-episode-hold
date: 2026-08-10
status: active
scope_globs:
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/**"
  - "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/**"
forbidden_globs:
  - "ink-engine/**"
  - "phone-hub/**"
  - "bus-client/**"
  - "shared/**"
  - "plugins/**"
  - "**/local.properties"
test_commands:
  - ".\\gradlew.bat :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug :glasses-hub:lintDebug --console=plain"
max_failures: 2
---

# Goal

Make the glasses panel stay lit for an entire assistant exchange, including
follow-up questions, by giving the display hold **one owner for the whole
episode** instead of passing it between the notice band and the Ink card.

The owner has now hit this three times in a row and is done with incremental
patches. This task is the definitive pass: design it so there is nothing left to
hand over, then prove it with tests and logs.

# Why the current design keeps failing

The hold has been owned by *whoever is drawing*:

- `89baf64d` gave the hold to the engaged notice. It covered the thinking band
  and died when the card appeared.
- `b67ecc82` (HEAD) transfers the hold from the notice to the Ink surface at the
  morph. That closes the measured gap, but it keeps two owners and a handover,
  so every future path that forgets to transfer reintroduces the same bug.

Measured failure that motivated the transfer, from the owner's device:

```
01:53:36.159 hold decision=release reason=notice_owner owner=assistant:notice
01:53:36.162 morph decision=animate reason=first_frame bandPx=75 cardPx=151
01:53:36.177 SurfaceFlinger Setting power mode 0
```

And the first answer of the same session, where nothing covered the card either
— it only survived because the ROM timer had not expired yet:

```
01:52:52.291 hold decision=release reason=notice_owner owner=assistant:notice
01:52:52.294 morph decision=animate reason=first_frame bandPx=75 cardPx=272
01:53:11.239 SurfaceFlinger Setting power mode 0     (card still on screen)
```

# Design (decided by the owner — implement this)

Introduce an explicit **engaged assistant episode** in the glasses hub, and let
the episode own the display hold.

- The episode **begins** at the first engaged assistant notice (the existing
  engaged signal — `NoticeDisplayHoldPolicy.noticeHoldsDisplay`).
- The episode **continues** across everything that happens inside it: band
  updates, the morph, the Ink card, a follow-up question that shows a new
  engaged band, and the card that replaces the previous card. None of these
  acquire, release, or transfer anything. A follow-up must extend the same
  episode, not restart it and not end it.
- The episode **ends**, releasing the hold exactly once, when: the assistant
  session closes, the wearer dismisses the surface, a non-assistant surface or a
  different plugin's engaged notice takes over, the link drops, the accessibility
  service is destroyed, or the safety ceiling fires.
- The hold is acquired once at episode start and released once at episode end.
  There is no `transfer` in the final design: remove it, or reduce it to an
  internal detail that cannot be forgotten by a future caller.

The ceiling is the leak guard and stays. Because a follow-up extends the
episode, the ceiling MUST be expressed so that a long conversation cannot hold
the panel forever: cap the *continuous* hold (for example the ceiling is
re-armed by genuine wearer engagement — a new question — but never by the hub's
own redraws), and document the rule you chose in a comment. State the worst case
explicitly in the report.

# Also required: review the whole path

Before implementing, read the engaged-episode path end to end — the assistant's
engaged flag, `NoticeController`, `NoticeOverlayRenderer`, `DisplayWakePolicy`,
`SurfaceController`, `SurfaceHudView`'s Ink presentation and morph — and list
every place the panel could go dark or the hold could leak. Fix the ones inside
scope. Report the ones you deliberately left, with the reason.

Specifically check, and state a verdict on each in the report:

1. The Ink card path where no band was showing (`morph decision=instant reason=no_matching_band`):
   does the episode still hold? Should it?
2. The 500 ms morph deadline path and the renderer-error path.
3. Concurrency: `DisplayWakePolicy` is `@Synchronized` but the notice and surface
   controllers run on the main looper — is any hold decision made off it?
4. Whether any release path can run twice, or zero times.

# Constraints

- MUST NOT introduce an Activity, a new window, a new window type, or new window
  flags. The card stays a child view of the existing surface overlay.
- MUST NOT change the wire protocol, grants, capabilities, the Ink engine, the
  templates, or anything under `forbidden_globs`. If the design appears to need
  a new wire field, STOP and report instead of inventing one.
- MUST NOT hold the display for anything that is not an engaged assistant
  episode. A plugin opened from the launcher, an ordinary notice, and a
  non-assistant surface must behave exactly as they do today.
- MUST NOT regress `fb0ccf59`: the card keeps the notice band's footprint, the
  host paints no chrome, the morph animates from the band on the first drawn
  frame and commits instantly on the 500 ms deadline.
- MUST keep the `ROKIDBUS hold ...` log line shape, and log the episode
  boundaries so a device run is diagnosable from logcat alone: one acquire with
  its reason, renewals, and one release naming the real end reason. Required.
- MUST add unit tests covering: episode spans band -> card -> follow-up band ->
  card with no release in between; release on every end path; ceiling enforced;
  launcher Ink and ordinary notices never hold; no double release.
- MUST NOT commit; no adb, no device commands, no gradle outside test_commands.
  If Gradle throws `AccessDenied` on `.gradle\...\fileHashes.lock`, that is a
  concurrent build: retry once, then stop and report.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Episode tests | — | continuous hold across a two-question exchange, every end path, ceiling, no double release |
| 3 | Review findings | — | the four questions above answered in the report |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Device validation is done by the requester with two consecutive questions; the
tests and the log lines must make the outcome readable without guessing.

# Context the executor cannot re-derive

- Branch `ink-surface`, worktree `E:\Tools\Rokid\wt-ink`, HEAD `b67ecc82`.
- `DisplayWakePolicy` holds `SCREEN_BRIGHT_WAKE_LOCK` with a lease equal to the
  remaining ceiling; renewals re-assert it. The one-shot `requestWake` path with
  its 5 s rate limit is a different, older mechanism — do not confuse them.
- `FLAG_KEEP_SCREEN_ON` on both the notice and the surface window does NOT stop
  this panel: measured on this firmware. Only the wake lock does. Keep the flags,
  never rely on them.
- Rokid's firmware forces `screen_off_timeout=5000` and re-asserts it at every
  boot, so any gap in the hold is visible within seconds.
- The morph handover lives in `SurfaceHudView.onInkFirstFrame` ->
  `NoticeController.closeForInkMorph`, which is where `b67ecc82` put the
  transfer. The episode design should make that call site stop caring about the
  display entirely.
- The episode can be reproduced without voice: open Assistant from the glasses
  launcher, then broadcast the assistant debug ask on the phone, twice in a row.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- The design would need a protocol change, an Activity, a new window, or a new
  permission — stop and report.
- You cannot guarantee exactly-once release on some path — report it rather than
  shipping a hold that can leak.

# Autonomy

Executor decides: how the episode is represented, where it lives, ceiling
semantics, whether `transfer` disappears entirely, test structure. Executor
stops for: protocol changes, new windows, morph or geometry changes, or any
design that could leak the hold or leave the panel dark mid-answer.
