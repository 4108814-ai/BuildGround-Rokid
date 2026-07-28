# Plan 012 — Activities (unified live-process layer)

Status: spec, decided except the screen-awake question. Depends on 010 (pin
surface, shipped), 011 (notice surface, banner mechanics and window machinery),
and the glyph foundations landed on `plan-012-foundations`.

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

## Foundations already landed

Four commits on `plan-012-foundations` cleared the prerequisites, so this plan
starts from a system that can already draw and address what it needs:

- **A glyph vocabulary.** 19 platform glyphs, the `NexusGlyphs` registry with a
  forgiving fallback, `docs/GLYPHS.md`, and `NexusGlyphArtTest` — the design
  system is a test, not prose, because prose had already failed.
- **Custom plugin glyphs, end to end.** `GlyphContract` + `GlyphDrawable` let a
  plugin supply geometry while the platform supplies style, and the transport
  carries it to the glasses, which never have the plugin's APK. Photos Sync is
  the first consumer.
- **Notice z-order.** `HudOverlayStack` owns the order (ambient first, most
  interruptive last); a visible notice is no longer buried by the launcher.
  **Not verified on hardware** — window z-order is not unit-testable.

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
  "actions": [{ "id": "mute", "glyph": "pause", "label": "Mute" }],
  "significant": true
}
```

- `glyph`: required, a name from the shared set or one the plugin registered for
  itself. The hub draws it; a plugin never supplies an image. This is what keeps
  activities cheap and consistent, and it is a deliberate refusal to reopen the
  image channel here.
- `primary`: required, ≤ 12 chars after trim. Rendered large. The one value the
  wearer reads at a glance ("300 m", "4 min", "12:41", "2.4 km").
- `secondary`: optional, ≤ 28 chars. The label for `primary`.
- `progress`: optional, integer `0..100`, or the string `"indeterminate"`.
  Absent means no progress affordance at all.
- `eta`: optional, ≤ 8 chars. Rendered as a right-aligned trailing value.
- `detail`: optional array of 0..2 strings, ≤ 32 chars each. Shown only in the
  expanded panel; the chip drops them.
- `actions`: optional array of 0..3 `{id, glyph, label}`. See "Actions".
- `significant`: optional bool on updates, default `false`. See "Flare".

Every cap is measured after trimming and violations are rejected, not truncated,
exactly like pins.

### Glyphs

An activity's glyph is a **verb**, not branding: one route emits `turn-left`,
`straight`, `turn-right`, `arrive` within minutes. Those belong to the platform,
which is what keeps the HUD coherent when several plugins are live at once. A
plugin's identity mark is the separate thing, and it is already carried.

The set is **open and additive, never a version gate**. Hubs validate only that
a value is well-formed; an unrecognised name renders as `dot` rather than being
rejected, so a plugin built against a newer SDK degrades on an older hub instead
of being refused by it. Adding a glyph is safe; removing one is the wire break.
`docs/GLYPHS.md` is the source of truth for the current set and the drawing
rules; a plugin needing a mark the set does not have registers its own.

This replaces the closed enum an earlier draft proposed. A closed enum would
have made every future maneuver a breaking change, and it is the reason the
"freeze the glyph list before implementation" question is gone rather than
answered.

## Presentations

One object, several behaviors. The hub picks by context, on the glasses side.

**(a) Chip — the ambient form.** A compact corner panel: glyph + `primary` on
the title row, `secondary` on the line below. This is **the pin renderer**,
reused as-is (medium tier geometry), not a new view.

**(b) Panel — the expanded form.** Platform-owned layout: glyph large on the
left, `primary` at 24sp, `secondary` at 13sp beneath it, `eta` trailing, the
progress affordance under them when present, `detail` lines at 11sp, and the
action row when there is one. Same pure-black background and hairline border as
every other HUD panel — black reads as transparent on the additive optics, and
that is why the panel must never gain a translucent grey fill.

**The panel draws over everything, including the native Rokid homepage.** Plan
013 measured our `TYPE_ACCESSIBILITY_OVERLAY` running at 60 fps over that exact
homepage, so "can we draw there" was already answered and the earlier open
question was mis-posed. We cannot inject into the native widgets (they are a
fixed CXR-fed set); we do not need to.

**Two form factors, and the wearer picks.** By default the panel expands when
something happens and collapses back to the chip after ~10 s of nothing, plus a
wearer setting to keep it always expanded. **The plugin never chooses.** If
plugins could, every developer would choose "always expanded" to be seen, and
the HUD becomes a billboard. That inversion is what this plan rests on.

**(c) Flare — an update flagged `significant`.** The chip **morphs in place**:
it grows from its corner anchor into a top-band banner over ~280 ms with a
fast-out-slow-in interpolator while the content crossfades from chip layout to
banner layout, holds ~3.5 s, then reverse-collapses (~240 ms) back to the chip.
The banner half reuses 011's notice band geometry.

**(d) Pulse — a minor update.** The chip scales `1.0 → 1.12 → 1.0` over ~180 ms
as the value refreshes. Cheap enough to run on every update; it exists so a
changing number is noticed peripherally without demanding attention.

Presentation is chosen per update:

| Context | No `significant` flag | `significant: true` |
|---|---|---|
| Another surface active | chip + pulse | flare |
| Idle / home layer | panel or chip, per the collapse rule | flare |
| Camera overlay active | hidden | hidden (queued, not replayed) |

The hub **rate-limits flares**: at most one per 10 s per activity, and a
`significant` update inside that window degrades to a pulse rather than
queueing. A plugin that flags everything as significant gets pulses, and the
throttle is logged. Pulses are not rate-limited.

### The window that carries the motion

One full-screen transparent window that **stays still**, with the view inside it
moving, scaling, fading and morphing. Not the window moving.

The reason is generic Android, not Rokid hardware: `updateViewLayout` is an IPC
round-trip to `system_server`, so driving it ~60×/s races against the view's own
frame production and lets window bounds and content land on different frames. A
window can also only translate and resize a rectangle, while a view can do that
plus rotate, clip, deform, fade, and drive several elements together. The flare
is exactly the case that needs the second: a corner chip travelling to a
top-centre band while resizing and crossfading is trivial as a view and 60 IPC
calls per second as a window.

A full-screen window does **not** capture input. Input never reaches overlay
windows: `RokidBusAccessibilityService.onKeyEvent()` intercepts touchpad keys at
the service level and a priority chain routes them. Pin, notice and activity
windows are `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE` and never see an event;
the launcher and surfaces call `requestFocus()` deliberately. The notice already
ships as a full-screen window in 1.0.46 and the launcher still opens over it.

Consequence worth taking: the pin currently lives in a small `WRAP_CONTENT`
window and has no motion value at all — it cannot animate. Moving it to a
full-screen container when the activity renderer lands makes it animatable. This
adds motion; it removes none.

## Actions

An activity carries 0..3 actions.

- **0 actions (default)** — a center tap opens the owner. The phone hub delivers
  the standard `/system/plugin/open`, exactly as a launcher tile does; the
  plugin's existing open path shows its fullscreen surface. No new callback, no
  new SDK method, no per-plugin wiring.
- **1..3 actions** — each is `{id, glyph, label}`. Forward/backward move between
  them, tap fires the selected one, and the hub sends `/activity/action` with
  the id to the owner.

Platform-rendered: a row of glyphs under the primary value, the selected one
highlighted. The plugin does not choose the look. Actions are live **only when
no surface is up** — a surface already owns forward/backward for scrolling, and
the chip stays passive underneath it. Routing goes through the existing
`onKeyEvent` chain; no window changes.

**The boundary that keeps this from becoming a surface:** one-shot commands,
three at most, platform glyphs only, no text entry, no scrolling. Anything more
opens the surface.

This deliberately overrules an earlier MUST NOT ("an activity's response is
'show me the real thing'"). It is what makes a HUD media player possible, and it
is not free: it adds an input path, an arbitration rule, and a row to render.

## Protocol (BUSSPEC additions)

Phone→glasses paths, on the `/pin/*` plumbing (envelope, owner injection,
wire-id rewrite to `<pluginId>:activity`, monotonic seq, stale-seq drop,
phone-side rejecting validation):

- `/activity/start` — begins or replaces the session. Full state.
- `/activity/update` — updates the live session. Present fields replace, absent
  fields keep their value. Owner-only. Carries `significant` when the change
  deserves a flare.
- `/activity/end` — ends the session. Owner-only.

Glasses→phone:

- `/activity/action` — carries the fired action's id to the owner.
- `/activity/closed` — closure reason (`owner | replaced | disconnect |
  max-duration`).

Errors mirror pins and notices: `INVALID_ACTIVITY`, `ACTIVITY_RATE_LIMITED`
(more than 4 accepted updates per second per plugin), `CAPABILITY_NOT_AVAILABLE`.

Feature bit `128` = `ACTIVITY_SURFACE` (`1 shl 7`; `64` = `NOTICE_SURFACE` from
011). The announce adds `"activitySurfaceVersion": 1`. Covered by the existing
`surfaces` grant; `PathRules` maps `/activity/*` to `surfaces`. No new
descriptor capability, no grant-UI change, no plugin API bump.

## Lifecycle

**Activities occupy free corners rather than evicting each other.** There are
four. Navigation takes one, a delivery takes another, and nobody replaces
anybody — which removes the ping-pong problem, and the guard an earlier draft
proposed for it, entirely.

- Cap: **2 activities + 1 pin** in v1 (three corners used, one free). Four
  panels on 640×480 is soup; the real case is nav + delivery.
- The pin keeps its chosen corner; an activity takes a free one.
- The expanded panel is singular, so exactly one activity is **primary**: the one
  that most recently updated with `significant`. It gets the panel and the
  flare; the others stay chips.
- Eviction only when every corner is full. The replaced owner is notified
  (`replaced`).
- **No TTL by default.** An activity ends when the owner ends it, when it is
  evicted, or when the owner's bus connection drops. A route that lasts 40
  minutes must not need a keep-alive.
- Optional `maxDurationMs` on start, clamped to `[60_000, 43_200_000]`, as a
  safety net against a plugin that crashes without ending its session. Absent
  means "until ended".
- Phone hub owns canonical state and resends it on glasses re-announce, with the
  **empty-slot assert** on reconnect. Same ghost lesson as the pin, and here a
  ghost activity would also keep claiming taps on the idle layer.

## Glasses hub

- `ActivityController` — state, seq guard, corner allocation, primary selection,
  presentation selection, flare throttle, collapse timer. Sibling of
  `PinController` / `NoticeController`; **not** grafted onto `SurfaceController`.
- `ActivityOverlayRenderer` — the single full-screen container described above,
  with the chip view delegated to the pin panel view and the banner half sharing
  011's band view.
- Context detection uses what already exists: `SurfaceController.activeSurface()`
  for the chip case, `LauncherOverlayRenderer.isShown()` plus the absence of an
  active surface for the idle layer, and the existing camera-overlay visibility
  hook to hide/restore, exactly as pins do.
- Input: the activity claims center tap **only** on the idle layer — no active
  surface, no visible notice, launcher not shown. When the launcher is up, its
  tiles own the tap and the activity is a passive chip. BACK is never claimed.
- Ordering goes through `HudOverlayStack`, which already owns it.

## Phone hub

`PhoneActivityState` mirrors `PhonePinState`: canonical payload + owner +
optional max-duration deadline, owner checks on update/end, resend payload,
empty-slot assert, clear-on-disconnect, `/activity/closed` emission from the one
place that clears state. Tap delivery reuses the existing plugin-open path
rather than inventing a new one; `/activity/action` routes to the owner the same
way `/notice/input` does.

## SDK (`:bus-client`)

- `data class NexusActivityAction(id, glyph, label)`, and `data class
  NexusActivity(glyph: String, primary, secondary = null, progress:
  NexusActivityProgress? = null, eta = null, detail = emptyList(), actions =
  emptyList(), maxDurationMs: Long? = null)` with caps enforced in `init` and
  KDoc per field. `glyph` is a `String`, not an enum — the set is open, and an
  enum would turn every future glyph into a recompile.
- On `NexusPluginClient` (an activity outlives surface sessions):
  `startActivity(activity)`, `updateActivity(activity, significant: Boolean =
  false)`, `endActivity()`, and `val supportsActivitySurface: Boolean`.
- `onActivityAction(id: String) = Unit` and `onActivityClosed(reason: String) =
  Unit` on `NexusPluginCallbacks`.
- KDoc must state the decision rule (activity vs notice vs pin vs surface) so it
  reaches developers where they actually read.

## Pilot use cases

1. **Google Maps turn-by-turn.** Panel while the wearer is idle (next maneuver
   glyph, distance as `primary`, street as `secondary`, ETA trailing); chip when
   another plugin's surface is up; a flare on each maneuver change
   (`significant: true`), which is exactly the "look now" moment; pulses on
   distance countdown.
2. **Delivery / ride tracking.** `package` or `car` glyph, `primary` = minutes
   out, `secondary` = courier or plate, `progress` = route completion, flare on
   status transitions (picked up, nearby, arrived).

Both are third-party-shaped: neither needs a new capability, neither needs the
image channel, and both work with the phone doing all the fetching. Together
they are also the case for corners rather than a single slot.

## Docs

- BUSSPEC.md: "Activity protocol v1" (paths, payload, glyphs, presentations,
  actions, arbitration, lifecycle, errors), feature-bit line, updated announce
  example.
- docs/PLUGIN_SDK.md: the four-kinds decision rule promoted to the top of the
  HUD section, plus the activity API and a Maps-shaped example.
- docs/GLYPHS.md already covers the glyph rules and needs no activity-specific
  section.

## MUST NOT

- MUST NOT let plugins supply layouts, images, animations, colors, timing, or
  choose their own presentation or form factor. Presentation is platform-owned;
  that is the contract that makes activities cheap and the HUD coherent.
- MUST NOT let an activity's action row grow into a surface: one-shot commands,
  three at most, platform glyphs only, no text entry, no scrolling.
- MUST NOT animate the window's layout params — one container window, child
  bounds only.
- MUST NOT change `/surface/*`, `/pin/*`, or `/notice/*` behavior,
  `SurfaceController` active-surface logic, `RingSurfaceInputPolicy`,
  `RingFocusCoordinator`, or the back-failsafe.
- MUST NOT claim BACK or take window focus.
- MUST NOT add a user grant / descriptor capability or bump the plugin API
  version.
- MUST NOT touch self-arm or camera code beyond the existing overlay-visibility
  hook.

## Acceptance

1. Unit tests across `:shared`, `:bus-client`, `:phone-hub`, `:glasses-hub`
   mirroring the pin and notice suites, plus a **pure presentation-selection
   test**: (context, significant, flare budget, collapse timer) → chip | panel |
   flare | pulse | hidden. That function is the heart of the plan and must be
   testable without a device. Corner allocation and primary selection get the
   same treatment.
2. `assembleDebug` succeeds for `phone-hub` and `glasses-hub`.
3. On-device matrix: chip over another plugin's surface; panel on the idle layer
   and over the native homepage; collapse after idle and the always-expanded
   setting; flare expand/hold/collapse with no flicker and no dropped frames;
   flare throttle degrades to pulse; two activities in two corners with one
   primary; tap opens the owner; the action row fires and is inert under a
   surface; camera hides/restores; owner disconnect clears; reconnect resync
   with an empty slot; old-glasses `CAPABILITY_NOT_AVAILABLE`.
4. A Maps-shaped fake plugin driving a scripted route end to end on hardware,
   before any real integration work starts.

## Settled, and why they are no longer open

- **Glyph list** — open additive set, not a frozen enum. Freezing it would have
  made every future maneuver a wire break.
- **Panel vs the native homepage** — the panel draws over it. 013 already
  measured 60 fps there.
- **Multi-activity arbitration** — corners, not a single slot, with one primary.
- **Media as an activity** — no. Media Deck keeps its surface and its transport
  controls. An activity matches the *definition* of an ongoing process but not
  the *interaction budget*. Closed, so two overlapping models cannot both ship.
- **Chip and pin coexistence** — both are corner residents under one allocator;
  the pin keeps its corner and an activity takes a free one. Shipped pin
  behaviour is unchanged.

## Still open

- **May an activity wake the display?** Every HUD kind is currently forbidden
  from keeping the screen on, and this plan inherits that rule. Turn-by-turn
  navigation is the first case where it is arguably wrong, and the premise is
  unverified: Hi Rokid's own notifications *appear* to wake the display, which
  would make our rule self-imposed rather than a platform constraint. **Needs a
  hardware observation before it can be decided.**

  Proposal if confirmed: a per-activity `wakeDisplay` flag, hub-capped (at most
  one wake per 30 s, and only on a `significant` update, so a maneuver change
  qualifies and a distance countdown does not), plus a per-plugin wearer
  setting. Not a blanket permission — a parcel tracker relighting the display
  every three minutes while the wearer walks is not a missed notification, it is
  something blinking in their eye.

## Deferred to plan 014

The glance layer: swipe between panels, choose what to look at — the wearer's
own widgets rather than a fixed set. It is the platform's central UX problem,
not a corner of this plan, and the input inventory it needs is already measured.
