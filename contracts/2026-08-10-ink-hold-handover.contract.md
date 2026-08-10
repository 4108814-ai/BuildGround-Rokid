---
task: ink-hold-handover
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

The display hold covers the assistant's thinking band but is dropped the instant
the band hands over to the Ink card, so the panel dies while the answer is on
screen. Make the hold span the whole engaged episode — band **and** card — with
no gap at the handover.

# Hard evidence (owner's device, tonight, build 1.2.99-ink)

Second question of a session, the failing one:

```
01:53:36.159 ROKIDBUS hold seq=... decision=release reason=notice_owner held=true ageMs=12889 owner=assistant:notice
01:53:36.159 ROKIDBUS notice state=closed seq=... reason=owner
01:53:36.162 ROKIDBUS morph decision=animate reason=first_frame bandPx=75 cardPx=151
01:53:36.177 SurfaceFlinger Setting power mode 0        <-- panel off, 18 ms into the morph
01:53:42.243 SurfaceFlinger Setting power mode 2
```

First question of the same session, same shape, only luckier:

```
01:52:52.291 ROKIDBUS hold ... decision=release reason=notice_owner ageMs=14317 owner=assistant:notice
01:52:52.294 ROKIDBUS morph decision=animate reason=first_frame bandPx=75 cardPx=272
01:53:11.239 SurfaceFlinger Setting power mode 0        <-- panel off 19 s later, card still up
```

So the card is never covered. It survived the first time only because the ROM's
inactivity timer had not expired yet. The surface window already carries
`FLAG_KEEP_SCREEN_ON` and it does NOT stop this panel — that was measured on this
firmware in an earlier task; only the `DisplayWakePolicy` wake lock controls it.

# Constraints

- The engaged episode MUST hold the display continuously from the engaged notice
  through the Ink card that succeeds it. At the handover the hold is
  **transferred**, never released and re-acquired: there must be no window, not
  even one frame, in which nothing holds the panel.
- The hold MUST be released when the episode genuinely ends: the Ink surface is
  hidden or replaced by a non-assistant surface, the session closes, the link
  drops, the service is destroyed, or the existing safety ceiling fires. A
  leaked hold that keeps the panel lit forever is worse than the bug being fixed.
- KEEP the existing ~90 s absolute ceiling as the leak guard. Decide and state in
  a comment whether the ceiling runs from the episode start or restarts at the
  handover; if it restarts, the total must still be bounded (no unbounded chain
  of renewals). A card left on screen past the ceiling falls back to the ROM
  timeout, which is acceptable.
- MUST NOT hold the display for Ink surfaces that are not part of an engaged
  assistant episode (a plugin opened from the launcher must behave exactly as it
  does today), and MUST NOT change any other plugin's notice or wake behaviour.
- MUST log the handover with the existing `ROKIDBUS hold ...` line shape, with a
  decision that names it (for example `decision=transfer owner=assistant:surface`)
  and a release line that names the real end reason. Required, not optional: the
  next device run has to be diagnosable from logcat alone.
- MUST NOT touch the morph itself, the card geometry, the Ink engine, the
  templates, the protocol, or anything under `forbidden_globs`. If the handover
  appears to need a new wire field, STOP and report instead of inventing one.
- MUST NOT regress `fb0ccf59`: the card still animates from the band and still
  commits instantly on the 500 ms deadline.
- MUST add unit tests: hold transferred at the handover with no released state in
  between; released on each end path (surface hidden, replaced, session closed,
  link loss, service destroyed); ceiling still enforced; a launcher-opened Ink
  surface never holds.
- MUST NOT commit; no adb, no device commands, no gradle outside test_commands.
  If Gradle throws `AccessDenied` on `.gradle\...\fileHashes.lock`, that is a
  concurrent build: retry once, then stop and report.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Handover tests | — | continuous hold across band -> card, every release path, ceiling |
| 3 | Diff scope | `git status --short` | only scope_globs files |

Manual (owner, wake-word path): ask a question, get a card, ask a follow-up; the
panel never switches off during either answer, and switches off normally some
time after the card is dismissed.

# Context the executor cannot re-derive

- Branch `ink-surface`, worktree `E:\Tools\Rokid\wt-ink`, HEAD `fb0ccf59`.
- `DisplayWakePolicy` owns the renewable hold (`DisplayHoldPhase { HELD,
  SUSPENDED, CEILING }`, ~90 s ceiling) added in `89baf64d`. The owner label
  `assistant:notice` and the release reason `notice_owner` come from there and
  from `NoticeOverlayRenderer`/`NoticeController`.
- The handover happens in `SurfaceHudView.onInkFirstFrame`: it calls
  `NoticeController.closeForInkMorph(token)` (which closes the notice, and that
  close is what drops the hold today) and then animates the clip. That is the
  seam where the transfer belongs.
- `NoticeDisplayHoldPolicy.noticeHoldsDisplay(surfaceId, engaged)` decides which
  notices hold; the assistant marks its listening/thinking/answer episode as
  engaged. The Ink card that follows belongs to the same episode and the same
  owner plugin (`ownerPluginId`, now parsed glasses-side for both the notice and
  the surface).
- Ink surfaces always take the overlay path now (`surfaceDisplayPath`), so the
  card is a child view in the surface overlay window, not an Activity.
- The Rokid firmware forces `screen_off_timeout=5000` and re-asserts it at every
  boot, so once the hold is gone the panel dies within seconds. That is why the
  gap is immediately visible rather than theoretical.
- The wake-word path cannot be triggered from adb, but the whole episode CAN be
  reproduced without voice: open Assistant from the glasses launcher, then
  broadcast the assistant debug ask on the phone. Device validation is still the
  owner's; the unit tests and the log lines must carry the proof.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- The transfer would need a protocol change, a new permission, or a new window —
  stop and report.
- You cannot guarantee release on some end path — report it rather than shipping
  a hold that can leak.

# Autonomy

Executor decides: how the transfer is expressed, where the episode is considered
ended, ceiling semantics, test structure. Executor stops for: protocol changes,
morph or geometry changes, or any design that could leak the hold.
