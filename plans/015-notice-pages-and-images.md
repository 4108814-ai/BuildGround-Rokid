# Plan 015 — Notice pages and images

Status: DONE — shipped in sdk-v0.7.0 (pages and images both live; plan 017
consumed them on hardware).
Depended on 011 (notice surface), 012 (activities — the band view is shared),
013 (HUD motion), and the image channel already shipped for surfaces.

## Goal

Make the notice tier able to carry **a real message**: text longer than a
glance, and a picture that came with it.

The trigger is a phone-notification relay. A relayed message is not a
platform-shaped 240-character string — it is however long the sender made it,
and it arrives with an image often enough that a tier which cannot draw one is
not a relay at all. Everything the notice tier does today is right; it is
simply sized for a smaller kind of event than the one it is about to be asked
to carry.

Two rules survive this plan unchanged, and they are what keep the tier from
turning into a screen:

- **The band is read, never scrolled.** Pages advance; nothing scrolls.
- **Geometry is platform-owned.** A plugin sends content, never layout. No
  plugin learns what a page is, how wide the optics are, or how many lines fit.

## Already landed

`92e5334` sized the band against the wire it draws — 92% width (~34 monospace
columns on the optics), eight body lines, 65% height ceiling. The contract's
240-character body now renders whole; ellipsizing is the safety net again
rather than the ordinary outcome. That commit is the floor this plan builds on
and needs no revisiting.

## The four pieces

### 1. A longer body

`NoticeSurfaceContract.MAX_BODY_CHARS` goes **240 → 1024**, roughly four pages.
Nothing else in the contract moves: title stays 32, footer stays 40, actions
stay at most 3.

This is a wire change, so the SDK bumps to **0.7.0**. It is additive in the
only direction that matters: every plugin sending 240 characters or fewer
behaves exactly as it does today, and the glasses hub must keep accepting the
old shape from an un-updated plugin.

### 2. Pages

A body that does not fit the eight lines the band draws becomes pages.

**Paging is computed where the pixels are** — in the glasses renderer, against
the real `StaticLayout` at the real width and text size. Nothing upstream (the
phone hub, the contract, the plugin) knows the page count exists. The renderer
measures the full body once, divides its line count by the lines the band can
draw, and hands the controller a page count; the controller owns which page is
current and the renderer draws that window of the layout. Because both come
from the same measured layout, page breaks never disagree with what was
measured.

**Navigation is the gesture the wearer already knows.** Forward and backward on
the touchpad, exactly as action chips are stepped through today. Back exits, as
it does today.

**A notice is paged or it is answerable, never both.** A notice carrying
actions is a question and is not paged; a paged notice carries no actions and
offers only back. This is not a limitation to work around later — it is what
keeps forward/backward from meaning two things at once. `claimsDirection()`
currently returns true only when live actions exist; it gains the paged case,
and the two are mutually exclusive by construction.

> **Superseded 2026-07-31, by wearing it.** The reasoning survives; the boundary
> was drawn in the wrong place. What must never happen is a direction meaning
> two things — and with **at most one action** there is nothing to step along,
> so the directions are free to turn pages while the tap still answers. The rule
> is now **a band pages unless its row needs the directions**: one action or
> none pages, two or more does not.
>
> What broke it is the case this tier was built for. A relayed conversation is
> long *and* worth one reply; under the old rule it was ellipsized at eight
> lines with the rest unreachable. Relay's second chip, Dismiss, was buying
> nothing either — Back already dismisses any visible band — while costing the
> wearer the ability to read the message they were interrupted about. It now
> sends one action, and its threads page.

**Page position is drawn by the platform, not the plugin.** When there is more
than one page, the footer line carries the plugin's footer at the start and a
muted `2/4` at the end. One page draws no indicator and adds no line.

### 3. A lifetime that matches the reading

Today `ttlMs` is capped at 20 s and total lifetime at 60 s. A four-page body is
about 50 s of reading, so both caps kill the band mid-message.

- **A notice that does not set `ttlMs` gets one computed from its length**:
  `2000 ms + 45 ms per character`, clamped to `[4 s, 45 s]`. 240 characters →
  ~13 s; a full 1024 → the 45 s clamp.
- `MAX_TTL_MS` 20 s → **45 s**. `MAX_LIFETIME_MS` 60 s → **90 s**.
- **The first page turn ends the countdown.** The notice enters an *engaged*
  state where neither TTL nor lifetime applies, replaced by a **30 s inactivity
  timeout that restarts on every gesture**. The wearer reading at their own
  pace is never interrupted; the wearer who takes the glasses off mid-message
  does not leave a band parked in their vision forever.
- Engagement ends on back, on the inactivity timeout, or when another notice
  takes the tier.

Note the deliberate asymmetry with `moveSelection`, which documents that
choosing is *not* a reason to extend a notice's life. Choosing among three
chips is a moment; reading four pages is not. Both comments should end up
saying why they differ.

### 4. An image

**The image rides in the `notice/show` envelope itself**, as a binary frame
alongside the JSON payload — the same shape `kind=image` already uses for
surfaces, whose decode and validation path is reused rather than reinvented.

`BusHubService` rejects any notice envelope carrying a binary today
(`envelope.binary != null` → `INVALID_NOTICE`). That guard is lifted **for
`notice/show` only**; `notice/update` and every other ambient tier stay
text-only.

Carrying the bytes in the show message is what makes the arrival correct for
free: the band cannot appear before its image, because they are the same
message. There is no waiting state to design, no deadline to tune, and no
failure mode where a picture never comes and a notice never appears — a
transfer that fails fails the whole envelope, and the plugin is told and can
re-show without the image.

Caps reuse `ImageSurfaceContract`: JPEG or PNG, 64 KiB, 512 px longest edge.
Senders should aim near 480×160; the band will downscale anything larger.

Layout: under the title, full band width, aspect preserved, capped at **150 px**
of the 312 px the band can occupy. **The image draws on page 1 only** — a
picture is worth the first screen, not a permanent tax on the reading — and
the body is capped at three lines while it is on screen, paging the remainder
as usual.

Decode never runs on the main thread. The optics are green-monochrome; that is
a known and accepted property, not something to compensate for.

## Also in scope

`ActivityOverlayRenderer.BAND_WIDTH_FRACTION` (0.80) is aligned to 0.92 to
match the notice. The activity flare and the notice band are the same view
drawn twice, and since `92e5334` they no longer have the same silhouette. The
flare's content is capped at two detail lines by its own contract, so it gains
whitespace and nothing else.

## Must not

- The notice window stays **never focusable and never touchable**, never keeps
  the screen on, and never wakes the display.
- A notice with neither actions nor multiple pages **claims no input at all** —
  the touchpad keeps reaching what is underneath.
- No change to the pin, activity, or surface tiers beyond the flare width above.
- No behaviour change for a plugin sending today's shape: ≤240 characters, no
  image, explicit `ttlMs`.
- `settings.gradle.kts` keeps pointing at `../CxrGlobal`. Do not repoint it at
  an internal copy.
- No scrolling, anywhere, for any reason.

## Done when

- A 1024-character notice pages cleanly, forward and backward, indicator
  correct, and never ellipsizes.
- Its countdown stops at the first page turn and it survives well past 45 s of
  reading, then leaves 30 s after the last gesture.
- A notice with actions is unpaged and steps through its chips exactly as
  before.
- A notice carrying a 64 KiB JPEG draws text and picture in the same frame,
  with no intermediate state where one is present and the other is not.
- An un-updated plugin's notice is byte-for-byte the experience it is today.
- `glasses-hub`, `phone-hub`, `shared`, and `bus-client` unit tests pass,
  including new coverage for paging arithmetic, the length-derived TTL, and the
  engaged-state lifetime.
