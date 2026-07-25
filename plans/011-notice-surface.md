# Plan 011 — Notice surface (transient interactive banner)

Status: draft spec, awaiting go. Depends on 010 (pin surface, shipped).

## Goal

Give a phone plugin a way to interrupt the wearer briefly for a single
real-world event, and to accept at most one gesture of response before it goes
away again. Motivating use case: Rokid Relay message notifications, where the
banner announces the message and the wearer answers by voice without ever
opening a surface.

A notice is the third HUD kind and the boundaries between them are the point:

- **pin** — ambient, persistent, no input. It sits in a corner for as long as it
  is relevant.
- **notice** — transient, top band, one interaction. It arrives, resolves, and
  disappears on its own.
- **surface** — engaged. The wearer is looking at it and driving it.

Anything that reads as "an ongoing process I glance at" is not a notice; that is
plan 012 (activities).

## Protocol (BUSSPEC additions)

Three new phone→glasses paths, reusing the `/pin/*` plumbing verbatim (envelope,
owner injection, wire-id rewrite to `<pluginId>:notice`, monotonic seq per slot,
stale-seq drop, phone-side rejecting validation):

- `/notice/show` — shows or replaces the notice. Full state every time.
- `/notice/update` — refreshes the visible notice. Fields that are present
  replace their current value; absent fields keep it. Honored only for the
  current owner, and only while a notice is actually visible; otherwise ignored
  with a log, never an error (the notice may have expired a frame earlier).
- `/notice/hide` — clears the notice. Owner-only, same ignore rule as `/pin/hide`.

```json
{
  "surfaceId": "relay:notice",
  "ownerPluginId": "relay",
  "seq": 12,
  "kind": "notice",
  "title": "Marie",
  "body": "On my way, ten minutes out. Do you still need me to bring the charger?",
  "footer": "tap to reply · back to dismiss",
  "interactive": true,
  "ttlMs": 8000
}
```

- `title`: optional, ≤ 32 chars after trim, single line, ellipsized.
- `body`: optional, ≤ 240 chars after trim. Free text; the renderer word-wraps
  it. Newlines are collapsed to spaces in v1.
- `footer`: optional, ≤ 40 chars after trim, single line. Dimmer state line
  ("Listening…", "sending in 3", "sent") — this is where the interaction status
  lives.
- At least one of `title`/`body` must be non-empty after trimming.
- `interactive`: optional bool, default `false`. See "Input claim".
- `ttlMs`: optional, default `8_000`, clamped to `[2_000, 20_000]`. Each accepted
  `/notice/show` or `/notice/update` restarts the TTL. Independently, the phone
  hub enforces an absolute lifetime of `60_000` ms from the first accepted show
  of that notice; an update cannot push a notice past it.

Two new glasses→phone→plugin paths:

- `/notice/input` — delivered to the owner only, while its notice is visible:
  `{"noticeId": "relay:notice", "keyCode": 23, "action": 0}`. Same event shape as
  `/system/plugin/input`, so the SDK can reuse `NexusInputEvent`.
- `/notice/closed` — delivered to the owner exactly once per notice:
  `{"noticeId": "relay:notice", "reason": "user"}` with `reason` in
  `user | timeout | owner | replaced | disconnect`. `owner` is the plugin's own
  hide, `replaced` is another plugin taking the slot, `disconnect` is the owner
  losing the bus (best-effort; not delivered if the owner is what disappeared).

Stable `/error` codes, delivered exactly like pin errors:

- `INVALID_NOTICE`: field shape, local id, cap, or enum validation failed.
- `NOTICE_RATE_LIMITED`: the plugin exceeded 5 accepted notice messages per
  second (show and update share the budget). Chosen so a streaming transcript
  can update the body a few times a second; see Open questions.
- `CAPABILITY_NOT_AVAILABLE`: notice v1 was not announced or SPP is down.

## Content and geometry

Geometry is platform-owned; a plugin sends text and nothing else.

- Top band: anchored top-center with the same edge margin the pin uses, width
  fixed at 80% of the screen, its own `TYPE_ACCESSIBILITY_OVERLAY` window.
- Pure black background with the existing hairline border and corner radius. The
  additive AR optics emit nothing for black, so the panel reads as transparent
  and only the border and text light up. Preserve this and the comment that
  explains it; a "nicer" translucent grey is a visible grey rectangle on-glasses.
- Title 15sp phosphor bold, body 12sp muted with adaptive word wrap, footer 11sp
  muted. The band grows with the body up to 40% of the screen height, then
  ellipsizes at the end.
- No images, no plugin-controlled layout, no progress bars in v1.
- Z-order: above the pin, which is above the fullscreen surface window. When the
  surface root window is recreated, re-add both, notice last.
- Camera interplay: the existing camera-overlay visibility hook that hides the
  pin hides the notice too. One call site, no new camera logic.
- Never sets `FLAG_KEEP_SCREEN_ON`, never calls `requestFocus()`, never wakes the
  display. A notice that arrives on a dark screen is missed; that is correct
  behavior for v1 and matches the pin rule.

## Input claim (the sensitive piece)

While a notice with `interactive: true` is visible, and only then:

- **Center tap / ENTER** is claimed and forwarded to the owner as
  `/notice/input`. This works even when the owner has no active surface, which is
  the new capability: today every input route in the glasses hub is gated on
  `SurfaceController.activeSurface() != null`.
- **BACK** always dismisses the notice, platform-side, and is never forwarded.
  A plugin cannot trap the wearer in a banner. There is no `handlesBack` for
  notices and there never will be.
- **Everything else** — dpad, ring scroll, triple tap, launcher keys — passes
  through unchanged to whatever is underneath.

Three call sites in `RokidBusAccessibilityService` and one in `SurfaceController`
have to learn about this, and each is a place where getting it wrong is invisible
until on-device testing:

1. `onKeyEvent`, the `TripleTapDetector.Decision.PASS` branch (currently
   `LauncherOverlayRenderer.handleKeyEvent(event) -> true` then
   `SurfaceController.handleKeyEvent(event) -> true`). The notice claim goes
   first in that `when`, and returns `true` only for center/ENTER/BACK while an
   interactive notice is visible. Everything else must fall through to the exact
   same chain as today.
2. `handleRingKeyEvent`, which early-returns `false` when
   `!launcherShown && !surfaceActive`. A notice with no surface behind it has to
   extend that gate, translate the ring tap through `RingSurfaceInputPolicy` the
   way `SurfaceController.handleRingKey` does, and respect the same
   `consumedDownKeys` DOWN/UP bookkeeping — the R08 ring sends a DOWN/UP pair and
   the UP frequently arrives after the notice is gone.
3. `flushPendingTaps`, which drops pending taps when
   `SurfaceController.activeSurface() == null`. Same gate extension.
4. `SurfaceController.handleBackDown` / `BACK_FAILSAFE_MS` must stay untouched.
   The notice consumes BACK before the surface sees it, so the failsafe timer
   must not be started or cancelled by notice dismissal.

The notice window itself stays `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`.
Claiming keys happens in the accessibility key pipeline, not by taking window
focus; a focusable overlay would change what the underlying app receives and is
out of scope.

## Arbitration and lifecycle

- **Single global slot**, last accepted show wins across plugins. Replacing
  another plugin's notice is allowed, logged phone-side, and closes the previous
  one with `replaced`.
- Independent of surfaces, exactly like the pin: a notice survives its owner's
  surface being hidden, other plugins' surfaces, the launcher, and native Rokid
  screens.
- Cleared by: BACK (`user`), TTL expiry (`timeout`), absolute lifetime cap
  (`timeout`), owner hide (`owner`), replacement (`replaced`), owner bus
  disconnect (`disconnect`).
- The **phone hub owns canonical state** — payload, owner, TTL deadline,
  absolute deadline — and resends it (idempotent) when the glasses re-announce
  capabilities after a link-up, with the remaining TTL recomputed the way
  `PhonePinState.payloadForResend` does.
- Reconnect resync **must include the empty-slot assert**: when there is no
  active notice, the hub sends a synthetic hide with a fresh seq on re-announce.
  This is the pin's ghost lesson — without it, a notice cleared while every link
  was down survives on the glasses forever, and unlike a pin it also keeps
  claiming center-tap.

## Capability gating

- New hub feature bit `64` = `NOTICE_SURFACE` (`1 shl 6`; `32` = `PIN_SURFACE` is
  the current highest). The glasses announce adds `"noticeSurfaceVersion": 1`.
- The phone hub exposes the bit to plugins only after a valid announcement and
  only while `SPP_DATA_UP` is live — mirror `PIN_SURFACE` exactly, including
  clearing on link-down and the "callers must not cache capabilities()" model.
- Covered by the existing `surfaces` grant. **No new descriptor capability, no
  grant-UI change.** `PathRules` maps `/notice/*` to `surfaces`.
- Old glasses never announce the bit → SDK notice calls fail locally with
  `CAPABILITY_NOT_AVAILABLE`. Plugin API version stays 3.

## Phone hub

- Route `/notice/*` through the same validate → inject owner → rewrite wire id →
  assign seq → forward pipeline as pins, plus the rate limiter and cap
  validation above.
- `PhoneNoticeState` mirrors `PhonePinState`: canonical payload + owner + TTL
  deadline + absolute deadline, owner checks for update/hide, resend payload,
  empty-slot hide payload, clear-on-disconnect.
- Two schedulers (mirror `schedulePinExpiry`): the refreshable TTL and the
  absolute cap. Whichever fires first closes the notice.
- `/notice/input` from the glasses is delivered **only** to the current owner and
  only while the notice is live; anything else is dropped with a log. A stale
  input for a closed notice must never reach a plugin.
- `/notice/closed` is emitted exactly once per notice, from the single place that
  clears canonical state.

## Glasses hub

- `NoticeController` (state + seq guard + TTL timer + `interactive` flag) and
  `NoticeOverlayRenderer` (window + view), siblings of `PinController` /
  `PinOverlayRenderer`. Do **not** graft notice state onto `SurfaceController`.
- The glasses-side TTL is the display timer; the phone's is canonical. Both
  exist for the same reason they do for pins: the link can drop.
- `BusHubService` (glasses) dispatches `/notice/*` to the controller.

## SDK (`:bus-client`)

- `data class NexusNotice(title: String? = null, body: String? = null,
  footer: String? = null, interactive: Boolean = false, ttlMs: Long? = null)`
  with `toPayload` in the style of `SurfaceModels.kt` and KDoc on every cap.
- On `NexusPluginClient` (not `NexusSurfaceSession` — a notice outlives and
  ignores surface sessions): `showNotice(notice): NexusSdkResult`,
  `updateNotice(notice): NexusSdkResult`, `hideNotice(): NexusSdkResult`, and
  `val supportsNoticeSurface: Boolean`, all shaped exactly like the pin methods.
- On `NexusPluginCallbacks`, with `= Unit` default bodies so existing plugins
  keep compiling: `onNoticeInput(event: NexusInputEvent)` and
  `onNoticeClosed(reason: String)`.
- New `NexusSdkResult` entry `NOTICE_RATE_LIMITED` only if the SDK preflights the
  limiter the way it preflights image rate limiting; otherwise reuse existing
  results.

## Acceptance narrative (Relay)

This is the flow the design exists for, and it must work end to end without the
wearer touching the phone:

1. A message arrives. Relay (phone) sends `/notice/show` with
   `title: "Marie"`, the message as `body`, `footer: "tap to reply"`,
   `interactive: true`.
2. The wearer taps the touchpad (or the ring). The hub routes `/notice/input`
   to Relay, which has no surface open.
3. Relay takes the mic lease and sends `/notice/update` with
   `footer: "listening…"`. The banner is now a live state display.
4. Transcript text streams back into `body` through `/notice/update`, a few
   updates per second, each refreshing the TTL.
5. On end-of-speech Relay updates to `footer: "sending in 3 · tap to redo"` and
   counts down in the footer. A tap inside the window restarts step 3.
6. On send: `footer: "sent"`, then `/notice/hide` after a beat. BACK at any
   point dismisses everything and Relay gets `/notice/closed` with `user`.

Mic and speech-to-text come from the existing audio-lease capability and the STT
work landing in parallel; this plan adds no audio path of its own.

## Docs

- BUSSPEC.md: "Notice protocol v1" section (paths, payload, caps, arbitration,
  lifecycle, input claim, error codes), feature-bit line, updated announce
  example.
- docs/PLUGIN_SDK.md: plugin-facing API, the three-kinds decision rule, and a
  short Relay-shaped example.

## Risks and review focus

**The input claim is the whole risk of this plan.** Everything else is pin
plumbing that has already shipped once. Review focus, in order:

1. `RingSurfaceInputPolicy` interaction. The ring translates raw keycodes into
   surface intents and holds state across DOWN/UP and tap windows. A notice that
   claims a tap while the policy is mid-gesture, or that disappears between DOWN
   and UP, must not leave the policy latched. `SurfaceController.cancelRingInput`
   exists for this; the notice path needs the equivalent discipline.
2. BACK. Confirm by test and on device that BACK dismisses in every combination
   (notice alone, notice over surface, notice over launcher, notice over a
   surface with `handlesBack: true`) and that the surface back-failsafe is
   neither started nor cancelled by a notice.
3. Precedence over the launcher. This plan claims center-tap ahead of the
   launcher, which means an interactive notice steals the tap that would have
   launched a plugin. That is deliberate — the banner is the newest thing on
   screen — but it is the one behavior most likely to feel wrong in the hand.
4. Stale input after close. A tap in flight while the TTL fires must not be
   delivered to a plugin that thinks its notice is gone.
5. Ghost notices. Same failure mode as the ghost pin, worse consequences,
   because a ghost notice eats input. The empty-slot assert is not optional.

## MUST NOT

- MUST NOT change `/surface/*` or `/pin/*` behavior, `SurfaceController`
  active-surface logic, `RingFocusCoordinator`, the back-failsafe, or dpad
  suppression beyond the four call sites named above.
- MUST NOT let a plugin trap BACK or take window focus.
- MUST NOT set `FLAG_KEEP_SCREEN_ON` or wake the screen from the notice path.
- MUST NOT add a user grant / descriptor capability, touch `PluginCapability`, or
  extend `PathRules` grant sets beyond mapping `/notice/*` to `surfaces`.
- MUST NOT bump the plugin API version (stays 3) or break old-glasses behavior.
- MUST NOT touch self-arm or camera code except the single camera-overlay
  visibility hook the pin already uses.

## Acceptance

1. `:shared`, `:bus-client`, `:phone-hub`, `:glasses-hub` unit tests pass, with
   new tests mirroring the pin suites: contract caps/shape validation, phone
   owner/TTL/absolute-cap/rate-limit/resync logic, glasses seq/TTL/replace, SDK
   payload building and capability preflight, and the input-claim decision
   function as a pure unit (given interactive + keycode → claim or pass).
2. `assembleDebug` succeeds for `phone-hub` and `glasses-hub`.
3. On-device matrix: notice over launcher; over a card surface; over a
   `handlesBack` surface; BACK dismisses in all of them; TTL expiry; absolute cap
   with a chatty updater; owner disconnect; camera hides/restores; ring tap and
   touchpad tap both deliver `/notice/input`; old-glasses
   `CAPABILITY_NOT_AVAILABLE`.
4. The Relay narrative above, run end to end on hardware.

## Open questions

These were not decided when the plan was written and are called out rather than
silently invented:

- **Title and footer caps.** `body` at 240 is decided; the 32/40 above are
  proposals sized to the band, not owner decisions.
- **Update merge semantics.** This plan says "present fields replace, absent
  fields keep", which matches `/surface/update` but differs from `/pin/show`
  (always full state). Confirm before implementing; a full-state-only update
  would be simpler to reason about but noisier for transcript streaming.
- **Rate limit.** 5/s is a guess balancing a live transcript against a runaway
  plugin. The pin's 500 ms limit would be too slow for step 4 of the Relay flow.
- **Launcher precedence** for center-tap (risk 3 above).
- **Bursts.** Two Relay messages a second apart mean the second replaces the
  first, and the wearer never reads message one. v1 is explicitly single-slot,
  but a two-deep queue is the obvious v1.1 and would change the closure reasons.
- **Whether a notice may wake a dark display**, and whether it should be allowed
  to trigger a haptic or sound on the glasses. Both are "no" here by default.
