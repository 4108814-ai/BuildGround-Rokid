---
task: ink-card-overlay-morph
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

Two changes to how an Ink surface reaches the wearer's eye, both glasses-side
only.

1. **The Ink page must be a floating card superimposed on the display, not a
   full-screen takeover.** Today it fills the panel with an opaque background.
   It must occupy the same footprint as the notice band and let the rest of the
   panel stay dark/transparent.
2. **The notice band must visibly become the card.** Today the band disappears
   and a full-screen window appears: a hard cut. It must read as one continuous
   motion, in the existing HUD motion vocabulary.

When this is done: the wearer says the wake word, sees the thinking band, and
that band grows into the weather card in place, on the same top anchor, over a
dark panel — no full-bleed window, no cut, no black gap, no clipped text.

# Why the previous three attempts failed (do not repeat these)

- Attempt A animated the **Ink view's own layout size**. The Ink engine measures
  `750rpx = the measured container width`, so re-measuring mid-animation
  silently shrank every dimension and the text came out clipped
  ("Ensoleillé" split across lines).
- Attempt B **pre-collapsed / pre-hid** the Ink view waiting for a signal that
  sometimes never arrived: the card never appeared at all.
- Attempt A also closed the notice **before** the Ink's first frame: the panel
  went black for several seconds between the two.

All three were reverted. HEAD is the clean instant swap and it is the fallback
this task must preserve.

# Design (decided by the owner — implement this, do not redesign)

## Card geometry

- The Ink surface renders as a card inside the existing surface overlay window.
  The window stays `MATCH_PARENT`, `TYPE_ACCESSIBILITY_OVERLAY`, `TRANSLUCENT`,
  focusable (Ink tap actions depend on key events reaching
  `SurfaceController.handleKeyEvent`). **Do not create an Activity, do not add a
  window, do not change the window type or flags.**
- In Ink card mode the host contributes **no** background, **no** padding and
  **no** chrome (no title, subtitle, footer). The `.ink` page draws its own
  border, background and padding — that IS the card.
- Footprint: width = `BAND_WIDTH_FRACTION` (0.92) of the display width,
  horizontally centred, top-anchored with the same `HudTopInset` the notice band
  uses, so the card's top edge lands exactly where the band's top edge was.
  Reuse the notice's constant/inset source rather than duplicating a literal.
- Height = the card's own content height (wrap content), capped at the space
  available below the top inset. When the cap binds, the Ink container is given
  exactly that height so the page lays out inside it — never clip or truncate
  content to make it fit.
- Every other surface kind (reader, lyrics, media, image, list, board) keeps its
  current full-bleed rendering byte-for-byte. Card mode is scoped to Ink only.

## The morph

Purely additive. The Ink card is always laid out at its final size and ends at
full alpha; the animation only reveals it.

1. The Ink card is added and laid out at its **final** size immediately. It is
   never `GONE`, never `INVISIBLE`, never scaled, never re-measured by the
   animation.
2. A wrapper around the card clips it. The wrapper's clip height starts at the
   outgoing notice band's height, so the card first appears within the band's
   rectangle.
3. When the Ink's first frame has genuinely drawn (the existing
   `InkPresentationGate` / `SurfaceHudView.dispatchDraw` signal), run one motion:
   - wrapper clip height: band height -> card height, `HudMotion.ENTER_MS`,
     `HudMotion.enter`;
   - card alpha: 0 -> 1 over `HudMotion.UPDATE_MS`, starting with the clip;
   - the notice band cross-fades out **in place** over the same window (it must
     not slide away first, and it must not be torn down before the card has
     drawn).
4. **Hard fail-safe**: if the first-frame signal has not arrived within 500 ms,
   or an animator cannot run, the card is committed instantly at full clip and
   alpha 1 and the band is dismissed. A missing signal must never leave the
   wearer with a blank or partial card.

Use the existing `HudMotionValue` / `HudMotion` primitives; do not introduce a
new animation framework or new duration constants.

# Constraints

- MUST NOT animate, scale, or re-measure the Ink view's width or height at any
  point. The only animated properties are the wrapper's clip bounds and view
  alpha.
- MUST NOT set the Ink view to `GONE`/`INVISIBLE`/`alpha = 0` in any state whose
  exit depends on a signal that can fail to arrive. Every such state needs the
  500 ms timeout above.
- MUST NOT dismiss or tear down the notice before the Ink card has drawn its
  first frame.
- MUST NOT change the wire protocol, grants, capabilities, the Ink engine, the
  templates, or anything under `forbidden_globs`. If the design appears to need
  a protocol flag (for example "full screen vs card"), STOP and report instead
  of inventing one — all Ink surfaces are cards.
- MUST NOT regress the display hold from `89baf64d`: the panel stays awake
  across the whole engaged episode, and the `ROKIDBUS hold ...` lines keep their
  current shape.
- MUST keep the `ROKIDBUS` logging style and add one line per morph decision
  (`morph decision=animate|instant reason=... bandPx=... cardPx=...`) so the next
  device run is diagnosable from logcat alone. Required, not optional.
- MUST NOT commit; no adb, no device commands, no gradle outside test_commands.
  If Gradle throws `AccessDenied` on `.gradle\...\fileHashes.lock`, that is a
  concurrent build: retry once, then stop and report.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Card geometry tests | — | Ink card width fraction, top anchor, wrap height, height cap; other surface kinds unchanged |
| 3 | Morph tests | — | clip starts at band height and ends at card height; card alpha ends at 1; timeout path commits instantly; band never torn down before first frame |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Manual (owner, wake-word path): thinking band grows into the weather card in
place; the panel around the card stays dark instead of being replaced; no cut,
no black gap, no clipped word.

# Context the executor cannot re-derive

- Branch `ink-surface`, worktree `E:\Tools\Rokid\wt-ink`, HEAD `3ba23e74`.
- `SurfaceOverlayRenderer.kt` owns the surface window (already MATCH_PARENT +
  TRANSLUCENT + `FLAG_KEEP_SCREEN_ON`). `SurfaceHudView.kt` is a `LinearLayout`
  with `setBackgroundColor(BusTheme.glassesBg)` and `setPadding(18,16,18,12)`;
  the Ink view is added as `MATCH_PARENT, height 0, weight 1` — that trio is
  what makes the card look full screen.
- `NoticeOverlayRenderer.kt` documents the doctrine this task must respect: the
  window stays put and only child bounds move, because `updateViewLayout` is an
  IPC round-trip to `system_server` that races the view's own frame production.
  Animate child views, never the window.
- `NoticeOverlayRenderer` band geometry: `BAND_WIDTH_FRACTION = 0.92f`, gravity
  `TOP or CENTER_HORIZONTAL`, top inset from `HudTopInset`, band height tracked
  in `bandHeightPx`.
- `InkPresentationGate.releaseAfterDraw(...)` already refuses to release on
  zero-size bounds or during a display power transition, and
  `SurfaceHudView.dispatchDraw` already calls `SurfaceController.onInkFrameDrawn`
  gated by `inkView.isLayoutSettledForDraw()`. Reuse this, do not rebuild it.
- The notice and the surface are two different windows, so the cross-fade spans
  both: the notice band's fade-out must be driven from the same moment the card
  commits. `NoticeController` / `SurfaceController` already talk to each other
  for the display hold; use that existing seam.
- The wake-word path cannot be triggered from adb, so device validation is the
  owner's. The unit tests and the new log lines must carry the proof.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- The morph would need a protocol change, a new window, an Activity, or a new
  permission — stop and report.
- You cannot satisfy both "card never clipped" and "band height start" — report
  which, do not ship a clipped card.

# Autonomy

Executor decides: where the wrapper lives, how the clip is expressed, how the
two windows are sequenced, test structure. Executor stops for: protocol changes,
new windows/Activities, or any state that could leave the card invisible.
