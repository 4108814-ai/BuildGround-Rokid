# Plan 013 — HUD motion

Status: DONE — shipped and measured on hardware. Serves 010
(pin), 011 (notice) and 012 (activities); introduces no protocol of its own.

## Goal

Make the HUD read as alive rather than as a slideshow, without making it
noisy. Every tier in 010–012 already describes motion informally — a chip that
morphs into a banner, a value that pulses, a banner that arrives and leaves.
This is the layer all of them animate through, so that the timings are shared,
the interruptions are handled once, and none of it can be dialled by a plugin.

## Renderer decision: native Views

The WebView renderer spike answered its own question and lost on cost. Both
render the same motion; only one of them charges rent.

| | native Views | WebView |
|---|---|---|
| flare + pulse | smooth | smooth |
| sustained CPU | 7.7% | ~1.2 cores |
| resident memory | none | +88 MB PSS |
| cold start | none | ~2.2 s to first paint |

The WebView's real advantage was plugin-authored layout, and 012 refuses that
by design. There was nothing left to buy.

## The layer

`HudMotion` — three duration tokens and two interpolators, and nothing else to
choose from:

- `MICRO_MS` 180 — a value refreshing in place.
- `STANDARD_MS` 280 — a panel arriving, leaving its anchor, changing shape.
- `EXIT_MS` 240 — anything leaving. Exits are quicker than entrances.
- `enter` fast-out-slow-in, `exit` fast-out-linear-in.
- `enabled` — a global kill switch that makes every animation land instantly.
  Intended consumers: battery saver, a wearer preference, and the platform's
  own remove-animations accessibility setting. Nothing reads it yet.

`HudMotionValue` — one animatable number that knows where it currently is.
`animateTo` cancels whatever was running and starts from `current`, which is
the entire interruption model: an update landing mid-morph continues from
where the eye already is instead of snapping back to a start value. Its
completion callback does not fire on cancel, so a superseded sequence cannot
resume itself two steps later.

A morph is driven by **one** progress value that both the bounds and the
content crossfade are lerped from. They cannot drift apart, and a retarget
mid-flight stays coherent because there is only one thing to retarget.

`HudWaveformView` — the one continuous animation the HUD gets. Redraws from
pushed amplitudes rather than a frame loop, so a stalled audio source costs
nothing rather than spinning.

`HudFrameMeter` — reports avg fps, p50, p95 and jank count, deliberately the
same four numbers the WebView spike reported.

## Rules

- **Motion means something happened.** Animate state changes, never
  decoration. On additive optics a moving element sits in the wearer's field
  of view while they are walking; an idle loop is not a flourish, it is a
  distraction with a thermal bill.
- **Continuous motion only during an engaged interaction**, and it dies with
  it. The waveform is the only current case: it is feedback for something the
  wearer is actively doing.
- **The platform owns timing.** A plugin declares what changed; it never picks
  a duration, an interpolator, or a presentation. This is the same inversion
  012 rests on.
- **Text pulses from its leading edge.** Full-width rows with left-aligned
  text slide sideways under a centre pivot.
- MUST NOT animate window layout params. One container window sized to the
  union of every state, animating child bounds inside it.

  The reason is capability and generic Android, not a measurement on this
  hardware — the table below compares two animations *inside* a fixed window and
  says nothing about moving one. `updateViewLayout` is an IPC round-trip to
  `system_server`, so driving it ~60×/s races against the view's own frame
  production and lets bounds and content land on different frames. A window can
  only translate and resize a rectangle; a view can also rotate, clip, deform,
  fade, and drive several elements at once. If some future animation genuinely
  wants a moving window, measure it — `HudFrameMeter` exists for that.

## Measured

Glasses hub 1.0.44, release-signed, real `TYPE_ACCESSIBILITY_OVERLAY` over the
native Rokid homepage:

| sequence | fps | p50 | p95 | jank |
|---|---|---|---|---|
| relay | 59.5 | 16.71 ms | 16.71 ms | 1/438 |
| taxi (bounds) | 59.9 | 16.71 ms | 16.71 ms | 0/359 |
| taxi (scale) | 59.9 | 16.71 ms | 16.71 ms | 0/358 |
| pulse | 60.0 | 16.71 ms | 16.71 ms | 0/399 |
| loop, sustained | — | — | — | 7.7% CPU |

Three things this settles:

1. The accessibility overlay gets the full frame budget. The WebView spike ran
   in a `TYPE_APPLICATION_OVERLAY`, so this was genuinely open.
2. Animating real bounds — a layout pass per frame — costs no more than
   animating scale. The flare keeps crisp text; the scale-and-crossfade
   fallback is not needed.
3. A 30 Hz custom redraw does not show up in the frame timings.

What frame timings cannot settle is whether a 280 ms morph **reads or smears
through the waveguide** — frames delivered to the app are not photons through
the optics. That was answered the way it should be: the wearer watched the
full loop on hardware and the motion read cleanly, the flare included. The
durations are accepted.

A camera through the lens stays available as a tuning instrument if a future
change makes something look wrong, but it was never the gate. A person wearing
the glasses saying it reads is the gate, and that happened.

## The real waveform

The spike feeds the waveform a synthetic speech envelope. Wiring the
microphone is not a matter of swapping the source, because of where the audio
lives: glasses mic PCM arrives **on the phone** via CXR-L (16 kHz mono PCM16),
never on the glasses. The amplitude has to come back down the bus.

- Do not send per-frame amplitudes. 30 messages/second is against the grain of
  a bus that rate-limits activities to 4 updates/second.
- Send the **envelope in batches**: roughly 10 messages/second carrying ~8
  samples each, interpolated locally for the draw. 80 envelope points per
  second is far more than 4 dp bars can show.
- The open number is round-trip latency, mic → CXR → phone → SPP → glasses.
  Under ~150 ms reads as live; beyond that it is felt.
- Fallback if latency disappoints: a VAD-driven envelope — speaking or not,
  plus a coarse level — is visually indistinguishable at this bar width. What
  the eye verifies is that it responds when the wearer speaks and stops when
  they stop, not that the shape is their actual voice.

This arrives with the mic/STT work, not before.

## Localization

The hub is single-locale today: `values/strings.xml` only, no `values-fr`, and
the glasses HUD hardcodes its strings in Kotlin rather than reading resources.

Most HUD text is not ours — the plugin supplies the message, the plate, the
street name, already in whatever language it chose. What the platform owns is
small and finite: hint footers ("Tap to reply · Back to dismiss") and state
words ("Listening…", "Sent", "Envoi dans 3"). Those should move into
`strings.xml` when 011 makes them real, rather than being translated in place
in a renderer.

## What shipped, and what the spike was

`HudMotion` and `HudMotionValue` are consumed by the notice band (011), which
is what earned this layer its place in the hub. `HudWaveformView` and
`HudFrameMeter` ship unused on purpose: the waveform is what the Relay voice
reply draws into once the microphone work lands, and the frame meter is how the
next renderer question gets answered with the same four numbers rather than an
impression.

The spike itself is gone — `MotionSpikeRenderer`, `MotionSpikeReceiver`, its
exported manifest entry and the two service hooks were removed once 011 gave
the layer a real consumer. It had done its job: it answered the frame-budget
question on the real overlay window, and it let the motion be looked at through
the optics before a single protocol decision depended on it.

One lesson from it is worth keeping. The spike fed the glasses directly,
skipping the phone, and that is exactly why three faults in the phone-side path
survived until the first real end-to-end test. A shortcut that bypasses a layer
hides the bugs in that layer.

## Open

- Wearer-level settings: default pin corner, motion intensity (the kill switch
  needs a screen), and the already-noted choice between a pin and a notice for
  the same event.
- Whether a notice arriving over a live activity interrupts its motion or
  queues behind it. 012 answers this for presentation; motion needs its own
  answer.
