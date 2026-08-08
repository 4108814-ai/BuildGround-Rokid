# Ink Surface slice 2 reconnaissance — glasses foreground-surface stack

Scope: `ink-surface`, read-only source investigation; no build or device work. Citations are repository-relative `file:line` references.

## Stack at a glance

```text
external plugin / built-in phone plugin
  -> phone BusHubService local route
     -> capability authorization
     -> owner-id rewrite + foreground arbitration + per-surface seq
     -> CXR (JSON envelope <= 3 KiB) or SPP (larger JSON / all binary)
        -> glasses CxrBusBridge or SppServerManager
           -> GlassesHub.onRemoteEnvelope
              -> SurfaceController
                 -> accessibility overlay (default) OR SurfaceActivity
                    -> SurfaceHudView
                       -> card/timed-lines in SurfaceHudView
                       -> MediaHudView / ImageHudView

input is the reverse control path:
AccessibilityService / overlay / activity
  -> SurfaceController
     -> /surface/input
        -> phone PhonePluginRegistry
           -> external owner as /system/plugin/input (local surface id)
```

The important architectural boundary is that **phone-side code owns authorization, foreground arbitration, owner namespacing and sequence assignment; glasses-side `SurfaceController` trusts that arbitration and only orders/renders what arrives**. `SurfaceController` has one global `active: NexusSurface?`, but does not compare plugin owners before replacing it (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:24-36`, `:242-268`).

## 1. Surface pipeline: phone -> glasses

### Envelope and route

`BusEnvelope` is `{v, path, id, payload}` JSON plus an optional out-of-band `binary`; JSON serialization deliberately excludes `binary` (`shared/src/main/java/com/anezium/rokidbus/shared/FrameProtocol.kt:11-17`, `:25-44`). Surface paths are `/surface/show`, `/surface/update`, `/surface/hide`; return input is `/surface/input` (`shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt:34-45`).

For an external plugin, the phone route does the following before transport:

1. A surface path is capability-gated through `PathRules.requiredCapability`; all three current lifecycle paths map to `PluginCapability.SURFACES` (`shared/src/main/java/com/anezium/rokidbus/shared/plugin/PathRules.kt:86-92`).
2. The hub rewrites local `surfaceId` to `<pluginId>:<localSurfaceId>` and adds `localSurfaceId` and `ownerPluginId` (`phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginRoutePolicy.kt:62-69`; call site `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:1091-1113`).
3. Image/media-binary validation runs on show/update (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:1116-1123`).
4. Show/update is rejected as `SURFACE_BUSY` unless the sender is the current foreground plugin; an idle HUD may adopt a registered sender only on `show` (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:1179-1188`; `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePluginRegistry.kt:142-155`).
5. The hub assigns a monotonically increasing, per-wire-surface `seq`, tracks visible surface ids per plugin, and treats hiding the last one as plugin self-close (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:1190-1215`).
6. If no local consumer takes it, `sendRemote` sends it to the glasses (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:1238-1254`).

Built-in plugins use the same envelope and add `seq` in `PhonePluginRegistry.withSurfaceMetadata`; they can also inject `handlesBack` from the built-in plugin contract (`phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePluginRegistry.kt:336-346`). External plugins already put `handlesBack` in their typed model payload.

### Transport choice and limits

- `CXR_CONTROL_MAX_BYTES` is **3 KiB**, measured on the serialized whole envelope, not just `payload` (`shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt:29-31`; phone selection at `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:3597-3603`).
- A JSON envelope at or below 3 KiB tries CXR first, then SPP. JSON above 3 KiB requires SPP; without it the error is `NO_DATA_PLANE`. Binary always requires SPP (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:3583-3614`).
- SPP is a 4-byte big-endian body length followed by a JSON or binary body, capped at **2 MiB total body** (`shared/src/main/java/com/anezium/rokidbus/shared/FrameProtocol.kt:19-23`, `:46-75`). A binary body is `format byte + uint16 JSON-metadata length + metadata + bytes`; metadata is therefore at most 65,535 bytes and the whole body still at most 2 MiB (`shared/src/main/java/com/anezium/rokidbus/shared/FrameProtocol.kt:77-110`).
- The public surface SDK separately caps non-binary surface `payload` JSON at **64 KiB**, before the phone adds owner/seq fields and before the outer envelope (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:533-544`, `:560-570`). That is an SDK preflight, not the SPP framing ceiling.
- Image bodies are JPEG/PNG <= 65,536 bytes, <= 512 px on either edge, <= 512² pixels, and image sends are spaced by at least 150 ms (`shared/src/main/java/com/anezium/rokidbus/shared/ImageSurfaceContract.kt:25-37`, `:49-93`; SDK limiter `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:516-530`). Media binary artwork reuses that contract but tightens the edge to 256 px (`shared/src/main/java/com/anezium/rokidbus/shared/MediaArtworkContract.kt:5-16`, `:31-46`).

The phone sends CXR as serialized JSON in `Caps` under key `rokidbus` (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:3624-3630`). Glasses CXR-S subscribes to that key, decodes raw/Caps payload variants, parses the envelope, then posts it to the main thread and calls `GlassesHub.onRemoteEnvelope` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/CxrBusBridge.kt:18-29`, `:86-117`). The glasses SPP server reads the same `FrameProtocol` and calls the same hub entry point (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SppServerManager.kt:90-110`). The hub starts both links together (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesHub.kt:153-166`).

`GlassesHub.onRemoteEnvelope` gives tier controllers first refusal; surface traffic reaches `SurfaceController.handleSurfaceEnvelope` after TTS, pin, notice and activity checks (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesHub.kt:264-284`). `SurfaceController` recognizes exactly the three `/surface/*` paths, schedules show/update/hide on main, and returns `false` for anything else (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:65-81`).

### Current content shapes and glasses parsing

All four current kinds share `surfaceId`, hub-added `seq`, `kind`, optional `contentKey`, and optional `handlesBack` where supported:

| kind | typed wire payload | glasses parser / validation |
|---|---|---|
| `card` | `title`, `lines`, optional `subtitle`, `footer`, `contentKey`, `handlesBack`; each line is a string or `{text,badge,trail,sub,tone,selected}` (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:45-78`, `:81-122`) | `NexusSurface.fromPayload` accepts strings/objects and materializes `SurfaceRow` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceModels.kt:203-272`) |
| `timed-lines` | `title`, `contentKey`, `lines:[{timeMs,text}]`, `anchor:{positionMs,playing,sentAtElapsedRealtime}`, optional subtitle/footer (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:124-176`) | same parser builds `TimedLine` and `SurfaceAnchor` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceModels.kt:273-301`) |
| `media` | `mediaVersion:1`, `contentKey`, chrome/title fields, `mediaTitle`, optional artist/album/footer, anchor with speed/duration, and optional `artwork` as `mono1` base64 or binary metadata (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:218-280`) | parser builds media fields/artwork and preserves compatible prior bitmap (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceModels.kt:216-229`, `:302-316`); binary artwork is contract-validated before publication (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:95-110`) |
| `image` | `imageVersion:1`, `contentKey`, MIME, dimensions, SHA-256, optional title/caption/footer/handlesBack, plus binary body (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:283-319`) | strict shared validation, then `RGB_565` decode with declared dimensions/MIME checked again (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:95-110`; `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/ImageHudView.kt:53-76`) |

The kind allowlist is hard-coded to these four values (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceModels.kt:197-207`). Card/timed/media text limits are strongly enforced by SDK constructors (64 card rows, 2,000 timed lines, 120-char titles, 240-char lines, 128-char content keys: `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:55-61`, `:91-100`, `:157-163`, `:560-570`), but glasses parsing is substantially more tolerant than image validation. Ink must not rely on “the SDK would have rejected it” as its only glasses-side defense.

## 2. View hosting, viewport and wake

### Overlay first, activity fallback

The configured default is `OVERLAY`. The preference may explicitly select `ACTIVITY`, but otherwise overlay wins because a `TYPE_ACCESSIBILITY_OVERLAY` remains visible when another glasses app repeatedly foregrounds itself (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:40-57`).

The overlay renderer is owned by `RokidBusAccessibilityService`. It creates one full-screen, translucent `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_LAYOUT_IN_SCREEN | FLAG_KEEP_SCREEN_ON`, adds a single full-size `SurfaceHudView`, and reuses that root across updates (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceOverlayRenderer.kt:15-23`, `:33-51`, `:62-81`). If the accessibility service/window manager is unavailable, `SurfaceController` falls back to `SurfaceActivity`; an explicitly selected activity path falls back to overlay only if activity start throws (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:352-361`, `:528-540`).

`SurfaceActivity` creates another `SurfaceHudView`, observes `SurfaceController.active`, renders every notification, and finishes when active becomes null. It turns the display on when locked on API 27+ and holds `FLAG_KEEP_SCREEN_ON` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceActivity.kt:10-28`). It is portrait, full-screen, singleTask, excluded from recents and non-exported (`glasses-hub/src/main/AndroidManifest.xml:73-79`; full-screen black theme at `glasses-hub/src/main/res/values/styles.xml:13-21`).

### View tree per kind

`SurfaceHudView` is the foreground-surface composition root. It programmatically creates shared title/subtitle/footer chrome, card/timed text views, a board/list container, `MediaHudView`, and `ImageHudView`; no XML layout or fragment is involved (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt:21-50`, `:65-106`). Dispatch is `image -> media -> timed -> card` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt:141-155`). Card, timed-lines, list and departure-board trees live directly in `SurfaceHudView`; media has `MediaHudView.kt` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/MediaHudView.kt:18-80`); image has `ImageHudView.kt` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/ImageHudView.kt:13-46`).

### Viewport

There is no hard-coded `480x640` layout size in the foreground renderer. Both hosts are `MATCH_PARENT`, so the viewport is the current full display/window bounds (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceOverlayRenderer.kt:38-46`, `:68-71`; activity content is full-screen by theme). The measured target is **480x640 at density 1.5** (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/StatusBadgeGeometry.kt:3-18`). All renderer dp values derive from `resources.displayMetrics.density` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt:630-631`).

Usable content is smaller than 480x640: the root has 18 dp horizontal, 16 dp top and 12 dp bottom padding; the top padding is dynamically increased by a phone/manual/ROM-derived `HudTopInset` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt:65-70`, `:122-139`). List windowing measures the actual `boardView` width/height after chrome layout and chooses visible rows around the selected row (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt:241-270`, `:273-314`). Ink `rpx` should therefore derive from the measured Ink content container, not blindly from 480 pixels, if it is to honor the plan's “existing surface viewport” wording (`plans/020-ink-surface.md:71-75`).

### Wake path

Every full show/update calls `DisplayWakePolicy.requestWake(... SURFACE, requested=true)` immediately before installing the active model (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:242-268`; image/media publication repeats it at `:271-344`). The policy refuses when already interactive, globally rate-limits wake requests to a 5 s window, and otherwise acquires `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` for 3 s (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/DisplayWakePolicy.kt:60-71`, `:146-202`). Once visible, both host windows keep the screen on (`SurfaceOverlayRenderer.kt:38-45`; `SurfaceActivity.kt:16-20`). There is no surface-specific phone brightness request in this path.

## 3. Input and owner return path

### Glasses input routing

`RokidBusAccessibilityService.onKeyEvent` is the global input ingress. For temple/non-R08 events, routing priority is notice BACK/direction/confirm/backdrop, launcher, foreground surface, activity; the first claimant consumes the key (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt:151-202`). The overlay and activity also override `dispatchKeyEvent` and call `SurfaceController` when their own window receives input (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceOverlayRenderer.kt:78-81`; `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceActivity.kt:37-40`).

Temple contact key 83 is delayed to distinguish one/two taps from Nexus's launcher triple-tap; a non-contact key breaks the streak because swipes begin with the same contact (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/TouchpadGestureDetectors.kt:19-50`, `:64-71`). On expiry, one or two pending contacts become key-83 DOWN events sent to the active surface (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt:390-407`). Standard DPAD aliases are deduped: RIGHT/DOWN = forward, LEFT/UP = backward, with a measured 150 ms paired-key window (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/TouchpadGestureDetectors.kt:75-120`; foreground suppression at `SurfaceController.kt:445-455`).

R08 input has a separate path. Ring forward/backward become RIGHT/LEFT DOWN+UP; single tap becomes ENTER; double tap becomes BACK (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RingSurfaceInputPolicy.kt:22-39`, `:45-64`). The accessibility service gives an active surface precedence over activities after notice/launcher ownership (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt:309-346`).

### SurfaceController and phone/plugin return

For current foreground surfaces, renderers do **not** interpret DPAD/tap. `SurfaceController` forwards DOWN and UP for BACK, ENTER, DPAD center/directions, space and media keys as `/surface/input {surfaceId,keyCode,action}` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:200-215`, `:231-239`, `:557-569`). Thus selection/scroll state seen in current card rows is normally recomputed by the phone/plugin and returned as `/surface/update`; it is not native view focus.

On the phone, `PhonePluginRegistry` recognizes `/surface/input`, derives `ownerPluginId` and `localSurfaceId` from explicit metadata or the namespaced id, and sends the event only to the matching active external owner (`phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePluginRegistry.kt:107-126`, `:249-267`). `ExternalPluginController.input` converts this to owner-only `/system/plugin/input` with the local id and raw key/action (`phone-hub/src/main/java/com/anezium/rokidbus/phone/ExternalPluginController.kt:127-143`). The SDK dispatches it only while registered, approved and `opened` (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginClient.kt:545-570`). Built-ins instead receive `NexusPlugin.onInput` on the registry's single plugin executor (`phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePluginRegistry.kt:268-295`, `:324-333`).

### What “plugin handled BACK” means

The key is forwarded first. If the active model has `handlesBack=false`, glasses hide locally immediately. If `handlesBack=true`, glasses leave it visible and arm a **1.5 s failsafe** (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:200-214`, `:478-510`). A plugin handles BACK by receiving the DOWN event and responding before that deadline with either:

- `/surface/update`: e.g. move from a detail page back to a chooser. Publication cancels the failsafe for the same surface id (`SurfaceController.kt:242-268`, specifically `:256`).
- `/surface/hide`: root-level exit. The phone removes the last tracked surface and calls `onPluginSelfHid` (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:1203-1210`); the controller then delivers `/system/plugin/close` with type `self_hidden`, unbinds, and clears foreground ownership (`phone-hub/src/main/java/com/anezium/rokidbus/phone/ExternalPluginController.kt:188-208`, close payload shape at `:345-363`).

If no update/hide arrives, the failsafe only hides glasses-local state (`SurfaceController.kt:495-509`); there is no current `/surface/closed(reason)` notification. That asymmetry matters for Ink lifecycle design.

## 4. Update/re-render path and HUD motion

### Current updates

Every ordinary show/update reparses a `NexusSurface`, using the previous model only to merge omitted fields for the same `surfaceId`, `kind` and compatible `contentKey` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:83-110`; merge rules `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceModels.kt:203-245`, `:273-316`). The controller then replaces `active`, notifies observers, and calls the selected host (`SurfaceController.kt:242-268`).

Delivery can cross CXR/SPP queues, so `SurfaceOrderingCoordinator` keeps per-surface base/all-message watermarks and drops stale bases, anchors and hides. Its special partial-update support is limited to timed-lines/media anchor-only updates; one future anchor can be stashed until a matching base arrives (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceOrderingCoordinator.kt:38-54`, `:56-113`, `:115-150`; anchor classification `SurfaceController.kt:364-371`).

The host root is reused, but render work is mostly a full logical render:

- `SurfaceHudView.render` cancels/restarts its ticker and calls `renderNow` (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt:108-120`).
- Plain/timed views overwrite text/visibility. Card lists and boards call `removeAllViews`; list rows are newly constructed, measured, then only a calculated visible window is attached (`SurfaceHudView.kt:206-240`, `:273-314`, `:466-495`). There is no RecyclerView or reusable node registry.
- `MediaHudView` mutates persistent child views and skips artwork reconstruction when its key is unchanged (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/MediaHudView.kt:82-102`, `:118-165`).
- Binary image decode runs on a two-thread executor, keeps the previous visible image until decode completes, rejects stale completions by `(surfaceId, seq, contentKey)`, and publishes on main (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt:271-349`).
- Playing timed-lines repaint every 100 ms; media progress every 500 ms (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt:610-635`).

This path has no generic `RenderPatch` application. Ink needs a persistent `nodeId -> View/node` index and patch executor inside its renderer; funneling patches through existing `NexusSurface.fromPayload` would collapse them into full model replacements.

### Reusable motion layer

`HudMotion` is the native-View motion vocabulary. It defines 180 ms micro, 280 ms standard and 240 ms exit durations, fast-out-slow-in enter, fast-out-linear-in exit and symmetric pulse interpolators, plus a global instant-motion kill switch (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/HudMotion.kt:26-56`). `HudMotion.pulse` drives a short scale refresh (`:58-74`). `HudMotionValue` cancels an in-flight animator and retargets from its current value; cancellation suppresses stale completion callbacks (`:77-134`), with explicit `snapTo`/`cancel` (`:136-146`).

The layer is utility, not automatic surface transition machinery: a new renderer must opt into it property by property. Its conventions are: state-change motion only, no decorative idle loops, one progress value for coherent morphs, and never animate window layout params—animate child bounds inside a fixed union-sized window (`plans/013-hud-motion.md:42-55`, `:60-84`). `HudFrameMeter` uses `Choreographer` and reports fps, p50, p95 and jank >32 ms (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/HudFrameMeter.kt:19-36`, `:46-80`). On hardware the accessibility overlay sustained ~60 fps with p95 16.71 ms; native Views cost 7.7% CPU versus the rejected WebView's ~1.2 cores, +88 MB PSS and 2.2 s cold paint (`plans/013-hud-motion.md:86-112`).

Ink transitions should reuse the duration/interpolator/retarget rules, but generic style transitions will require an adapter above `HudMotionValue`; the current helpers do not parse transition declarations, animate colors/layout tuples, or maintain multiple property animators.

## 5. Renderer capability advertisement and phone SDK checks

Capability traffic uses `/system/hub/capabilities` (`shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt:133-138`). Current renderer feature bits are image `1<<1`, pin `1<<5`, notice `1<<6`, activity `1<<7`, and TTS `1<<9`; there is no generic card/timed/media renderer bit and no Ink bit (`shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt:172-182`).

The additive glasses capability payload is version 1 and carries `features`, per-tier versions for image/pin/notice/activity, max image bytes, app version and setup/TTS state (`shared/src/main/java/com/anezium/rokidbus/shared/GlassesHubCapabilitiesContract.kt:7-32`, JSON at `:82-117`). Glasses announce it whenever SPP or CXR comes up and on setup changes (`glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesHub.kt:182-200`, `:393-440`). The advertised values and bits are assembled at `GlassesHub.kt:443-487`.

Phone parses that payload and accepts a tier only when protocol version, feature bit and tier version match (plus image byte budget) (`phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt:5122-5141`). `BusHubService.capabilities()` turns accepted remote versions into the live Binder bitmask; image additionally requires SPP because binary images have no queue/resend path (`BusHubService.kt:5062-5098`). The public client reads the live Binder value (`bus-client/src/main/java/com/anezium/rokidbus/client/BusClient.kt:296-314`), while `NexusPluginClient` combines that bitmask with link state and typed API preflight (`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginClient.kt:45-66`; image send check `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/SurfaceModels.kt:493-513`).

For Ink, the established pattern is therefore: allocate a new `BusCapabilityBits.INK_SURFACE`, add `inkSurfaceVersion` to the additive capability JSON, advertise `InkWire.VERSION`, parse/store it phone-side, expose it in the Binder mask under an explicit link policy, and make the future SDK return `CAPABILITY_NOT_AVAILABLE` before sending. If Ink documents may be up to 64 KiB, a show cannot be assumed CXR-capable; capability semantics must state whether SPP is required for initial show or whether a smaller/queued fallback exists.

## 6. Concrete extension points for an `ink` foreground tier

### First resolve the route/grant mismatch

The approved plan currently says dedicated `/ink/show`, `/ink/update`, `/ink/hide` and a distinct signer-bound `ink_surface` grant (`plans/020-ink-surface.md:77-92`). The current stack, however, only applies ownership injection, `SURFACE_BUSY`, visible-id tracking, self-close, seq assignment and `SurfaceController` dispatch to `/surface/*` (`BusHubService.kt:1091-1215`; `SurfaceController.kt:65-80`). Capability authorization is path-only (`PathRules.kt:86-104`), so merely using `/surface/show` with `kind:"ink"` would inherit `surfaces`, not enforce the distinct `ink_surface` grant.

Two coherent implementations exist:

1. **Dedicated `/ink/*` tier (matches plan/grant).** Add constants in `BusPaths`; add `PluginCapability.INK_SURFACE` and map `/ink/*` in `PathRules`; generalize `PluginRoutePolicy.injectSurfaceOwner`, `BusHubService` ownership/seq/visible-set/self-hide blocks, and `PhonePluginRegistry.allowExternalSurface` so Ink participates in the same single foreground owner. Route the glasses envelopes in `GlassesHub` to either an `InkSurfaceController` sharing the host/arbitration state or normalized methods on `SurfaceController`. Add owner-scoped/direct-reply paths for `ready/action/closed/error` to `PathRules.isDirectReply/isOwnerScoped` (`shared/src/main/java/com/anezium/rokidbus/shared/plugin/PathRules.kt:60-84`). This is more plumbing but preserves the separate grant cleanly.
2. **`/surface/*` with `kind:"ink"` (smallest renderer hook).** Add `KIND_INK` and its document/patch fields to `NexusSurface.fromPayload`, then add an `InkHudView` branch in `SurfaceHudView`. Existing seq, wake, overlay/activity, input return and arbitration work automatically. To keep `ink_surface` distinct, phone authorization must become payload-aware before `PathRules.requiredCapability`, which is a broader security-policy change and must reject raw Ink payloads from plugins holding only `surfaces`.

Do not ship a hybrid in which `/ink/*` bypasses the phone's visible-surface set or foreground controller; glasses `SurfaceController` alone does not enforce ownership.

### Renderer and host

- Put the native renderer beside the current programmatic View renderers, conventionally `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/InkHudView.kt`. It should own persistent document/node/view indexes and expose `show(document)`, `apply(patch)`, `clear`, and local input/action APIs.
- Add one `InkHudView` child to `SurfaceHudView` and make kind dispatch mutually exclusive with media/image/card/timed (`SurfaceHudView.kt:21-50`, `:141-155`). This automatically uses both overlay and activity hosts because both instantiate `SurfaceHudView` (`SurfaceOverlayRenderer.kt:62-75`; `SurfaceActivity.kt:23-28`).
- Keep construction programmatic and density/measurement-driven like the surrounding code. Avoid coupling Ink to card's `boardView` manual windowing; it was designed for flat rows, not an arbitrary node tree.
- If dedicated `/ink/*` does not create a `NexusSurface`, share or extract a host abstraction from `SurfaceController.displaySurface` rather than creating a third WindowManager path. Overlay/activity fallback, wake, ring-focus broadcast and launcher-return behavior all currently live around active-surface publication (`SurfaceController.kt:242-268`, `:352-407`).

### Model/patch ingestion

Slice 1 emits compact `INK_DOC_V1`: document `{v,meta?,roots}`, nodes with stable `id`, type `t`, optional text/attributes/style/events/dataset/children, and deterministic JSON (`ink-engine/src/main/kotlin/com/anezium/rokidbus/ink/RenderModel.kt:6-8`, `:16-64`). Patches are `{v,changes}` with add/remove/move/text/attr/style/event/dataset ops (`RenderModel.kt:69-108`, `:111-163`). There is currently **serialization only**, no checked-in `fromWireJson` decoder. Slice 2 needs a strict glasses-side decoder/validator and should not pull the whole phone compiler into the APK merely to recover these models; `ink-engine` is a Kotlin/JVM module with `org.json` compile-only (`ink-engine/build.gradle.kts:1-20`).

Patch application needs explicit base identity/revision semantics. Current surface seq ordering can drop stale envelopes, but a patch is not a self-contained base: if patch N+1 crosses transports and arrives before N, dropping N leaves an invalid document. `RenderPatch` presently carries only `v` and changes, no base revision/document hash (`RenderModel.kt:99-108`). Add a document/session id plus base/target revision and define resync/full-document recovery.

### Arbitration and lifecycle

- **Single foreground owner:** preserve `PhonePluginRegistry.allowExternalSurface`/`ExternalPluginController.active` as the authority (`PhonePluginRegistry.kt:142-155`; `ExternalPluginController.kt:39-45`, `:154-178`). Glasses has one active presentation but is not the security boundary.
- **Show:** validate contract/version/budgets on phone and again at glasses edge; assign owner and seq; wake; install document; emit owner-only `ready` only after the view tree is attached/measured.
- **Update:** validate owner/session/revision; coalesce at the phone boundary if specified; apply on the UI thread, with expensive JSON decoding/validation off-main and only View mutation on-main.
- **Hide:** order against seq/revision, cancel all animators/callbacks, detach/release assets/views, clear node indexes, then participate in the same “last visible surface => plugin self-close” accounting (`BusHubService.kt:1203-1210`; `ExternalPluginController.kt:188-208`).
- **Closed reasons:** current `/surface` has none; local BACK failsafe can disappear without telling the phone. Ink's planned `closed(reason)` therefore needs a new owner-scoped glasses->phone event and reasons such as `user`, `plugin`, `replaced`, `link_lost`, `revoked`, `invalid_patch`/`renderer_error`. Keep it distinct from session-level `/system/plugin/close`, which currently carries types such as `self_hidden`, `switch`, `revoked` or `package_unavailable` (`ExternalPluginController.kt:188-208`, `:210-253`, `:345-363`).

### Input/action model

Current surfaces send raw input to the phone; the current renderers do not consume it. Ink needs glasses-local focus/scroll/tap to meet native rendering and low-latency action semantics. Add a renderer input contract that returns one of: consumed locally; emit action `(actionId,dataset)`; request close/back; not consumed. Call it **before** unconditional raw forwarding in `SurfaceController.handleKeyEvent` (`SurfaceController.kt:200-215`). Preserve DPAD pair dedupe and R08 translation upstream. BACK should first unwind Ink-local focus/modal/scroll state, then emit/forward owner BACK if declared, then use the existing failsafe/close escape route.

Because overlay and activity each own a different `SurfaceHudView`, do not store the only input state in whichever View instance happens to be alive. Either make `SurfaceController` own the Ink interaction state and views project it, or register exactly one live renderer delegate and clear it atomically on host switch/service destruction.

### Text, green styling, fonts and glyphs

- `BusTheme` supplies glasses black plus `text`, `muted`, `dim`, phosphor green `#71FF97`, hairline and danger tokens; dp conversion is centralized there (`bus-client/src/main/java/com/anezium/rokidbus/client/ui/BusTheme.kt:16-39`). The foreground root explicitly uses pure black (`SurfaceHudView.kt:65-70`).
- Current surface text uses `Typeface.MONOSPACE`, removes font padding, disables hyphenation, and uses high-quality break strategy (`SurfaceHudView.kt:616-628`; duplicated in `MediaHudView.kt:189-203`). The helper is private; there is no reusable glasses typography factory yet. Ink should share/extract tokens/helpers rather than copy a third private `monoText`.
- `NexusGlyphs` is the built-in 24x24 state-glyph vocabulary with additive unknown->dot fallback (`bus-client/src/main/java/com/anezium/rokidbus/client/ui/NexusGlyphs.kt:6-22`, `:24-48`, `:52-85`). Custom glyph geometry is drawn with platform-owned green, 1.7-unit rounded stroke on a 24-unit viewport (`bus-client/src/main/java/com/anezium/rokidbus/client/ui/GlyphDrawable.kt:14-25`, `:34-52`, `:75-87`). Reuse these for semantic actions; do not let page data choose arbitrary Android drawables/colors.
- The plan's monochrome clamp is consistent with existing tokens (`plans/020-ink-surface.md:52-54`). Literal Ink colors still need renderer-side clamping because `RenderDocument.style` is untrusted wire data at the glasses edge.

## 7. Dependencies and APK posture

Direct `glasses-hub` production dependencies are the two project modules `:shared` and `:bus-client`, Rokid `cxr-service-bridge:1.0-20260522.063600-105`, `dadb:1.2.10`, `kadb:2.1.1`, and `kotlinx-coroutines-core:1.10.2` (`glasses-hub/build.gradle.kts:45-52`). Through `:bus-client`, the app also gets `androidx.core:core:1.13.1` and `kotlinx-coroutines-android:1.10.2` (`bus-client/build.gradle.kts:33-39`).

There is **no Google FlexboxLayout, ConstraintLayout/RecyclerView, Lottie, chart, or other general layout/animation library** declared in `glasses-hub`. Existing surface layout is platform `LinearLayout`/`FrameLayout` plus custom `View`/Canvas code (`SurfaceHudView.kt:15-18`, `:21-50`; `ImageHudView.kt:3-14`). `ValueAnimator`/`PathInterpolator` are platform APIs (`HudMotion.kt:3-7`). Therefore the plan's `FlexboxLayout` and `LottieAnimationView` are new dependencies, not already-paid APK/runtime cost (`plans/020-ink-surface.md:56-69`).

APK posture is lean-runtime but not shrink-optimized: release has `isMinifyEnabled=false` and no `shrinkResources` (`glasses-hub/build.gradle.kts:19-27`). A prior camera plan stated a hub APK check target of roughly **<25 MB**, but no current build task or enforced Gradle size gate is present (`plans/007-camera-platform-and-lens-plugin.md:187-195`). This investigation did not build or inspect an APK, so it makes no claim about current bytes. Runtime posture strongly favors native Views: the measured WebView alternative added 88 MB PSS and 2.2 s cold paint (`plans/013-hud-motion.md:14-27`). Adding full Lottie/flex/chart libraries will land unshrunk unless release posture changes; evaluate their AAR/dex/native/resource cost separately before accepting them.

## Risks for slice 2

1. **Route/grant mismatch:** `/ink/*` in plan 020 bypasses every current `/surface/*` ownership/arbitration/seq block unless deliberately generalized; `kind:"ink"` on `/surface/*` cannot enforce distinct `ink_surface` with today's path-only capability policy.
2. **No wire decoder:** Slice 1 serializes `RenderDocument`/`RenderPatch` but has no glasses-side `fromWireJson` parser. Depending on all of `:ink-engine` would also bring phone compiler/evaluator code into the glasses artifact unless models/codecs are split.
3. **Patch chain has no base revision:** cross-channel reordering is already expected, while `RenderPatch` has no base/target document revision. A later dependent patch can arrive first and make the subsequent older patch get dropped; full resync semantics are required.
4. **Current update model is mostly rebuild:** card/list rendering creates fresh row Views and uses `removeAllViews`; it has no node registry, adapter recycling, subtree invalidation or stable-view identity. Ink must implement those rather than inherit card update behavior.
5. **Flat-list viewport assumptions do not generalize:** current list visibility is computed from measured row heights in one vertical `boardView`. Nested flex, absolute children, scroll-view and multiple axes need their own bounded measurement/scroll rules within the actual remaining viewport.
6. **Input is phone-owned today:** `SurfaceController` forwards raw keys without consulting the renderer. A node-tree renderer needs a new local input/focus/action contract, and must preserve temple/R08 dedupe plus a guaranteed BACK escape route.
7. **Two host instances:** overlay and activity each construct their own `SurfaceHudView`. Renderer state, node maps and animators can split or duplicate during fallback unless controller ownership/host registration is explicit.
8. **Main-thread pressure:** CXR arrives on main and SPP is normalized to main before current parsing/publication; `NexusSurface.fromPayload` runs on main. Parsing/validating a 256-node document and constructing/patching a large View tree there can miss frames. Decode/validation should be off-main with revision-checked main-thread commit, following the image decode coordinator pattern.
9. **Lifecycle asymmetry:** current BACK failsafe hides locally without a glasses->phone closed event, so phone foreground ownership can remain stale. Ink's `closed(reason)` cannot be documentation-only; it must be wired and owner-scoped.
10. **Resource cancellation/recycling:** current controller manually recycles only the one active image bitmap and invalidates only image decode work (`SurfaceController.kt:393-407`, `:549-555`). Ink must cancel per-node animators, delayed callbacks, chart/canvas loops and asset decodes on replace/hide/service destruction without recycling a resource still referenced by a replacement node.
11. **Animation helpers are primitives, not a transition engine:** `HudMotionValue` handles one float and interruption; Ink still needs property typing, layout/transform application, coordinated multi-property retargeting, and loop/battery policy. `HudMotion.enabled` is not yet wired to accessibility/battery settings (`HudMotion.kt:49-56`).
12. **Continuous work can survive invisible state unless guarded:** current timed/media tickers are explicitly removed on every render/detach (`SurfaceHudView.kt:108-133`). Every Ink chart/canvas/Lottie loop needs the same visibility/attachment lifecycle and plan-defined fps budget.
13. **Transport budget is layered:** the SDK's 64 KiB payload cap is not the CXR cap and does not include owner fields/outer envelope. Initial Ink documents near the cap always need SPP; small patches may choose CXR, creating the exact cross-queue ordering hazard above.
14. **Glasses-side defense is mandatory:** card/text parsing is tolerant because typed SDK constructors are the usual producer; Ink exposes a richer, more dangerous tree. Recheck version, node/depth/size/style/asset/action bounds at the glasses edge even when the phone compiler says the document is valid.
15. **Dependency/APK cost is not prepaid:** FlexboxLayout/Lottie/chart libraries are absent and release shrinking is off. Their memory, dex/resource size, cold initialization and animation behavior on API-31 Android Go glasses must be measured before M2, not inferred from desktop/phone behavior.
16. **Typography/design helpers are fragmented:** colors are centralized, but glasses `monoText` factories are private/duplicated. Without extracting a small glasses style adapter, Ink can drift in font padding, break strategy, sizes and phosphor/muted treatment.
17. **Ordering identity may be insufficient:** current ordering keys bases by `(surfaceId, kind, contentKey)` and has special logic only for anchors (`SurfaceOrderingCoordinator.kt:67-89`, `:93-113`). Ink needs a session/document identity plus patch revision; reusing `contentKey` alone risks applying a patch to a structurally different document.
