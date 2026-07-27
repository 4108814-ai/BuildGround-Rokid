# Plan 012 — Decision note

Status: decided except Q6. Fold into `012-activities.md` and delete this file
once Q6 lands.

Grounded in the code at `v1.0.46` (`6e6c65c`). Anchors are cited so a wrong
premise is falsifiable. Two of this note's own earlier claims were wrong and are
corrected below rather than quietly dropped.

---

## Findings

### F1 — The icon system already exists (earlier claim in this note was wrong)

An earlier draft said `glasses-hub` has no `drawable/` directory and concluded
every activity glyph would be drawn from scratch. The directory observation is
true; the conclusion was not. The icons live in **`bus-client`**, which both
hubs depend on: 22 vectors in `bus-client/src/main/res/drawable/`, a registry
(`NexusPluginIcons.kt`), a test, and prose in `docs/PLUGINS.md`.

**The design system, audited across all 22** — and it is looser than a two-file
sample suggested. The invariants are 24×24 dp on a 24 viewport, a single colour
`#FF4DFF8C`, and at least one path stroked at 1.7. Within that, deviation is
craft rather than drift: `ic_plugin_bus` draws its body at 1.7, its headlights
at 1.9 as zero-length round-capped strokes, and its wheels at 1.5;
`ic_plugin_cart`, `_game` and `_feed` each add a small filled accent beside a
stroked shape. An earlier draft of this note called the rule "stroke, never
fill" — that would have failed three shipped icons.

**Our own two plugin marks broke it.** `nexus_glyph_photosync.xml` was
fill-only, and `nexus_glyph_sample.xml` — the one authors copy first — was
fill-only *and* white. PhotoSync's comment explains the cause: the hub tints
what it loads cross-package, but a plugin's own settings header rendered the
resource untouched, so a white silhouette stayed white there and the accent had
to be baked in. The redraw alone would not have held; the untinted header was
the actual defect.

**And a real gap, verified:** the bus carries only `iconKey`, a string. No
drawing crosses to the glasses. `photosync` deliberately declares only
`ICON_DRAWABLE` and no built-in key, so on the glasses
`drawableFor(iconKey = null, "photosync")` misses the legacy table and falls
back to `ic_plugin_grid`. **A plugin's custom mark shows on the phone and
renders as a generic grid on the glasses.** Independent of 012.

One useful precedent: the descriptor parser already passes unknown icon keys
through (`PluginDescriptorTest.kt:45`) and the renderer falls back. The
forgiving-wire model proposed for glyphs is what `iconKey` already does.

### F2 — "Never animate a window" was asserted, never measured (also corrected)

The rule appears in exactly two places, same commit: a KDoc comment
(`NoticeOverlayRenderer.kt:23`) and a MUST NOT in plan 013. Plan 013's
measurement table compares `bounds` vs `scale` — **both animations inside a
fixed window**. There is no window-params measurement anywhere. "Flickers on
this hardware" is unsourced.

The reframe that matters: this was never a limit on how much we animate. It is
about **which object carries the transform**.

- **A** — the window moves (`updateViewLayout` per frame).
- **B** — a full-screen transparent window stays still; the **view inside** moves,
  scales, fades, morphs.

B is strictly more capable. A window can only translate and resize a rectangle;
a view can do that plus rotate, clip, deform, fade, and drive several elements
together. The genuine technical argument for B is generic Android, not Rokid:
`updateViewLayout` is an IPC round-trip to `system_server` ~60×/s, unsynchronised
with the view's own frame production, so window bounds and content can land on
different frames.

**The flare is the case that needs B most** — a corner chip travelling to a
top-centre band while resizing and crossfading is trivial as a view and 60 IPC
calls per second as a window.

**Decision:** keep B because it is the more capable option, and rewrite plan
013's MUST NOT to say that instead of citing a measurement that does not exist.
If some future animation genuinely wants A, measure it — `HudFrameMeter` exists
for exactly that.

**Bonus:** the pin is in a small `WRAP_CONTENT` window
(`PinOverlayRenderer.kt:63-74`) and has no `HudMotionValue` at all — it cannot
animate. Moving it to a full-screen container makes it animatable. B adds
motion, it does not remove any.

**Full-screen does not capture input.** Input never reaches overlay windows:
`RokidBusAccessibilityService.onKeyEvent()` intercepts touchpad keys at the
service level and a priority chain routes them (`:96-201`). Pin and notice
windows are `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE` and never see an event.
The notice is already a full-screen window shipped in 1.0.46, and the launcher
still opens over it.

| Window | Flags | Sees input |
|---|---|---|
| pin, notice, activity | `NOT_TOUCHABLE` + `NOT_FOCUSABLE` | no |
| launcher, surface | `requestFocus()` | yes, deliberately |

### F3 — Notice z-order bug — FIXED

`NoticeOverlayRenderer.ensureOnTop()` was never called, though its KDoc said
"notice goes last". The two sites that re-assert z-order lifted only the pin
(`LauncherOverlayRenderer.kt:82`, `SurfaceOverlayRenderer.kt:47`). Overlay
windows stack by insertion order and the launcher panel is `Gravity.TOP` over an
opaque `BusTheme.glassesBg = Color.BLACK` (`:236-237`) — exactly where the band
sits. A visible notice was buried by the launcher for the rest of its life,
contradicting a shipped 1.0.46 claim.

Fixed: `HudOverlayStack.reassert()` owns the order (ambient first, most
interruptive last) and both sites call it. `:glasses-hub:compileDebugKotlin`
passes. **Not verified on hardware** — window z-order is not unit-testable.

---

## Decisions

### D1 — Glyphs and the design system

1. **The shared set grows**, same DA, drawn by us:
   - controls: `play`, `pause`, `next`, `prev`, `stop`
   - navigation: `turn-left`, `turn-right`, `turn-slight-left`,
     `turn-slight-right`, `turn-sharp-left`, `turn-sharp-right`, `u-turn`,
     `straight`, `roundabout`, `arrive`
   - state: `package`, `walk`, `timer`, `dot`
2. **Plugins can register their own glyphs** — declared once at registration
   (0..8), cached by the hub, referenced by name afterwards. A `pathData` string
   is a few hundred bytes, so the bus carries it fine. This is *not* the
   per-update image channel 012 refuses. It also closes the F1 gap: a plugin's
   own mark finally reaches the glasses.
3. **Unknown glyph values pass through** rather than being rejected, falling
   back to `dot` at the renderer — same model `iconKey` already uses. Makes the
   set additive forever, so nothing here is a one-shot decision.
4. **The DA becomes enforceable** — `docs/GLYPHS.md` holds the recipe and
   `NexusGlyphArtTest` holds the rules, over both the platform set and the
   plugin-supplied marks in this repo. Prose alone had already failed.
5. **A stroked primary shape is required**, fills allowed only as small accents
   beside one. On additive optics an outline lights far fewer pixels than a
   filled shape, which is why the set reads the way it does.

**Status: done.** 19 glyphs drawn, `NexusGlyphs` registry with the forgiving
fallback, `NexusGlyphArtTest`, `docs/GLYPHS.md`, both plugin marks redrawn, and
the untinted header fixed in `NexusUi.iconTileImage`. Remaining from D1: the
custom-glyph transport (item 2), which is the part that needs a wire change.

There is no distinction between "glyph" and "icon" in the platform: both are a
24×24 stroked vector. A plugin's **identity** mark is custom; an activity's
**state** glyph is a verb from the shared set (it changes 40 times per route, it
is not branding).

### D2 — Rich panel: yes, over everything, with two form factors

The panel draws over the native Rokid homepage. Plan 013 already measured our
`TYPE_ACCESSIBILITY_OVERLAY` running at 60 fps over that exact homepage, so the
"can we" was already answered and the plan's open question was mis-posed.

Two form factors: **by default it expands when something happens and collapses
to a chip after ~10 s of nothing**, plus a wearer setting to keep it always
expanded.

**The wearer chooses, never the plugin.** If plugins could pick, every developer
would choose "always expanded" to be seen, and the HUD becomes a billboard. That
inversion is what 012 rests on.

### D3 — Multiple activities by corner, replacing eviction

There are four corners. Rather than a single slot where the newest activity
evicts the previous one, activities occupy free corners: navigation in one,
delivery in another. Nobody evicts anybody, which **removes the ping-pong
problem and the guard proposed for it entirely**.

- Cap: **2 activities + 1 pin** in v1 (three corners used, one free). Four panels
  on 640×480 is soup; the real case is nav + delivery.
- The pin keeps its chosen corner; an activity takes a free one.
- The rich panel is singular, so exactly one activity is **primary**: the one
  that most recently updated with `significant`. It gets the panel and the flare;
  the others stay chips.
- Eviction only when every corner is full.

### D4 — Media does not become an activity

Media Deck keeps its surface with its transport controls. An activity matches
the *definition* of an ongoing process but not the *interaction budget*. Closed,
so two overlapping models cannot both ship.

### D5 — Activities carry 0..3 actions

- 0 actions (default) = tap opens the owner. Unchanged.
- 1..3 actions = each `{id, glyph, label}`; forward/backward move between them,
  tap fires, the hub sends `/activity/action` with the id to the owner.
- Platform-rendered: a row of glyphs under the primary value, selected one
  highlighted. The plugin does not choose the look.
- Active only when no surface is up — a surface already owns forward/backward for
  scrolling, and the chip stays passive under it.
- Routed through the existing `onKeyEvent` chain. No window changes.

**The boundary that keeps this from becoming a surface:** one-shot commands, 3
max, platform glyphs only, no text entry, no scrolling. More than that, open the
surface.

This contradicts a MUST NOT in `012-activities.md` ("an activity's response is
'show me the real thing'"). Deliberate: it is what makes a HUD media player
possible. It is not free — it adds an input path, an arbitration rule, and a row
to render.

### D6 — F3 fix and the pin's window

Ship the z-order fix independently of 012 (done). Move the pin to a full-screen
container when the activity renderer lands, so it can animate too.

---

## Still open

### Q6 — May an activity wake the display?

Every HUD kind is currently forbidden from keeping the screen on. Turn-by-turn
navigation is the first case where that is arguably wrong. The owner's argument:
Hi Rokid's own notifications appear to wake the display, which would make our
rule self-imposed rather than a platform constraint. **Unverified — the owner is
checking on hardware.**

Proposal if confirmed: a per-activity `wakeDisplay` flag, hub-capped (at most one
wake per 30 s, and only on a `significant` update, so a maneuver change qualifies
and a distance countdown does not), plus a per-plugin wearer setting. Not a blanket
permission: a parcel tracker relighting the display every three minutes while the
wearer walks is not a missed notification, it is something blinking in their eye.

### Deferred — plan 014, navigation and the glance layer

The owner's larger idea: swipe between panels, choose what to look at — your own
widgets rather than a fixed set. This is the "Nexus glance" VISION.md lists as a
bonus. Agreed as its own plan, because it is the platform's central UX problem,
not a corner of 012.

**Input inventory, measured from the code — write this into 014:**

- **One swipe axis only.** `DpadPairDedupe` maps `DPAD_RIGHT` *and* `DPAD_DOWN`
  to `FORWARD`, `DPAD_LEFT` *and* `DPAD_UP` to `BACKWARD`
  (`TouchpadGestureDetectors.kt:93-101`). That is not a bonus vertical axis: the
  firmware emits two keycodes per physical swipe, 20-80 ms apart (measured on
  device 2026-07-08, `:117-119`), and the dedupe recombines them.
- Tap ×1, ×2, ×3. Triple tap is taken by the launcher.
- Back.
- **Long press is detectable and unused.** `repeatCount` is filtered everywhere
  (`:92` and in the service). It is the only free gesture in the system — record
  that before someone spends it.
- R08 ring, when worn: tap, forward, backward, double-tap = back. One axis too.

**Sketch, to be validated on device:** a modal layer is dangerous on a HUD
because the wearer cannot see which mode they are in. So: long press enters the
glance layer and a focus ring makes the mode *visible*; inside, swipe walks a
single **flat list** (panel, its actions, next panel, its actions…) with no
nesting; tap fires; back or long press exits. Default state unchanged — swipe and
tap pass through to the native apps underneath.

---

## What this changes in `012-activities.md`

- Glyph enum → open, growing set + plugin-registered glyphs (D1).
- Rich panel → in v1, over everything, two form factors (D2).
- Single activity slot → multiple by corner, one primary (D3), and the
  ping-pong guard is dropped as unnecessary.
- Tap-only → 0..3 actions, MUST NOT rewritten (D5).
- Plan 013's window MUST NOT → rewritten with the real reason (F2).
- New prerequisite work: PhotoSync redrawn + plugin-header tint fix, custom
  glyph transport, `docs/GLYPHS.md` + CI check.
