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
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.3.0")
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
glasses announce renderer features, image/pin surface versions and image limits, their app version,
and onboarding completion (`GlassesHubCapabilitiesContract`); unknown fields
stay ignorable in both directions.

Surface IDs are local to the plugin. The SDK validates fields and payload size;
the hub injects verified ownership and global sequencing. High-level code cannot
set a trusted owner, global sequence, or arbitrary system path.

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

Because of that, set `ttlMs` whenever the pin describes something with a
natural end. It is the only thing bounding a pin whose owner never comes back —
if your process is killed before it can `hidePin`, the TTL is what stops the
pin becoming a permanent ghost.

Check the live `supportsPinSurface` value immediately before use. Both methods
return `CAPABILITY_NOT_AVAILABLE` without sending unless the glasses announced
pin v1 and SPP is live. Old glasses therefore continue to work without a
plugin API bump.

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
