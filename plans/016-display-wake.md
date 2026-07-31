# Plan 016 — Waking the display

Status: spec decided, unbuilt. Depends on 011 (notice surface) and 012
(activities), both shipped. Blocks 017 (relay notifications).

## Goal

Let an event the wearer must not miss turn the display back on, under a cap the
plugin asking for it does not control.

This is the last thing standing between the notice tier and the product it was
designed for. A relayed message that arrives on a dark screen is not a quiet
notification — it never happened. Plan 017 is the first consumer, but the
mechanism belongs here, in the platform, and not in whichever plugin needed it
first.

## What is already true, and it changes the shape of this plan

**The hub already wakes the display, and has since the surface tier shipped.**
[`SurfaceController.wakeScreen()`](../glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:541)
takes a 3-second `SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`, returns
early when the screen is already interactive, and runs on every accepted
`/surface/show` — three call sites, one per publication path. `WAKE_LOCK` is in
the glasses manifest. The pattern is field-proven on the optics.

So the rule stated in [BUSSPEC.md:650](../BUSSPEC.md) — *"it never keeps the
screen on or wakes the display"* — is true of pins, notices and activities, and
false of surfaces. It was never a platform prohibition; it was an unexamined
asymmetry. Plan 012 checked the premise on hardware on 2026-07-28 and recorded
the finding that settles it: **Hi Rokid's own notifications light the screen.**
Nothing in the ROM forbids this.

That reframes the work. We are not inventing a wake path, we are **governing one
that already exists and is currently ungoverned**: today any plugin can relight
the wearer's display as often as it likes, simply by pushing a surface.

Plan 012 also already decided the shape — per-item flag, hub-held cap, wearer
setting, never keep-on ([012:387](012-activities.md)). It shipped without it
because activities did not need it to be useful. This plan is that decision,
built once, for every tier that has a case, minus the wearer setting and with a
much shorter cap; both departures are argued where they land.

## The three pieces

### 1. One policy object, one counter

`DisplayWakePolicy` in the glasses hub is the only code in the tree that
acquires a screen wake lock. It answers one question —

```text
(kind, requested, isInteractive, budget, now) → Wake | Refused(reason)
```

— and it is a pure function over a small state, so the whole of it is testable
without a device. The lock acquisition itself sits behind it, unchanged from
what `SurfaceController` does today.

**The budget is global, not per plugin.** Three plugins each entitled to their
own quota is a display that relights three times as often. What the wearer
experiences is one screen, so the cap governs one screen: **at most one wake per
5 s across all plugins and all kinds.**

**Five seconds, and not the thirty plan 012 assumed.** Thirty is calibrated for
a navigation activity, where the next maneuver is minutes away. Messages are not
minutes apart, and a relay that swallows the second message of a conversation
has failed at the one thing it exists for. The cap is not there to ration real
events — it is there to stop a plugin whose loop rekindles the display ten times
a second, and five seconds stops that just as completely.

It cannot be calibrated against the ROM's screen timeout either, because that is
the wearer's own setting (5 s, 10 s, 20 s, always on, always off), so no measured
value would hold on the next pair of glasses. The tightest case is the shortest
timeout: a wake at T=0 holds the screen roughly eight seconds (our three-second
lock, then the ROM's five), an event at T=6 lands on a lit screen and spends
nothing, and an event at T=9 finds the budget already free. There is no window
where a message is lost.

**A refused wake and an unnecessary wake are different.** When the screen is
already interactive there is nothing to wake, so the budget is not spent and the
next real event still gets its wake. Only an acquired lock consumes it.

Surfaces move onto the policy without changing behaviour for the wearer, and
that is the point: a surface push can no longer be used to sidestep the cap the
notice tier respects.

### 2. `wakeDisplay` on a notice

A new optional boolean on `/notice/show`, default `false`. This is a wire change,
so the SDK bumps to **0.8.0**; it is additive and an un-updated plugin behaves
exactly as it does today.

**Honoured on `show`, ignored on `update`.** A show is a new event in the world.
An update is the owner driving a band it already owns — the countdown moving,
the transcript arriving, the footer changing — and none of that is a reason to
relight a display the wearer let go dark. Sending it on an update is not an
error; it is dropped with a log, like every other field an update may not move.

The absolute lifetime and the TTL are untouched. A woken notice is an ordinary
notice in every other respect.

### 3. `wakeDisplay` on an activity

The same flag on `/activity/start`, honoured **only on a `significant` update**,
exactly as plan 012 specified: a maneuver change qualifies, a distance countdown
does not. It ships here because the policy is shared and building it twice is how
two caps end up disagreeing.

## MUST NOT

- MUST NOT let a **plugin** keep the screen on. No `FLAG_KEEP_SCREEN_ON` granted
  through a tier, no lock held past the fixed short duration. Waking is
  permitted; holding is not.

  This governs what a plugin can ask the platform for. It says nothing about the
  states where the wearer is *already looking at the screen* and the hub keeps it
  lit on their behalf — an engaged surface, the camera viewfinder, the launcher,
  the manual pairing flow. Those hold the screen through their own window flags
  today, correctly, and are outside this plan: a wearer reading a pairing code is
  not an event interrupting them. **Do not route them through the policy and do
  not strip their flags.** The pairing hold in particular exists because the
  screen going dark dismisses the dialog and cancels the pairing mid-flow.
- MUST NOT wake on `/notice/update`, `/notice/hide`, any `/pin/*`, or a
  non-significant activity update.
- MUST NOT let a plugin raise, reset, or read the budget. The throttle is logged
  like the flare throttle and reported nowhere else.
- MUST NOT add a capability, a descriptor field, or a grant prompt, and MUST NOT
  bump the plugin API version.
- MUST NOT change what the wearer sees when a surface is pushed while the screen
  is already on — the surface path keeps its current behaviour and only gains
  the shared counter.
- MUST NOT acquire a **tier** wake lock anywhere outside `DisplayWakePolicy`
  once it exists. The manual-pairing hold in `GlassesHub` stays where it is and
  keeps its own lock; it is the one legitimate exception and the reason this
  bullet says "tier".

## Acceptance

1. Pure decision test over the policy: every combination of kind, requested
   flag, interactive state and budget position, including that an
   already-interactive screen leaves the budget untouched and that a surface
   push and a notice show draw from the same one.
2. Contract and hub tests for `wakeDisplay` validation, its silent drop on
   update, and its absence from an old-SDK payload.
3. `assembleDebug` green for `phone-hub`, `glasses-hub`, and the SDK.
4. On-device, in a dark room, screen off: a notice with the flag lights the
   optics and draws its band; a second notice 2 s later draws without lighting;
   one 6 s later lights again; with the screen already on, three notices in a
   row all draw and the next dark-screen notice still lights, proving the budget
   was not spent.
5. Battery sanity: a scripted twenty-notice hour on hardware, measured against
   the same hour with the flag off. A wake mechanism that costs a visible
   fraction of the glasses' battery is a wake mechanism we ship differently.

## Settled, and why they are no longer open

- **Whether the ROM allows it** — measured 2026-07-28. It does, and Hi Rokid
  itself does it. Not reopened.
- **Per-plugin budget** — no. One screen, one budget.
- **A grant** — no. Plan 012 closed this: a wake is not a data capability, and
  turning it into one adds an approval prompt for a behaviour the wearer can
  already see.
- **A wearer switch, deferred rather than decided against.** Plan 012 called for
  a per-plugin *Wake the display* toggle. It is not built here, because the cap
  already handles the failure it was aimed at — the plugin that relights the
  screen in a loop — and the remaining case is narrower than it looks: a
  *third-party* plugin the wearer wants to keep but wants quieter. Every plugin
  in the store today is the owner's, and the fix for one of those being too eager
  is to fix the plugin. Deferring costs nothing later: `wakeDisplay` is already
  on the wire, so adding the toggle is hub-local, with no protocol change and no
  SDK bump. **Reopen it the day a plugin signed by someone else requests a
  wake.**
- **Keeping the screen on** — forbidden, for every kind, permanently. This plan
  does not narrow that rule; it depends on it.
