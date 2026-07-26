# Plan 010 — Pin surface (persistent corner overlay)

Status: spec locked, branch `pin-surface`, implementation in flight.

## Goal

Let a phone plugin pin a tiny, persistent text overlay in a corner of the
glasses HUD. The pin stays visible across surface changes, the launcher, and
native Rokid screens, until it is hidden, replaced, expired, or its owner loses
its grant. Motivating use case (external plugin developer request): a
taxi/ride app keeping the car plate + description quietly in the corner while
the wearer does other things.

This is deliberately NOT a general widget system: one global slot, text only,
hard caps.

## Protocol (BUSSPEC additions)

New phone→glasses paths, mirroring the `/surface/*` plumbing (envelope, owner
injection, wire-id rewrite, monotonic seq, stale-seq drop):

- `/pin/show` — shows or replaces the pin (idempotent upsert). There is no
  `/pin/update`; `show` carries the full state every time.
- `/pin/hide` — hides the pin. Honored only when the sender owns the current
  pin (phone-side owner check); otherwise ignored (log, no error).

Payload (JSON, inside the standard envelope):

```json
{
  "surfaceId": "<pluginId>:pin",
  "seq": 7,
  "kind": "pin",
  "title": "AB-123-CD",
  "lines": ["Grey Toyota Prius"],
  "position": "top-right",
  "ttlMs": 1800000
}
```

- `surfaceId`: plugins send local id `pin`; the phone hub rewrites to
  `pluginId:pin` and injects `ownerPluginId`, exactly like surfaces.
- `title`: optional string, ≤ 24 chars after trim. Rendered emphasized.
- `lines`: optional array of 0–2 strings, each ≤ 28 chars after trim.
- At least one of `title`/`lines` must be non-empty after trimming.
- `position`: optional enum `top-left | top-right | bottom-left |
  bottom-right`, default `top-right`.
- `ttlMs`: optional; clamped to [1_000, 86_400_000]. Absent = persistent.
- `seq`: monotonic per pin slot; glasses drop stale/duplicate seq (same rule
  as surfaces).

Validation is phone-side and rejecting (stable `/error` codes, delivered the
same way image-surface errors are): `INVALID_PIN` (caps/shape violations),
`PIN_RATE_LIMITED` (more than one accepted `/pin/show` per 500 ms per plugin),
`CAPABILITY_NOT_AVAILABLE`. The glasses renderer additionally ellipsizes
defensively but must never receive oversized payloads from a compliant hub.

## Arbitration and lifecycle

- **Single global slot.** Last accepted `/pin/show` wins, across plugins;
  replacing another plugin's pin is allowed and logged phone-side. No eviction
  callback in v1.
- The pin's lifecycle is **independent of surfaces and of its owner's process**:
  it survives its owner's surface being hidden/replaced, other plugins'
  surfaces, launcher open/close, native apps in the foreground, and the owner
  disconnecting from the bus.
- **Fire-and-forget is the intended shape.** The taxi case has no surface at
  all: a dormant phone-side plugin wakes on a notification, connects, sends
  `/pin/show`, and goes dormant again; it wakes to `show` again on every update
  (idempotent upsert — that is why there is no `/pin/update`) and to `hide` when
  the ride ends. Requiring it to stay bound for the life of the pin would
  contradict the background policy in `docs/PLUGINS.md` and burn a process on a
  three-line overlay.
- The pin is cleared when: the owner sends `/pin/hide`; another `/pin/show`
  replaces it; `ttlMs` expires (glasses-side timer; phone tracks the deadline
  too and drops its canonical state); or the owner loses the right to hold one —
  grant revoked or package uninstalled (phone hub clears state and sends a
  synthetic `/pin/hide`). That last check is keyed by owner id, not by
  registration: the owner is normally dormant by then, so no binder is left to
  notice. `ttlMs` — not disconnection — is what bounds an abandoned pin.
- The **phone hub owns canonical pin state** and re-sends it (idempotent) when
  the glasses hub re-announces capabilities after a link-up, mirroring the
  surface resend model. The glasses a11y service re-renders the active pin in
  `onServiceConnected`, mirroring `SurfaceOverlayRenderer`.

## Capability gating

- New hub feature bit `32` = `PIN_SURFACE` (2 = IMAGE_SURFACE, 4 =
  CAMERA_CONSUMER_READY, 8 = CAMERA_FROZEN_SPP, and 16 =
  CAMERA_LOHS_REVERSE_REQUIRED — undocumented in BUSSPEC at the time this plan
  was written — are taken). The glasses announce adds `"pinSurfaceVersion": 1`
  to `/system/hub/capabilities`.
- The phone hub exposes the bit to plugins only after a valid announcement and
  only while `SPP_DATA_UP` is live — mirror the `IMAGE_SURFACE` gating exactly,
  including clearing on link-down and the "callers must not cache
  capabilities()" model.
- User grant: pins are covered by the existing `surfaces` grant. **No new
  descriptor capability, no grant-UI change** (same decision as image
  surfaces). Path rules map `/pin/*` to the `surfaces` capability.
- Old glasses never announce the bit → SDK pin calls fail locally with
  `CAPABILITY_NOT_AVAILABLE`. Plugin API version stays 3.

## Glasses rendering

- New `PinOverlayRenderer` (sibling of `SurfaceOverlayRenderer`) drawing a
  **separate, small window** via the a11y service: `TYPE_ACCESSIBILITY_OVERLAY`,
  `WRAP_CONTENT`, corner gravity per `position` with comfortable edge margins,
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`. It must never call
  `requestFocus()` and must never set `FLAG_KEEP_SCREEN_ON` (ambient content:
  a pin must not keep the display alive, and showing one must not call the
  wake-screen path).
- Z-order: the pin renders above the fullscreen surface window. When the
  surface root window is (re)created, re-add the pin window so it stays on
  top. Acceptance: pin visible over an active card surface.
- Style: existing phosphor language, reusing the glasses-side theme constants
  already used by `SurfaceHudView` — compact rounded panel, thin border,
  translucent dark background; title row brighter and slightly larger, lines
  dimmer; max width ~45% of the screen, single-line ellipsize per row. No new
  colors.
- Camera interplay: while the camera overlay / live camera session is active,
  hide the pin window and restore it afterwards. One minimal visibility hook
  where the camera overlay shows/hides — do not touch camera logic beyond it.
- State/seq guard/TTL timer live in a small `PinController` (do NOT graft pin
  state onto `SurfaceController`'s active-surface machinery). Routing in
  `BusHubService` (glasses side) dispatches `/pin/*` to it.

## Phone hub

- Route `/pin/*` with the same validate → inject owner → rewrite wire id →
  assign seq → forward pipeline as surfaces; add the rate limiter, cap
  validation, and `/error` emission described above.
- Canonical pin state holder: current payload + owner + TTL deadline; resend
  on glasses announce; clear + synthetic hide when the owner's grant goes away
  (revoked or uninstalled), matched by owner id since the owner is usually
  dormant by then; expiry clears state (and defensively sends hide when the
  link is up). Registrations coming and going must NOT touch pin state.

## SDK (`:bus-client`)

- `data class NexusPin(title: String? = null, lines: List<String> = emptyList(),
  position: NexusPinPosition = NexusPinPosition.TOP_RIGHT, ttlMs: Long? = null)`
  plus `enum class NexusPinPosition`, with `toPayload` in the style of
  `SurfaceModels.kt`. KDoc documents every cap.
- Methods on `NexusPluginClient` (NOT on `NexusSurfaceSession` — the pin
  outlives surface sessions): `showPin(pin: NexusPin): NexusSdkResult` and
  `hidePin(): NexusSdkResult`, with the local capability check returning the
  existing capability-unavailable result exactly like image calls do.
- Follow existing `NexusSdkResult` shapes/naming; no new result types unless
  one is genuinely missing.

## Demo producer (validation vehicle)

Add a minimal pin demo to `plugin-sample`: using whatever input/interaction
hook the sample already has, toggle a demo pin
(`title "NEXUS PIN"` / line `"sample overlay"`, no TTL) on and off. Smallest
possible diff; it exists so on-device e2e can be driven without writing a new
plugin.

## Docs

- BUSSPEC.md: new "Pin protocol v1" section (paths, payload, caps, arbitration,
  lifecycle, error codes), feature-bit line, updated announce example.
- PLUGIN_SDK.md: plugin-facing API with a short example.
- Keep prose style consistent with the existing spec text.

## MUST NOT

- MUST NOT change existing `/surface/*` behavior, `SurfaceController`
  active-surface logic, ring input (`RingSurfaceInputPolicy`,
  `RingFocusCoordinator`), back-failsafe, or dpad suppression. Pin input does
  not exist; nothing about key routing changes.
- MUST NOT add a new user grant / descriptor capability or touch
  `PluginCapability`, `PathRules`' grant sets beyond mapping `/pin/*` to the
  existing `surfaces` capability, or `PluginPermissionsActivity` (a concurrent
  branch edits the grant system; collision is unacceptable).
- MUST NOT touch self-arm, ring, or camera files except the single
  camera-overlay visibility hook.
- MUST NOT bump the plugin API version (stays 3) or break old-glasses
  compatibility.
- MUST NOT set `FLAG_KEEP_SCREEN_ON`, request focus, or wake the screen from
  the pin path.

## Acceptance

1. `:bus-client`, `:phone-hub`, `:glasses-hub` unit tests pass (add pin tests
   mirroring existing surface-test patterns: payload building + caps, phone
   validation/rate-limit/owner checks, glasses seq/TTL/replace logic).
2. `assembleDebug` succeeds for `phone-hub` and `glasses-hub`.
3. Diff review shows no edits outside the additive scope above.
4. Documented manual matrix for on-device validation: pin over launcher; pin
   over another plugin's card; persists after closing the owner's surface;
   **survives the owner disconnecting entirely** (the taxi shape: push, go
   dormant, pin stays); TTL expiry; grant revoke and uninstall clear it;
   camera hides/restores; old-glasses `CAPABILITY_NOT_AVAILABLE` path.
