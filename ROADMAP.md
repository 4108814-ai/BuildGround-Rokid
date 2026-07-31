# Rokid Nexus — Roadmap

Status: 2026-08-01. This file is the public roadmap and the source of truth the
[project site](https://rokid-nexus.anezium.me) renders. The founding product
argument lives in [VISION.md](VISION.md); shipped detail lives in
[CHANGELOG.md](CHANGELOG.md).

Nothing here carries a date. Items are ordered by the problem they solve, and a
thing is only listed as shipped once it has run on real hardware.

---

## Shipped

The platform is past its beta gate: a stranger sets up Nexus with nothing but a
phone, installs plugins from a public registry, and never touches the glasses
again.

### The platform

- **The bus and its trust boundary.** Any APK may request access; capabilities
  (`surfaces`, `http_proxy`, `microphone`, `camera`) are granted per plugin by
  the user, keyed to package + plugin id + signing certificate. Installation
  alone grants nothing, and route enforcement happens at the hub.
- **Wake-on-message.** A plugin does not need to be running to receive traffic —
  the hub binds it awake.
- **One hub-owned CXR-L session.** No more apps fighting over the link.
- **Declarative surfaces.** Plugins push content descriptions; the glasses hub
  renders them locally. Zero glasses-side deployment, ever.
- **Setup without a computer.** Seven in-context steps on the phone, the glasses
  app pushed over the Rokid link, then a two-card self-arm on the glasses that
  enables accessibility and bootstraps its own privileged shell. No ADB, no
  cable, no PC at any point.
- **A closed distribution loop.** The SDK publishes to JitPack from `sdk-v*`
  tags; plugins release as namespaced GitHub tags, are ingested by the public
  [RokidBrew-Registry](https://github.com/Anezium/RokidBrew-Registry), and
  install from the in-app Store with SHA-256 and signer pinning verified before
  every install. Both apps keep themselves current afterwards.

### The HUD

Everything the wearer sees belongs to a tier, and every tier is shipped:

| Tier | What it is |
|---|---|
| **Ambient** | Silent surface updates — lyrics advancing, glanceables refreshing |
| **Pin** | A persistent corner overlay that survives across native screens |
| **Activity** | An ongoing process — a ride approaching, a transfer in flight — holding a stable corner, expandable into a panel with up to three platform-drawn actions |
| **Notice** | A transient band over whatever is underneath: up to sixteen structured lines, paged on the glasses, up to three glyph answers, exactly one answer taken |
| **Surface** | A full interactive surface — cards, timed lines, media decks, list rows, and real images |

Plus what makes them feel native: HUD motion, a shared glyph set, plugin marks
travelling to the glasses as bare geometry, the phone's battery in the ROM's own
status row, and a band that can pulse a dark display awake — at most once every
five seconds across every plugin, never held on.

### Capabilities

- **Camera.** The glasses stream live H.264 over a Wi-Fi Direct link and the
  consumer plugin decodes on the phone. When the phone's Wi-Fi is off, the roles
  invert — the phone hosts a `LocalOnlyHotspot` and the glasses join it — and
  everything downstream is unchanged.
- **Microphone.** The audio lease is specified and hardware-validated, and
  speech-to-text ships through it.

### Plugins

Eight, all ordinary phone APKs, none of them built into the hub:

Relay · Lens · Feeds · Transit · Lyrics · Media Deck · Photos Sync · Sample

---

## Building

The work that is specified and underway.

- **Display arbitration.** The protocol has carried an `actionable` class since
  v1, and v1 still renders it as a toast. This is the first problem two chatty
  plugins will create, and the last tier of the layer model that is still
  promised rather than enforced.
- **Continuous speech.** Speech-to-text ships in short takes; the remaining
  slice is a continuous mode — the one that live captions and a voice assistant
  are both blocked behind.

---

## Next

Ordered by the problem each solves, not by ambition.

- **Native apps in the menu.** Phase 2 of the interaction model: list and launch
  real glasses APKs — Scouter, RokidPipe — from the same triple-tap menu that
  lists plugins. Nexus does not port them; it stops the wearer having to leave.
- **A `nav` surface kind.** Turn-by-turn deserves a real surface. Navigation
  degrades to a text card until it exists.
- **Maven Central.** JitPack carries the SDK today. Central once the AIDL
  surface is stable — a published coordinate is what makes a platform safe to
  bet on.

---

## Exploring

Not committed. Listed so the shape of the platform is legible — each one is an
ordinary phone APK against a capability that already exists.

- Live captions and translation, and a voice assistant (audio lease)
- Teleprompter and glanceables (today's surface kinds)
- A visual assistant and FoodFacts (camera)
- Sport HUD, CGM glucose (small protocol additions)

---

## Not on the roadmap

Being explicit about non-goals is how a platform stays one.

- **Porting native glasses apps into Nexus.** They are launched from the menu,
  not absorbed.
- **Glasses-side plugin code.** The glasses half of any capability lives in the
  hub; plugins stay phone APKs. This is what makes zero glasses-side deployment
  possible, and it is not negotiable.
- **A signature-only plugin permission.** It would make third-party plugins
  impossible, since no external developer can be signed with the Nexus key.
- **Other hosts and platforms.** Hi Rokid Global, Android, for now.
