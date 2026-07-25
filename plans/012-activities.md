# Plan 012 — Activities (unified live-process layer)

Status: draft spec, awaiting go. Depends on 010 (pin surface, shipped) and 011
(notice surface, banner mechanics and window machinery).

## Goal

Model the thing most glasses-worthy plugins actually want: an **ongoing
real-world process** the wearer follows for minutes or hours without engaging
with it — a delivery, a ride, a navigation route, a workout, a timer.

Today a plugin that wants this has to choose between a pin (persistent but
static and text-only) and a surface (rich but exclusive, and gone the moment the
wearer looks at something else). Neither fits. So plugins fake it: they either
re-show a pin on every update, or they hold a surface open and block the rest of
the HUD.

An activity is one object with structured live state, rendered by the platform
in whichever presentation fits the moment. The plugin never chooses the
presentation — it declares what is happening, and the hub decides how loudly to
say it. That inversion is the whole design.

### Decision rule for plugin developers

- **Ongoing process** the wearer follows → **activity**.
- **Discrete event** needing attention or a response → **notice** (011).
- **Engaged interaction** the wearer is driving → **surface**.
- **Trivial static fact** that just needs to stay put → **pin** (010).

The pin stays in the platform for exactly that last case (a car plate, a gate
number, a room code). An activity is not "a pin that changes"; if there is a
state machine behind it, it is an activity.

## The activity object

```json
{
  "surfaceId": "maps:activity",
  "ownerPluginId": "maps",
  "seq": 31,
  "kind": "activity",
  "glyph": "turn-left",
  "primary": "300 m",
  "secondary": "Rue de la Paix",
  "progress": 42,
  "eta": "12:41",
  "detail": ["then right on Av. de l'Opera"],
  "significant": true
}
```

- `glyph`: required, one of a **platform enum** (below). The hub draws it; a
  plugin never supplies an image. This is what keeps activities cheap and
  consistent, and it is a deliberate refusal to reopen the image channel here.
- `primary`: required, ≤ 12 chars after trim. Rendered large. The one value the
  wearer reads at a glance ("300 m", "4 min", "12:41", "2.4 km").
- `secondary`: optional, ≤ 28 chars. The label for `primary`.
- `progress`: optional, integer `0..100`, or the string `"indeterminate"`.
  Absent means no progress affordance at all.
- `eta`: optional, ≤ 8 chars. Rendered as a right-aligned trailing value.
- `detail`: optional array of 0..2 strings, ≤ 32 chars each. Shown only in the
  rich panel presentation; the chip drops them.
- `significant`: optional bool on updates, default `false`. See "Flare".

Every cap is measured after trimming and violations are rejected, not truncated,
exactly like pins.

### Glyph enum v1 (proposal — see Open questions)

- Navigation: `turn-left`, `turn-right`, `turn-slight-left`,
  `turn-slight-right`, `turn-sharp-left`, `turn-sharp-right`, `u-turn`,
  `straight`, `roundabout`, `arrive`.
- Transport and delivery: `car`, `bus`, `train`, `walk`, `bike`, `package`,
  `bag`.
- Activity: `run`, `ride`, `heart`.
- Time: `timer`, `hourglass`.
- Fallback: `dot`.

Glyphs are drawn from vector assets bundled in the glasses hub, monochrome, in
the phosphor tone. An unknown glyph value is rejected at the phone hub; the
glasses renderer falls back to `dot` defensively so a version skew degrades
instead of crashing.

## Presentations

One object, five behaviors. The hub picks by context, on the glasses side.

**(a) Chip — another plugin's surface is active.** The activity renders as a
compact corner panel: glyph + `primary` on the title row, `secondary` on the
line below. This is **the pin renderer**, reused as-is (medium tier geometry),
not a new view. The wearer is doing something else; the activity is ambient.

**(b) Rich panel — the glasses are idle or on the home layer.** The "widget"
tier: platform-owned layout, glyph large on the left, `primary` at 24sp,
`secondary` at 13sp beneath it, `eta` trailing, the progress affordance under
them when present, `detail` lines at 11sp. Same pure-black background and
hairline border as every other HUD panel — black reads as transparent on the
additive optics, and that is why the panel must never gain a translucent grey
fill.

**(c) Flare — an update flagged `significant`.** The chip **morphs in place**:
it grows from its corner anchor into a top-band banner over ~280 ms with a
fast-out-slow-in interpolator while the content crossfades from chip layout to
banner layout, holds ~3.5 s, then reverse-collapses (~240 ms) back to the chip.
Implementation constraint, not a suggestion: **one container window sized to the
union of both states, animating child view bounds.** Animating window layout
params on an accessibility overlay flickers and drops frames on this hardware.
The banner half of the flare reuses 011's notice band geometry.

**(d) Pulse — a minor update.** The chip scales `1.0 → 1.12 → 1.0` over ~180 ms
as the value refreshes. Cheap enough to run on every update; it exists so a
changing number is noticed peripherally without demanding attention.

**(e) Tap — opens the owner.** A center tap on the chip or panel makes the phone
hub deliver the standard `/system/plugin/open` to the owner, exactly as a
launcher tile does. The plugin's existing open path shows its fullscreen
surface; no new callback, no new SDK method, no per-plugin wiring. (This is the
difference from a notice: a notice's response happens *in* the banner and needs
`/notice/input`; an activity's response is "show me the real thing".)

Presentation is chosen per update:

| Context | No `significant` flag | `significant: true` |
|---|---|---|
| Another surface active | chip + pulse | flare |
| Idle / home layer | rich panel | rich panel + emphasis pulse |
| Camera overlay active | hidden | hidden (queued, not replayed) |

The hub **rate-limits flares**: at most one per 10 s per activity, and a
`significant` update inside that window degrades to a pulse rather than
queueing. A plugin that flags everything as significant gets pulses, and the
throttle is logged. Pulses are not rate-limited.

## Protocol (BUSSPEC additions)

Three new phone→glasses paths, on the `/pin/*` plumbing (envelope, owner
injection, wire-id rewrite to `<pluginId>:activity`, monotonic seq, stale-seq
drop, phone-side rejecting validation):

- `/activity/start` — begins or replaces the session. Full state.
- `/activity/update` — updates the live session. Present fields replace, absent
  fields keep their value. Owner-only. Carries `significant` when the change
  deserves a flare.
- `/activity/end` — ends the session. Owner-only.

Errors mirror pins and notices: `INVALID_ACTIVITY`, `ACTIVITY_RATE_LIMITED`
(more than 4 accepted updates per second per plugin), `CAPABILITY_NOT_AVAILABLE`.

Feature bit `128` = `ACTIVITY_SURFACE` (`1 shl 7`; `64` = `NOTICE_SURFACE` from
011). The announce adds `"activitySurfaceVersion": 1`. Covered by the existing
`surfaces` grant; `PathRules` maps `/activity/*` to `surfaces`. No new
descriptor capability, no grant-UI change, no plugin API bump.

## Lifecycle

- **Single active activity slot in v1.** Last accepted `/activity/start` wins
  across plugins; the replaced owner is notified (`replaced`). Stacking multiple
  concurrent activities is explicitly deferred — see Open questions.
- **No TTL by default.** An activity ends when the owner ends it, when another
  activity replaces it, or when the owner's bus connection drops. A route that
  lasts 40 minutes must not need a keep-alive.
- Optional `maxDurationMs` on start, clamped to `[60_000, 43_200_000]`, as a
  safety net against a plugin that crashes without ending its session. Absent
  means "until ended".
- Phone hub owns canonical state and resends it on glasses re-announce, with the
  **empty-slot assert** on reconnect. Same ghost lesson as the pin, and here a
  ghost activity would also keep claiming taps on the idle layer.
- The owner is told about closure with a reason (`owner | replaced |
  disconnect | max-duration`), on `/activity/closed`.

## Glasses hub

- `ActivityController` — state, seq guard, presentation selection, flare
  throttle. Sibling of `PinController` / `NoticeController`; **not** grafted onto
  `SurfaceController`.
- `ActivityOverlayRenderer` — the single container window described above, with
  the chip view delegated to the pin panel view and the banner half sharing
  011's band view.
- Context detection uses what already exists: `SurfaceController.activeSurface()`
  for (a), `LauncherOverlayRenderer.isShown()` plus the absence of an active
  surface for the idle layer, and the existing camera-overlay visibility hook to
  hide/restore, exactly as pins do.
- Input: the activity claims center tap **only** on the idle layer — no active
  surface, no visible notice, launcher not shown. When the launcher is up, its
  tiles own the tap and the activity is a passive chip. BACK is never claimed.
- Never sets `FLAG_KEEP_SCREEN_ON`, never wakes the display, never requests
  focus (see Open questions for navigation).

## Phone hub

`PhoneActivityState` mirrors `PhonePinState`: canonical payload + owner +
optional max-duration deadline, owner checks on update/end, resend payload,
empty-slot assert, clear-on-disconnect, `/activity/closed` emission from the one
place that clears state. Tap delivery reuses the existing plugin-open path
rather than inventing a new one.

## SDK (`:bus-client`)

- `enum class NexusActivityGlyph` (the platform enum, one entry per wire value)
  and `data class NexusActivity(glyph, primary, secondary = null,
  progress: NexusActivityProgress? = null, eta = null, detail = emptyList(),
  maxDurationMs: Long? = null)` with caps enforced in `init` and KDoc per field.
- On `NexusPluginClient` (an activity outlives surface sessions):
  `startActivity(activity)`, `updateActivity(activity, significant: Boolean =
  false)`, `endActivity()`, and `val supportsActivitySurface: Boolean`.
- `onActivityClosed(reason: String) = Unit` on `NexusPluginCallbacks`.
- KDoc must state the decision rule (activity vs notice vs pin vs surface) so it
  reaches developers where they actually read.

## Pilot use cases

1. **Google Maps turn-by-turn.** Rich panel while the wearer is idle (next
   maneuver glyph, distance as `primary`, street as `secondary`, ETA trailing);
   chip when another plugin's surface is up; a flare on each maneuver change
   (`significant: true`), which is exactly the "look now" moment; pulses on
   distance countdown.
2. **Delivery / ride tracking.** `package` or `car` glyph, `primary` = minutes
   out, `secondary` = courier or plate, `progress` = route completion, flare on
   status transitions (picked up, nearby, arrived).

Both are third-party-shaped: neither needs a new capability, neither needs the
image channel, and both work with the phone doing all the fetching.

## Docs

- BUSSPEC.md: "Activity protocol v1" (paths, payload, glyph enum, presentations,
  arbitration, lifecycle, errors), feature-bit line, updated announce example.
- docs/PLUGIN_SDK.md: the four-kinds decision rule promoted to the top of the
  HUD section, plus the activity API and a Maps-shaped example.

## MUST NOT

- MUST NOT let plugins supply layouts, images, animations, colors, or timing.
  Presentation is platform-owned; that is the contract that makes activities
  cheap and the HUD coherent.
- MUST NOT animate window layout params for the flare (single container window,
  child bounds only).
- MUST NOT change `/surface/*`, `/pin/*`, or `/notice/*` behavior,
  `SurfaceController` active-surface logic, `RingSurfaceInputPolicy`,
  `RingFocusCoordinator`, or the back-failsafe.
- MUST NOT claim BACK, take window focus, or keep the screen on.
- MUST NOT add a user grant / descriptor capability or bump the plugin API
  version.
- MUST NOT touch self-arm or camera code beyond the existing overlay-visibility
  hook.

## Acceptance

1. Unit tests across `:shared`, `:bus-client`, `:phone-hub`, `:glasses-hub`
   mirroring the pin and notice suites, plus a **pure presentation-selection
   test**: (context, significant, flare budget) → chip | panel | flare | pulse |
   hidden. That function is the heart of the plan and must be testable without a
   device.
2. `assembleDebug` succeeds for `phone-hub` and `glasses-hub`.
3. On-device matrix: chip over another plugin's surface; panel on the idle
   layer; flare expand/hold/collapse with no flicker and no dropped frames;
   flare throttle degrades to pulse; tap opens the owner's surface from both
   presentations; camera hides/restores; owner disconnect clears; reconnect
   resync with an empty slot; old-glasses `CAPABILITY_NOT_AVAILABLE`.
4. A Maps-shaped fake plugin driving a scripted route end to end on hardware,
   before any real integration work starts.

## Open questions

- **Final glyph list.** The v1 set above is a proposal. It should be frozen
  before implementation, because every entry is a bundled vector asset and
  removing one later is a wire break.
- **Idle-layer panel vs the native Rokid homepage.** The rich panel wants the
  same screen real estate the native homepage widgets occupy, and the glasses
  hub does not own that layer. Whether the panel renders over it, only when our
  launcher is up, or only when the native homepage is absent, is undecided and
  needs on-device experimentation (see the "native widgets are a fixed
  CXR-fed set" finding — third-party injection into the native homepage is not
  available to us).
- **Multi-activity arbitration.** v1 is a single slot. A wearer navigating with
  a delivery in flight is a real and obvious case. Deferred deliberately; the
  eventual answer (priority? most-recently-updated? a stacked chip column?)
  changes the closure reasons and the chip geometry, so it should not be
  retrofitted casually.
- **Does media playback become an activity?** It matches the definition (an
  ongoing process with live state), and the existing media surface would become
  a presentation of it. Tempting and out of scope here; deciding it later risks
  two overlapping models shipping.
- **Chip and pin coexistence.** If a plugin holds a pin while another runs an
  activity, both want the corner. v1 proposal is a single stacked column
  (activity chip closest to the corner, pin beneath); the alternative is
  suppressing the pin while an activity is live, which changes shipped pin
  behavior and therefore needs an explicit decision.
- **Screen-awake policy.** Every other HUD kind is forbidden from keeping the
  display on, and this plan inherits that rule. Turn-by-turn navigation is the
  first case where it is arguably wrong. If navigation needs a lit screen, it
  should be an explicit, user-visible, per-activity opt-in — not a side effect
  of showing an activity.
