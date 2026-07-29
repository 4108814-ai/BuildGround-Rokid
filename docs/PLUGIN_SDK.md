# Nexus Android plugin SDK

> [PLUGINS.md](PLUGINS.md) is the full guide to building a plugin (module
> structure, the headless-manifest rules, and the NexusUi design kit). This
> document is the SDK reference: artifact coordinates, the service contract,
> and the approval flow.

For the complete self-contained plugin contract — endpoints, limits,
lifecycle, and publishing — see [`plugins/AGENTS.md`](../plugins/AGENTS.md).

The SDK artifact is `com.github.Anezium.Rokid-Nexus:bus-client`, released
through JitPack from `sdk-v*` tags on this repository (see the "Rokid Nexus
SDK" GitHub releases for the current version). The `shared` artifact is
resolved transitively.

## 1. Add the dependency

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.7.0")
}
```

For local development against a checkout, publish a snapshot instead:
`.\gradlew.bat :shared:publishToMavenLocal :bus-client:publishToMavenLocal
'-PversionName=0.1.0-SNAPSHOT'` and consume it from `mavenLocal()`.

Use `compileSdk = 36`. The bus-client AAR supports `minSdk >= 26`; the
repository's canonical Sample and Transit plugin templates use `minSdk = 30`
(Android 11), matching the phone hub — don't require a newer API level without
a reason, or your plugin won't install on Android 11 phones the hub supports.
The repository builds with JDK 17.

## 2. Declare the plugin service

Declare exactly one exported service for the Nexus plugin action. Installation
does not approve it.

```xml
<service android:name=".HelloPluginService" android:exported="true">
    <intent-filter>
        <action android:name="com.anezium.rokidbus.action.PLUGIN" />
    </intent-filter>
    <meta-data android:name="com.anezium.rokidbus.plugin.ID" android:value="hello" />
    <meta-data android:name="com.anezium.rokidbus.plugin.DISPLAY_NAME" android:value="Hello Nexus" />
    <meta-data android:name="com.anezium.rokidbus.plugin.API_VERSION" android:value="3" />
    <meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES" android:value="surfaces" />
    <meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES" android:value="/plugin/hello,/system/plugin" />
    <meta-data android:name="com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY" android:value=".HelloActivity" />
    <meta-data android:name="com.anezium.rokidbus.plugin.LAUNCHABLE" android:value="true" />
</service>
```

Plugin IDs use `[a-z][a-z0-9._-]{2,63}`. Requested capabilities are `surfaces`,
`http_proxy`, `microphone`, `stt`, and `camera`. Camera paths are protected by the
approved signer-bound grant. `microphone` is grantable from the phone UI for any
plugin that requests it (see §3.1); the plugin needs no Android `RECORD_AUDIO`
permission — glasses-microphone PCM reaches the plugin over the hub, not through
the phone's own recorder. `stt` is a separate grant for hub-produced transcript
text and does not require the plugin to request raw `microphone` access.

## 3. Implement the service

```kotlin
class HelloPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null

    override fun onNexusOpen() {
        surface = nexusSurfaceSession("main")
        surface?.showCard(
            NexusCard(
                title = "Hello Nexus",
                lines = listOf("> First", "  Second"),
                footer = "swipe · tap · back",
            ),
        )
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            surface?.hide()
        }
    }

    override fun onNexusClose() {
        surface?.hide()
        surface = null
    }
}
```

The hub can cold-start this service after the app process was stopped. Do not use
an Activity initializer or static factory. `onNexusOpen`, `onNexusClose`, input,
link-state, and registration callbacks are serialized on the application main
thread. Duplicate lifecycle IDs are ignored. The glasses path already deduplicates
paired directional aliases; plugins should act once on each delivered input.

Approved, registered plugins automatically receive informational glasses signals;
no descriptor capability or extra grant is required. Test
`LinkStateBits.GLASSES_WORN` in `onNexusLinkState`, override
`onNexusGlassesAiButton(active)` for the AI-assist button (`true` on start,
`false` on stop), and handle `BusPaths.GLASSES_DEVICE_INFO` in `onNexusMessage`.
The version-1 device payload contains `deviceName`, `batteryLevel`, `sound`,
`brightness`, `systemVersion`, `isCharging`, and `wearingStatus`, in addition to
`type`, `id`, and `pluginId` envelope fields — the hardware serial number is
never included. These callbacks are observational and do not alter Hi Rokid's
assistant behavior.

Beyond the typed surface API, the service exposes `hubTarget` to select which
hub the plugin binds (phone by default), and two raw hooks for traffic on the
declared receive prefixes: `onNexusMessage` (JSON envelopes) and
`onNexusBinaryMessage` (binary frames with their metadata). Hub state rides the
additive capabilities contracts in `shared`: the phone announces `features`
plus the camera consumer display name (`PhoneHubCapabilitiesContract`), the
glasses announce renderer features, image/pin/notice/activity surface versions,
image limits, their app version, and onboarding completion
(`GlassesHubCapabilitiesContract`); unknown fields stay ignorable in both
directions.

Surface IDs are local to the plugin. The SDK validates fields and payload size;
the hub injects verified ownership and global sequencing. High-level code cannot
set a trusted owner, global sequence, or arbitrary system path.

### Choosing a HUD kind

Choose the object by what the wearer is doing, not by how large you want it to
look:

- **Ongoing process** the wearer follows → **activity**.
- **Discrete event** needing attention or a response → **notice**.
- **Engaged interaction** the wearer is driving → **surface**.
- **Trivial static fact** that just needs to stay put → **pin**.

An activity is not a frequently updated pin. If there is a state machine behind
the value — a route, delivery, ride, workout, or timer — use an activity. Your
plugin describes that state and the platform decides whether it is currently a
chip, panel, flare, pulse, or hidden. Plugins cannot select a presentation or
supply activity layouts, images, animations, colors, or timings.

### Live activities

Activities reuse the existing `surfaces` grant and plugin API version 3. They
live on `NexusPluginClient`, not `NexusSurfaceSession`, because the real-world
process continues when its engaged surface closes:

```kotlin
sealed interface NexusActivityProgress {
    data class Percent(val value: Int) : NexusActivityProgress
    data object Indeterminate : NexusActivityProgress
}

data class NexusActivityAction(
    val id: String,
    val glyph: String,
    val label: String,
)

data class NexusActivity(
    val glyph: String,
    val primary: String,
    val secondary: String? = null,
    val progress: NexusActivityProgress? = null,
    val eta: String? = null,
    val detail: List<String> = emptyList(),
    val actions: List<NexusActivityAction> = emptyList(),
    val maxDurationMs: Long? = null,
)

val supportsActivitySurface: Boolean
fun startActivity(activity: NexusActivity): NexusSdkResult
fun updateActivity(
    activity: NexusActivity,
    significant: Boolean = false,
): NexusSdkResult
fun endActivity(): NexusSdkResult

interface NexusPluginCallbacks {
    fun onActivityAction(id: String) = Unit
    fun onActivityClosed(reason: String) = Unit
}
```

`NexusPluginService` forwards those callbacks to the overridable
`onNexusActivityAction(id)` and `onNexusActivityClosed(reason)` hooks used in
the example below.

Check the live `supportsActivitySurface` value immediately before starting or
updating. For a registered plugin with the `surfaces` grant, all three methods
return `CAPABILITY_NOT_AVAILABLE` without sending when the glasses did not
announce activity v1, so the same plugin APK remains safe with an old hub.

The typed models enforce the wire caps in `init`: `primary` is required and at
most 12 trimmed characters; `secondary` is optional and at most 28; `eta` is
optional and at most 8; `detail` has at most two 32-character entries;
percentage progress is `0..100`; and there are at most three actions.
`maxDurationMs`, when present on start, is clamped by the hub to one minute
through 12 hours. Without it the activity lasts until explicitly ended,
replaced, or its owner disconnects. There is no TTL and no keep-alive loop.

Activity and action glyphs are strings, not enums, because the glyph vocabulary
is additive. Use a platform glyph for each action; the main activity glyph may
also be one your plugin registered through the custom-glyph API. The wire
validates glyph-name shape, and an unknown well-formed name renders as `dot`
instead of failing on an older hub. Each action's `id`, `glyph`, and `label`
must be nonblank. Activity v1 intentionally sets no separate numeric length cap
on action IDs or labels beyond that requirement and the three-action limit.

`updateActivity` sends the complete mutable state: nullable optional fields are
explicitly cleared when null and both lists are sent even when empty.
`maxDurationMs` is start-only and is omitted from updates, so an update cannot
restart or change the safety deadline. `significant` is a transient hint and is
sent only when true.

A Maps-shaped route can publish the next maneuver as one object:

```kotlin
class MapsLikePluginService : NexusPluginService() {
    private var routeActivityStarted = false
    private var muted = false

    override fun onNexusOpen() = Unit
    override fun onNexusClose() = Unit // The route activity continues.
    override fun onNexusInput(event: NexusInputEvent) = Unit

    fun startRoute() {
        val result = nexusClient?.startActivity(
            routeActivity(
                glyph = "turn-left",
                distance = "300 m",
                street = "Rue de la Paix",
                percent = 42,
                maxDurationMs = 4 * 60 * 60 * 1000L,
            ),
        )
        routeActivityStarted = result == NexusSdkResult.SENT
    }

    fun updateRoute(
        glyph: String,
        distance: String,
        street: String,
        percent: Int,
        maneuverChanged: Boolean,
    ) {
        if (!routeActivityStarted) return
        nexusClient?.updateActivity(
            routeActivity(glyph, distance, street, percent),
            significant = maneuverChanged,
        )
    }

    private fun routeActivity(
        glyph: String,
        distance: String,
        street: String,
        percent: Int,
        maxDurationMs: Long? = null,
    ) = NexusActivity(
        glyph = glyph,
        primary = distance,
        secondary = street,
        progress = NexusActivityProgress.Percent(percent),
        eta = "12:41",
        detail = listOf("then right on Av. de l'Opera"),
        actions = listOf(
            NexusActivityAction(id = "mute", glyph = "pause", label = "Mute"),
        ),
        maxDurationMs = maxDurationMs,
    )

    override fun onNexusActivityAction(id: String) {
        if (id == "mute") muted = !muted
    }

    override fun onNexusActivityClosed(reason: String) {
        routeActivityStarted = false
    }

    fun finishRoute() {
        nexusClient?.endActivity()
        routeActivityStarted = false
    }
}
```

Ordinary updates pulse. Set `significant = true` only for a real transition
such as a maneuver change or arrival. The hub decides whether that becomes a
flare and permits at most one flare per activity every 10 seconds; a throttled
flare becomes a pulse and is never queued. Do not use `significant` for distance
countdown ticks.

By default, an idle expanded panel collapses to its chip after about 10 seconds.
The wearer can keep the primary activity expanded from Nexus phone Settings.
That is a platform preference; no plugin API can read, set, or override it.

The platform can keep two activities and one pin in stable corners. Exactly one
activity is primary: the most recently significant one, or the oldest started
one when none is significant. A third start replaces the
least-recently-updated non-primary activity, with the oldest start as the
deterministic fallback when no non-primary candidate exists. Only the primary
activity can show the expanded panel
or claim its action row.

With no actions, a center tap on the idle layer opens the plugin through its
normal `onNexusOpen` path. With one to three actions, forward/backward selects
one and center tap invokes `onNexusActivityAction(id)`. Activity input is inert
while a surface, notice, launcher, or camera overlay owns the context; BACK is
never claimed. `onNexusActivityClosed(reason)` reports `owner`, `replaced`,
`disconnect`, or `max-duration`.

The phone hub owns canonical activity state and resends it after a glasses
reconnect, after first clearing possible ghosts. You should still call
`endActivity()` when the underlying process ends. Do not end it merely because
an engaged surface received `onNexusClose`. Activity v1 neither wakes nor keeps
the display on, and it does not include plan 014's glance layer.

### Notice bands

Notices reuse the existing `surfaces` grant and API version 3, and live on
`NexusPluginClient` for the same reason pins do: a plugin can wake, say one
thing, and go dormant again without ever opening a surface.

**A notice is a message or a question, not a menu.** It arrives, says its piece,
and leaves on its own deadline. With no explicit `ttlMs`, Nexus computes
`2000 ms + 45 ms` per normalized text character and clamps that to 4–45
seconds; an explicit value is clamped to 2–45 seconds. The absolute lifetime is
90 seconds. Anything the wearer follows over minutes is an activity; anything
they browse or drive is a surface.

A body may contain 1024 characters. Nexus measures it on the glasses and
replaces one eight-line page with the next; your plugin never calculates page
breaks. Forward and backward change pages, the footer gains a platform `2/4`
indicator, and nothing scrolls. The first page turn replaces both countdowns
with a 30-second inactivity timeout restarted on every page gesture.

[notice-band-states.html](notice-band-states.html) shows the band's four
states as the wearer sees them — plain, interactive, with actions, answered —
with an interactive demo of the one-answer rule. Open it in a browser.

```kotlin
data class NexusNoticeAction(
    val id: String,
    val glyph: String,
    val label: String,
)

data class NexusNoticeImage(
    val contentKey: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

data class NexusNotice(
    val title: String? = null,
    val body: String? = null,
    val footer: String? = null,
    val interactive: Boolean = false,
    val actions: List<NexusNoticeAction> = emptyList(),
    val ttlMs: Long? = null,
    val image: NexusNoticeImage? = null,
)

data class NexusNoticeUpdate(
    val title: String? = null,
    val body: String? = null,
    val footer: String? = null,
    val interactive: Boolean? = null,
    val actions: List<NexusNoticeAction> = emptyList(),
    val ttlMs: Long? = null,
)

val supportsNoticeSurface: Boolean
fun showNotice(notice: NexusNotice): NexusSdkResult
fun showNotice(notice: NexusNotice, imageBytes: ByteArray): NexusSdkResult
fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult
fun hideNotice(): NexusSdkResult

interface NexusPluginCallbacks {
    fun onNoticeInput(event: NexusInputEvent) = Unit
    fun onNoticeAction(id: String) = Unit
    fun onNoticeClosed(reason: NexusNoticeCloseReason) = Unit
}
```

`NexusPluginService` forwards those to the overridable `onNexusNoticeInput`,
`onNexusNoticeAction(id)`, and `onNexusNoticeClosed(reason)` hooks.

Give a band up to three actions and the platform draws a row of glyph chips
under the footer: forward and backward step along it, confirm fires the selected
one, and you hear the id through `onNexusNoticeAction`. A fourth action is
refused, not dropped. An answerable notice is deliberately one unpaged
question, so the directions never mean both “choose” and “turn page.” With no
actions, `interactive = true` claims one confirming gesture and calls
`onNexusNoticeInput`; the two callbacks never both fire. Setting `interactive`
alongside actions is redundant: offering answers is already asking for one.

**Both callbacks fire at most once per question.** A notice takes exactly one
answer: the first confirm fires, the row leaves the band, and after that the
band claims nothing and sends nothing — no second action, no second input, and
no falling back from one to the other. Write both handlers as if they run once,
because they do. Two fast temple taps used to reach a plugin as two calls, which
for a messaging plugin meant two messages sent.

> **Behaviour change from 1.0.46.** A notice with `interactive = true` used to
> call `onNexusNoticeInput` on every confirm for as long as the band was up. It
> now calls it on the first confirm only. If your plugin relied on repeated
> taps, ask again explicitly with an `updateNotice` that carries `interactive`
> or a new row.

That is also why the SDK gives you no way to clear the row: answering removes it
for you. To ask again, send a new question — an `updateNotice` carrying
`actions` or `interactive`, or a fresh `showNotice`.

```kotlin
nexusClient?.showNotice(
    NexusNotice(
        title = "Marie",                       // optional, max 32 trimmed chars
        body = "On my way, ten minutes out.",  // optional, max 1024
        footer = "scroll to choose",           // optional, max 40
        actions = listOf(
            NexusNoticeAction(id = "reply", glyph = "phone", label = "Reply"),
            NexusNoticeAction(id = "later", glyph = "timer", label = "Later"),
        ),
    ),
)

override fun onNexusNoticeAction(id: String) {
    when (id) {
        "reply" -> openReply()
        "later" -> snooze()
    }
}
```

To attach a JPEG or PNG, pass its declared metadata in the notice and its
encoded bytes to the binary overload. The frame is limited to 64 KiB, each edge
to 512 px, and total decoded area to 512 x 512; aim near 480 x 160. Nexus checks
the signature, dimensions, and SHA-256 before forwarding it. The image is full
band width under the title, capped at 150 physical pixels, and appears on page
one only. Its first body window is three lines; later pages use eight.

```kotlin
val photo = notificationPictureBytes()
nexusClient?.showNotice(
    NexusNotice(
        title = "Marie",
        body = notificationBody,
        image = NexusNoticeImage(
            contentKey = "message-${notificationId}",
            mimeType = "image/jpeg",
            pixelWidth = 480,
            pixelHeight = 160,
        ),
    ),
    photo,
)
```

Text and image appear in the same frame after background decode; there is no
text-only waiting state. `showNotice(notice)` refuses a notice whose `image` is
set, and the binary overload refuses one without image metadata. Updates remain
text-only and preserve the current image. Use a fresh `showNotice` to replace
or remove it. The binary overload also requires the live
`supportsImageSurface` capability; keep the text-only form as the fallback.

Action glyphs are strings for the same reason activity glyphs are: the
vocabulary is additive, the wire validates name shape rather than membership,
and an unknown well-formed name renders as `dot` on an older hub. Each action's
`id`, `glyph`, and `label` must be nonblank; there is no numeric cap on an id or
label beyond that and the three-action limit.

Answering turns the band into a display you still own, which is the shape most
of these want. A voice-reply plugin asks, hears the pick once, and then narrates
what it is doing on the same band:

```kotlin
override fun onNexusNoticeAction(id: String) {
    if (id != "reply") return
    // The row is already gone from the band; from here it is a display.
    // Empty string clears the footer — the "scroll to choose" hint is spent.
    nexusClient?.updateNotice(NexusNoticeUpdate(body = "Listening…", footer = ""))
    startDictation(
        onPartial = { text -> nexusClient?.updateNotice(NexusNoticeUpdate(body = text)) },
        onSent = {
            nexusClient?.updateNotice(NexusNoticeUpdate(body = "Reply sent"))
            nexusClient?.hideNotice()
        },
    )
}
```

`updateNotice` has patch semantics, and they hold all the way to the glasses:
**null keeps a field, an empty string clears it**, and a field you leave out is
one the wearer keeps seeing. The hub relays your patch rather than its own copy
of the band, so `footer = ""` really does take the footer off the band and
`interactive = false` really does stop it asking. Actions are the exception:
passing a non-empty list replaces the whole row, while an empty list leaves the
current row alone rather than clearing it. The wearer's selection follows its
action id across a replacement, so reordering your answers does not move their
finger onto a different one.

Two of these fields also *reopen* an answered band: `actions` and `interactive`.
Setting either is how you ask again — a new row, or `interactive = true` on a
band that has no row. An update that carries neither, like every call in the
example above, drives an answered band as a display without reopening it, which
is almost always what you want after the wearer has replied.

**BACK always dismisses the band**, platform-side, and you never hear about it.
That is deliberate and it does not change when a notice carries actions: a
plugin must not be able to hold the wearer inside a banner. Ring scroll and
every other key keep reaching whatever is underneath, except forward and
backward while an unanswered row or multiple text pages are actually up. A
plain one-page notice claims no direction at all.

Check the live `supportsNoticeSurface` value immediately before use. Unlike
pins it accounts for the link: a notice is a moment, so the hub never holds one
for glasses it cannot reach and tells you instead.

### Real image surfaces

Image surfaces use the existing `surfaces` grant; do not add a descriptor
capability and do not change API version 3. They are available only while the
glasses renderer has announced image v1 and the SPP binary link is live. Check
`nexusClient?.supportsImageSurface` immediately before sending and keep a card
fallback: it is a live value and can become false when SPP drops. `showImage`
and `updateImage` return `CAPABILITY_NOT_AVAILABLE` without sending when either
condition is absent.

Preprocess on the phone. Correct orientation, downscale so both decoded edges
are at most 512 px (and total pixels at most `512 * 512`), then encode as JPEG or
PNG. For photographs, start around JPEG quality 70--80 and adjust to a 20--40 KiB
target. The hard compressed cap is 65,536 bytes. PNG is most useful for simple
graphics; neither format may exceed the decoded bounds. The SDK verifies the
format signature, actual encoded dimensions, SHA-256, metadata, and size before
calling the binary transport. Do not base64 the image.

```kotlin
val bytes = resources.openRawResource(R.raw.image_surface_sample).use { it.readBytes() }
val image = NexusImage(
    contentKey = "tweet-123-photo-1", // stable identity, max 128 chars
    mimeType = ImageSurfaceContract.MIME_JPEG,
    pixelWidth = 480,
    pixelHeight = 480,
    title = "Photo",
    caption = "Optional caption",
    footer = "back",
    handlesBack = true,
)

val result = if (nexusClient?.supportsImageSurface == true) {
    surface?.showImage(image, bytes)
} else {
    surface?.showCard(NexusCard("Photo", listOf("Image preview unavailable")))
}
```

Use `updateImage(image, bytes)` to replace the current image. Every image update
is a complete binary frame and the phone hub enforces 150 ms between image
frames for the same surface. A faster frame is rejected with `/error` code
`IMAGE_RATE_LIMITED`; the SDK preflight returns
`NexusSdkResult.IMAGE_RATE_LIMITED` immediately. Plugins should not build
animation loops around v1.

### Persistent pins

Pins reuse the existing `surfaces` grant and API version 3. They occupy one
global last-writer-wins slot, are independent from `NexusSurfaceSession`, and
remain visible across normal surface and launcher changes until hidden,
replaced, expired, or your plugin's grant goes away. For that reason
`showPin`/`hidePin` live on `NexusPluginClient`, not a surface session.

**A pin does not need a surface, and does not need you to stay connected.**
This is the shape it was built for: a ride-hailing plugin spots the "driver
arriving" notification on the phone, wakes, connects, sends `showPin`, and goes
dormant again. The pin stays on the glasses. On every update it wakes and sends
`showPin` again — there is no `/pin/update`, a `show` always carries the full
state and replaces the previous one. When the ride ends it wakes once more and
sends `hidePin`. Do not hold the bus connection open for the life of a pin;
that violates the background policy in [PLUGINS.md](PLUGINS.md) and burns a
process on three lines of text.

Because of that, every pin has a deadline. Send no `ttlMs` and the hub gives
you **30 minutes** — pins are for facts worth a corner for the length of an
errand, and an unbounded default would strand one on the glasses whenever a
plugin is killed before it can `hidePin`. Set `ttlMs` explicitly when you know
your own horizon (a countdown, an ETA, a shift), anywhere from one second to
24 hours. Sending a fresh `showPin` restarts the clock, so a plugin that keeps
updating never hits its deadline.

Check the live `supportsPinSurface` value immediately before use. Both methods
return `CAPABILITY_NOT_AVAILABLE` without sending unless the glasses announced
pin v1. Old glasses therefore continue to work without a plugin API bump.

**You do not need the glasses to be awake.** `supportsPinSurface` says these
glasses can show a pin, not that one would appear this second. Push yours when
your event happens; if the glasses are off or out of range the hub holds it and
delivers it when they come back. So `CAPABILITY_NOT_AVAILABLE` means one thing —
this pair cannot show pins at all — and retrying will not change it.

```kotlin
val result = nexusClient?.showPin(
    NexusPin(
        title = "AB-123-CD",                 // optional, max 24 trimmed chars
        lines = listOf("Grey Toyota Prius"), // 0..2, max 28 trimmed chars each
        position = NexusPinPosition.TOP_RIGHT,
        ttlMs = 30 * 60 * 1000L,             // optional; clamped to 1 s..24 h
    ),
)

// Later; only the current owner can clear the slot.
nexusClient?.hidePin()
```

A pin has two size tiers. `NexusPinSize.SMALL` is the default and keeps the caps
above; `NexusPinSize.MEDIUM` allows a 28-character title and three lines of 32
characters, and renders slightly larger and up to 60% of the screen width
instead of 45%. Pick the smallest tier that fits: a pin competes with whatever
the wearer is actually looking at.

Lines can also carry emphasis. Pass `richLines` instead of `lines` (the two are
mutually exclusive, exactly like `NexusCard.richLines`): `NexusPinEmphasis.BRIGHT`
promotes a line to the phosphor title tone and `DIM` states the muted body tone
explicitly. The title is always bright.

```kotlin
nexusClient?.showPin(
    NexusPin(
        title = "Bus 42 · Central",
        size = NexusPinSize.MEDIUM,
        richLines = listOf(
            NexusPinLine("arrives in 4 min", NexusPinEmphasis.BRIGHT),
            NexusPinLine("then 11 min · 26 min"),
            NexusPinLine("platform 2", NexusPinEmphasis.DIM),
        ),
    ),
)
```

At least one title or line must be non-empty after trimming. Typed-model cap
violations throw `IllegalArgumentException`, and the caps checked are the ones
for the tier you passed. The hub rejects malformed raw traffic with
`INVALID_PIN` and accepts at most one `/pin/show` per plugin every 500 ms
(`PIN_RATE_LIMITED`). The glasses overlay is text-only and has no input. The
sample plugin cycles small pin, medium pin, hidden from its existing tap action
for on-device validation.

### 3.1 Microphone (audio lease)

Request the `microphone` capability and add `/audio` to the plugin's receive
prefixes:

```xml
<meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES"
    android:value="surfaces,microphone" />
<meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    android:value="/plugin/yourid,/system/plugin,/audio" />
```

Once the owner grants `microphone`, acquire a lease through
`nexusAudioSession(callbacks)` and drive it with `start()` / `stop()`. The hub
holds a single glasses-microphone lease at a time and streams the raw PCM to the
current holder; the SDK routes the reply, frames, and revocation to your
callbacks — you never handle the raw `/audio/*` envelopes yourself.

```kotlin
class DictationService : NexusPluginService() {
    private var audio: NexusAudioSession? = null

    fun beginListening() {
        val session = nexusAudioSession(object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                // format is 16000 Hz, 1 channel, "pcm16le"
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                // Variable buffers, typically ~10 frames/s at ~3.2 KiB each.
                // Feed your STT, recorder, VAD, etc. `pcm` is owned by the caller;
                // copy it if you keep it past this call.
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                // RELEASED, REVOKED (link lost), or a DENIED_* / ERROR terminal.
                audio = null
            }
        }) ?: return
        audio = session
        when (session.start()) {
            NexusSdkResult.SENT -> Unit                       // lease requested
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> Unit     // owner hasn't granted mic
            NexusSdkResult.NOT_REGISTERED -> Unit             // hub not connected yet
            else -> Unit
        }
    }

    fun stopListening() {
        audio?.stop()   // fires onAudioStopped(RELEASED); safe if already stopped
    }
}
```

Format is fixed at **16 kHz, mono, signed 16-bit little-endian PCM**
(`NexusAudioFormat`). `onAudioStopped` fires exactly once per active session —
on your own `stop()`, on a hub revoke (e.g. the glasses link drops), or on a
denied acquire (`DENIED_BUSY` when another plugin holds the lease,
`DENIED_NO_LINK`, `DENIED_START_FAILED`). The session also tears down (with
`onAudioStopped`) if the plugin loses approval or the service is destroyed, so
you do not need to release on `onNexusClose` yourself.

Two hardware facts to design around:

- **The glasses must be worn.** The on-glasses microphone DSP beamforms toward
  the wearer's mouth and gates otherwise, so a lease acquired while the glasses
  sit unworn yields near-silence. Gate your UX on
  `LinkStateBits.GLASSES_WORN` from `onNexusLinkState` if silence would confuse
  the user.
- **The level is conservative.** Captured speech peaks well below full scale;
  if you play the audio back or show a meter, apply gain (roughly 5×) or
  normalize.

### 3.2 Speech to text

Request `stt` and receive `/stt`; do not request `microphone` unless the plugin
also needs raw PCM:

```xml
<meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES"
    android:value="surfaces,stt" />
<meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    android:value="/plugin/yourid,/system/plugin,/stt" />
```

After install, the user must grant **Speech to text** in **Rokid Nexus →
Settings → Plugin access**. Installation never grants it. Adding `stt` to an
already installed descriptor changes the requested capability set and returns
the plugin to Pending until the user re-approves it.

Create one typed session with `nexusSpeechSession(callbacks)`. This complete
minimal service starts a French utterance when opened:

```kotlin
class SpeechPluginService : NexusPluginService() {
    private var speech: NexusSpeechSession? = null

    override fun onNexusOpen() {
        val session = nexusSpeechSession(object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) {
                // realtime=true means partial hypotheses may follow.
            }

            override fun onSpeechState(state: NexusSpeechState) {
                // LISTENING, RECOGNIZING, or PROCESSING
            }

            override fun onSpeechPartial(text: String) {
                // Update lightweight UI only. Never log transcript text.
            }

            override fun onSpeechFinal(text: String) {
                // Use the completed transcript. Never log transcript text.
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) {
                speech = null
                // error has kind plus optional provider/detail; no transcript.
            }
        }) ?: return
        speech = session
        when (session.start(language = "fr")) {
            NexusSdkResult.SENT -> Unit
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> speech = null
            NexusSdkResult.NOT_REGISTERED -> speech = null
            else -> speech = null
        }
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusClose() {
        speech?.stop()
        speech = null
    }
}
```

`language` is optional and accepts a hub `TranscriptionLanguage` ID such as
`auto`, `en`, `fr`, `de`, `es`, `it`, `pt`, `ja`, `ko`, `yue`, `zh-hant`, or
`zh-hans`. An absent or unknown ID uses the hub's configured language for that
session. Only utterance mode exists in v1.

All speech callbacks are serialized on the plugin application's main thread,
just like lifecycle and audio callbacks. Offload network calls, database work,
large parsing, or other heavy processing immediately. Transcript strings are
immutable, but retaining or persisting them is a plugin privacy decision; do
not put partials, finals, prompts, or user speech in logcat, analytics, crash
breadcrumbs, or bug reports.

The hub has one global speech session shared with its Speech settings dictation
test. It also consumes the same one-holder glasses audio lease used by
`NexusAudioSession`. A settings test, another STT plugin, or a raw microphone
lease can therefore produce `DENIED_BUSY`. The lease is handed back as soon as
the speaker stops rather than when the transcript lands, so the microphone frees
up during `PROCESSING` and a link drop while the result is in flight does not
lose it. Realtime engines set
`realtime=true` and may emit monotonic partials before one final. Buffered
engines set `realtime=false` and normally emit no partial callbacks.

Start denials map as follows:

| Hub reason | `NexusSpeechStopReason` |
|---|---|
| `BUSY` | `DENIED_BUSY` |
| `NO_LINK` | `DENIED_NO_LINK` |
| `NOT_READY` | `DENIED_NOT_READY` |
| `START_FAILED` | `DENIED_START_FAILED` |
| `INVALID_REQUEST` or unknown | `DENIED_INVALID` |

Session endings map as follows:

| Hub reason | `NexusSpeechStopReason` |
|---|---|
| `completed` | `COMPLETED` |
| `cancelled` | `CANCELLED` |
| `no_speech` | `NO_SPEECH` |
| `error` or unknown | `ERROR` |
| `link_lost` | `LINK_LOST` |
| `revoked` | `REVOKED` |

`NexusSpeechError` exposes the slice-1 `SttErrorKind` name and optional
provider/detail. `stop()` is idempotent: while active it sends one stop request
and immediately finishes locally with `CANCELLED`; late replies/events remain
consumed by the sticky typed route. Approval loss or direct client close
terminates with `ERROR`, while the service's normal close/destruction calls
stop first and terminates with `CANCELLED`.

## 4. Approve and debug

After installing the APK, open **Rokid Nexus → Settings → Plugin access**. Review
the requested capabilities and approve only those needed. Pending, denied,
disabled, invalid, and missing-capability plugins are not launchable.

For local software validation:

```powershell
.\gradlew.bat :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug
```

**Settings → Advanced → Developer mode** is a global toggle. It unlocks the
Bus inspector, a live journal of plugin traffic and rejections, and shows DEV
badges; package, signer, API, and route details are available with developer
details. Logs and bug reports must redact device identifiers, signing digests,
credentials, locations, user text, and full payloads.

Normal use should not require ADB. The present repository still needs owner-run
device validation for APK install/update, glasses accessibility onboarding,
force-stop wake, input, revoke, and CXR-L/SPP continuity. Those are deployment
and hardware gates, not SDK initialization requirements.

Debug builds include a phone-hub-owned end-to-end image probe. With both hubs
installed, the glasses accessibility service armed, and SPP connected, run:

```powershell
adb -s $phone shell am broadcast -n com.anezium.rokidbus.phone/.PhoneProbeBroadcastReceiver -a com.anezium.rokidbus.phone.PROBE --es probe image-surface
```

This loads the bundled 480x480 JPEG in the phone hub and sends it through the
normal SPP frame, glasses validation/decode, and HUD renderer. The receiver is
present only in debug builds.

Compatibility details and reserved lifecycle payloads live in
[BUSSPEC.md](../BUSSPEC.md). [`plugins/sample`](../plugins/sample) is the
canonical headless template: package `com.anezium.rokidbus.plugin.sample`,
`minSdk 30`, a headless manifest, and a NexusUi/BusTheme settings screen with
the required uninstall row.

This project is licensed under the [Apache License 2.0](../LICENSE).
