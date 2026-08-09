---
task: ink-morph-blackgap-clip
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

The notice→ink morph (commit `f06ecfa1`) is BROKEN on hardware two ways, both
proven tonight:
1. **Black gap, no animation.** The "Thinking…" band disappears, the screen is
   BLACK for several seconds, then the Ink card pops in. There is no visible
   grow animation at all.
2. **Clipped Ink layout.** After the morph, the Ink card renders with content
   cut off ("Ensoleillé" truncated, values clipped). The SAME document rendered
   via the DEBUG_INK harness (no notice, no morph) is PERFECT — see
   `E:\Tools\Rokid\tmp\ink-morph-bug\harness-render-perfect.png`. So the clip is
   caused by the morph path corrupting the Ink surface's final layout, NOT the
   template or the engine.

Make the morph correct: the band visibly GROWS into the full, UN-clipped Ink
card with zero black frames, or — if a true grow cannot be made robust — a clean
cross-fade with zero black and zero clip. Never worse than an instant swap.

# Evidence (read these)

- `E:\Tools\Rokid\tmp\ink-morph-bug\glasses.log` and `phone.log` — a fresh
  reproduction around 22:23. Timeline: ask 22:23:01 → 12 s of ChatGPT thinking →
  phone `/ink/event` 22:23:13.037, assistant logs "notice stream stopped for
  glasses handoff" → glasses `/surface/show ...assistant:assistant-ink`
  22:23:13.444 → glasses `/ink/event` 22:23:13.592. NOTE: there are NO
  morph/handoff log lines and NO `/notice/closed handoff` — strongly implying
  the morph placeholder path is not firing (owner-match failing OR the band is
  already gone), so the notice window is torn down before Ink paints → black.
- `harness-render-perfect.png` — the weather template rendered with no morph:
  the correct, un-clipped target layout.
- The morph implementation to fix: `NoticeMorphLogic.kt`, the morph paths in
  `SurfaceHudView.kt` (PendingInkHandoff / ActiveNoticeMorph / applyNoticeMorphProgress),
  `NoticeOverlayRenderer.handoffSnapshot`, `NoticeController`, and the assistant
  side in `AssistantUiController.kt` (`onInkAnswerShown`, INK_HANDOFF_FALLBACK_MS).

# Root-cause hypotheses to confirm or refute (with the logs + code)

- H1 (black): the real notice window is removed BEFORE the Ink first frame is
  painted, leaving black. The assistant's 2 s `INK_HANDOFF_FALLBACK_MS` hides the
  band phone-side while Ink is still in flight/rendering; and/or the glasses
  close the notice "same frame" as inserting the placeholder but the placeholder
  is empty/black or the Ink view paints black first.
- H2 (clip): the morph animates the Ink view's LAYOUT (bounds/params) or leaves
  it sized to the small band footprint, so the Ink content lays out at the wrong
  (small) size and clips. Layout-driven growth reflows and clips text.

# Constraints — the FIXED architecture (implement exactly this)

- MUST render the Ink surface at its FULL final bounds always. The Ink content
  (`InkHudView`) MUST NOT be laid out at the band's small size and MUST NOT have
  its layout params/clip animated. The grow MUST be a pure View TRANSFORM
  (scaleX/scaleY about a pivot + translationX/Y), which does not reflow or clip
  content: lay the Ink out full-size, set its initial transform so it visually
  occupies the band's footprint, then animate the transform to identity
  (scale 1, no translation). One `HudMotionValue`, `HudMotion.STANDARD_MS`, enter
  interpolator. This kills the clip (H2) by construction.
- MUST NOT produce any black frame (H1). The real notice window MUST stay up
  until the incoming Ink surface has painted its FIRST FRAME (reuse the slice-2
  first-frame/`ready` signal already emitted — glasses `/ink/event` at
  22:23:13.592 in the log is that hook). Sequence: Ink arrives → render Ink
  full-size, transformed to the band footprint, alpha rising from ~0 → on Ink
  first-frame committed, cross-fade the notice band OUT (its window still open,
  alpha→0) while the Ink transform animates to identity → only AFTER that, close
  the notice window (invisible at alpha 0). At no point is the screen empty.
- MUST make the assistant side stop blanking early: `onInkAnswerShown` must NOT
  cause the band to be hidden before Ink paints. Drive band removal from the
  glasses handoff completion. Keep a fallback, but it must only fire if the Ink
  NEVER shows (e.g. >8 s), and it must hide to the launcher/idle, never to black
  mid-answer. Reconcile with the phone→glasses flow already in place.
- MUST make the morph robust to the real timing (12 s think, then Ink): the
  owner-match + snapshot must still be valid when Ink arrives after a long
  think. If the band is genuinely gone by then, FALL BACK to showing Ink
  instantly at full bounds (no black, no clip) — never a degraded morph.
- MUST keep every non-assistant notice unchanged. MUST keep DPAD/BACK/input
  semantics unchanged. MUST NOT touch wire protocols, grants, or forbidden globs.
- If a robust transform-grow cannot be achieved within the surface host, the
  ALLOWED fallback is a full-frame cross-fade (band alpha→0 as full-size Ink
  alpha 0→1, both painted, window closed at alpha 0) — still no black, no clip,
  no reflow. Prefer the grow; ship the cross-fade only if the grow proves
  unstable, and say which you shipped in the report.
- MUST NOT commit; no adb/device commands. Transient SDK AccessDenied → retry
  once then stop.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite/build/lint | test_commands[0] | BUILD SUCCESSFUL, 0 lint errors |
| 2 | Assistant suite/build/lint | test_commands[1] | BUILD SUCCESSFUL, 0 lint errors |
| 3 | New unit tests | — | morph uses transform not layout (assert Ink view layout bounds equal full host bounds throughout the animation, only scale/translation change); notice window not closed until first-frame signal; fallback-to-instant when no band; assistant fallback fires only on no-show |
| 4 | Diff scope | `git status --short` | only scope_globs files |

Manual (reviewer, on device): ask a numeric question; band grows into a
full, UN-clipped Ink card with no black frame; slow ChatGPT (10 s+) still
morphs or falls back to instant, never black; unrelated notice unchanged.

# Context the executor cannot re-derive

- Branch `ink-surface`; morph landed `f06ecfa1`, refined the earlier
  `7007a6cd` (which had NO black/clip but no animation — that clean instant
  swap is the safety floor you must never regress below).
- Ink first-frame signal: the slice-2 renderer already emits ready on first
  committed frame (glasses TX `/ink/event ...ready`), used for the assistant
  `ready` callback — reuse it to gate the notice teardown.
- `SurfaceHudView` hosts both the Ink view and the morph placeholder in one
  full-screen window; the notice is a SEPARATE window (`NoticeOverlayRenderer`),
  non-focusable, closed by removing its window. `HudOverlayStack` reasserts
  notice-above-surface z-order — during the cross-fade the notice window is
  above the surface, so fading the notice's alpha reveals the Ink beneath.
- `HudMotionValue` = one float, retarget/cancel safe. Motion vocab in
  `plans/013-hud-motion.md` (180/280/240 ms; never animate window params).
- The weather template + chart are correct (harness proof); do NOT touch the
  template or engine — the clip is purely a morph-layout artifact.
- Assistant `INK_HANDOFF_FALLBACK_MS` is currently 2 s (AssistantUiController) —
  too short; it blanks the band before a 12 s think's Ink arrives.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts.
- Diff outside scope_globs.
- The first-frame signal cannot gate the teardown without a new phone→glasses
  message — stop and report (do not invent a new wire message).

# Autonomy

Executor decides: transform pivot math, cross-fade curve, test structure,
grow-vs-crossfade final choice (report which). Executor stops for: any
protocol/grant change, input-semantics change, or template/engine edit.
