# Plan 017 — Relay notifications

Status: spec decided, unbuilt. Depends on 011 (notice surface) and 015 (pages
and images), both shipped in sdk-v0.7.0, on 009 (speech to text), and on 016
(display wake), which is unbuilt and blocking.

Source app: **Rokid Relay v0.1.16**, `E:\Tools\Rokid\Rokid Relay`. Inventory of
record: Codex read-only report, 2026-07-31, `E:\Tools\codexbg\runs\0731-142150`.

## Goal

Bring Relay's one real capability into Nexus: a phone notification arrives in
the wearer's eye, and they answer it with their voice without touching
anything.

This is the use case plan 011 was written for and the trigger plan 015 named.
The tier is built, the speech stack is built, the transport is built. What is
missing is the notification-shaped code, and nothing else.

## What Nexus already provides, and what that deletes

Relay is two apps carrying an entire platform underneath one feature. Nexus has
that platform. The port is therefore mostly a subtraction, and the table is the
plan's central claim:

| Relay subsystem | Replaced by |
|---|---|
| `CxrLAuth`, phone `RelayBridge` — CXR-L lifecycle, reconnect, epoch guards | the hub |
| The entire glasses APK — `RelayHudView`, `NotificationTextPager`, a11y overlay, image LRU | the notice tier; pages and geometry are platform-owned |
| `RelayInputInterpreter` — LLRR combos, two-finger mode, DPAD parasites, debounce | the notice input claim; the inbox opens from the Nexus menu |
| Self-arm, `ClientBootstrap`, `GlassesHelperUpdater`, watchdog script | the hub |
| Five STT engines, `CxrBufferedAudioCapture`, audio routing | the `stt` capability, one `nexusSpeechSession()` |
| `BleWakeServer` / `BleWakeClient` — waking a sleeping phone from the glasses | nothing: the Nexus phone hub is a permanent foreground service with SPP reconnect |
| `CompanionDeviceCoordinator`, Hi Rokid authorization UI, phone self-update | the hub |

What survives is exactly what is about notifications: the listener, the capture
and dedup rules, the text extraction, the image extraction, and firing the
`RemoteInput`. Those parts are worth porting closely — they encode a lot of
field-earned knowledge about how Android apps actually shape their
notifications — and everything around them is worth deleting.

## Form: an external plugin

`nexus-relay`, its own repo, published through the registry like photosync.
Capabilities `surfaces,stt`; receive prefixes `/plugin/relay,/system/plugin,/stt`.

Notification access is the most invasive grant on Android. It has to be a
deliberate install, not a checkbox inside an app someone installed for a
different reason. The hub stays neutral, which is the standing decision from
plan 003 onward.

**The process stays alive by itself.** A bound `NotificationListenerService`
keeps it resident, which is what makes the plugin viable at all: `PendingIntent`
and `RemoteInput` are live objects that cannot be serialized, persisted, or
handed across processes, so whoever holds them must still be running when the
wearer answers. The plugin connects to the bus to say something and disconnects
after, honouring the background policy in PLUGINS.md — the listener, not the bus
connection, is what keeps it warm.

## The pieces

### 1. Listener and admission

`RelayNotificationListener extends NotificationListenerService`, exported under
`BIND_NOTIFICATION_LISTENER_SERVICE`. Setup opens
`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`; the grant is detected by
searching `enabled_notification_listeners` for the package.

Admission, in order:

1. The plugin is enabled and its allowlist admits `sbn.packageName` (§7).
2. The notification carries a repliable action — the first action with a
   non-null `PendingIntent` and at least one `RemoteInput` with
   `allowFreeFormInput`. Data-only inputs and canned-choice-only actions do not
   qualify.
3. It is not a duplicate of what was already shown (§2).

Relay's admission is items 2 and 3 only. Item 1 is new and is the difference
between a relay and a firehose.

**`onListenerConnected()` re-enumerates `activeNotifications`** and rebuilds the
pending map — after process death the live objects are gone and this is the only
way back. **`requestRebind()` on disconnect** is added; Relay has no recovery
path and simply waits for Android to reconnect it, which is a silent way to stop
working forever.

### 2. Identity, revision, dedup

- Stable id: first 10 bytes of `SHA-256(sbn.key)` as 20 hex characters. The raw
  key is retained privately for cancellation only.
- A capture compares package, app label, title, rendered text, revision and
  image id against the previous entry for that id.
- A notice is pushed only when the content actually changed **and** this is the
  most recent capture. An unchanged repost refreshes the inbox silently.

Ported as-is. These predicates are why Relay does not flash the same message
three times when an app repaints its notification.

### 3. Text extraction

Precedence: `MessagingStyle` messages → `EXTRA_TEXT_LINES` when it is genuinely
richer → `EXTRA_TEXT_LINES` → `EXTRA_BIG_TEXT` → `EXTRA_TEXT`. MessagingStyle
messages are timestamp-sorted, trimmed, limited to the newest N, and rendered as
`sender: text` when a sender name exists. The "genuinely richer" test — collapsed
summaries like `"\d+ new messages"` are always beaten, otherwise the candidate
must carry at least the same newline count plus 24 more normalized characters —
is ported verbatim. It is the kind of heuristic nobody rediscovers on purpose.

**The band's caps become the plugin's problem, in the right direction.** Title is
32 characters, body is 1024, footer is 40. Relay has no text limit at all and
bounds by message count instead, so a 20-message thread can overrun 1024. The
plugin trims **from the top** — oldest messages first — so what survives is the
part the wearer is being interrupted about. Being ellipsized by the renderer is
the safety net, not the design.

Title is `sender` when the notification names one, and the app label otherwise.
The app label always appears somewhere; a message with no visible source is a
message the wearer cannot judge.

### 4. Image

Ported from `NotificationImageExtractor`: newest MessagingStyle `image/*`, then
`EXTRA_PICTURE` / `android.pictureIcon`. Not `largeIcon` — Relay's changelog
claims it and its code does not do it; we take the code's word.

Recalibrated for the Nexus image channel: **512 px longest edge, 64 KiB**
([ImageSurfaceContract.kt:31](../shared/src/main/java/com/anezium/rokidbus/shared/ImageSurfaceContract.kt)),
against Relay's 360 px / 80 KiB. The retry ladder keeps its shape — successive
edges and JPEG qualities until it fits — with its steps moved onto the tighter
byte budget. Off by default, as in Relay: an image costs roughly 180 ms of link
time and most notifications do not have one worth the wait.

### 5. The notice

```kotlin
showNotice(
    NexusNotice(
        title = sender ?: appLabel,
        body = renderedText,
        footer = appLabel,
        actions = listOf(reply, dismiss),
        wakeDisplay = true,   // plan 016
    ),
    imageBytes,
)
```

Paging, page breaks, the `2/4` indicator, the chip row, selection wrap, the
one-answer rule and the TTL are the platform's. The plugin sends content and
never layout — it does not learn what a page is.

Note the tier's own constraint: **a notice is paged or it is answerable, never
both.** A long message with a reply chip is not paged; the wearer reads what
fits and opens the thread in the inbox for the rest. That is plan 015's rule and
this plan does not negotiate with it.

### 6. Replying

The flow is Relay's, with one change, and it needs no re-show. A `/notice/update`
that carries `actions` re-arms the band's answer
([PhoneNoticeState.kt:148](../phone-hub/src/main/java/com/anezium/rokidbus/phone/PhoneNoticeState.kt)),
so one notice carries the whole exchange:

1. `showNotice(actions = [reply, dismiss], wakeDisplay = true)`
2. `onNexusNoticeAction("reply")` → `updateNotice(footer = "Listening…")`
3. `nexusSpeechSession().start()`; `onSpeechPartial` → `updateNotice(footer = partial)`
4. `onSpeechFinal` → `updateNotice(body = transcript, actions = [send, retry, cancel])`
5. `onNexusNoticeAction("send")` → fire the `RemoteInput`, then
   `updateNotice(footer = "sent")` and let it expire

Five accepted notice messages per second is the budget; each update restarts the
TTL and the 90-second absolute lifetime bounds the whole exchange.

**Step 4 replaces Relay's countdown.** Relay shows the transcript for 2–6.5 s
(180 ms per word) and sends when it reaches zero; confirm means *retry* and
there is no send button. Nothing leaves without a tap here. Auto-sending an
unreviewed voice transcription into a real conversation is a mistake with no
undo, and the chip row makes the explicit version cost the wearer one gesture.

Firing is `RemoteInput.addResultsToIntent` + `setResultsSource(SOURCE_FREE_FORM_INPUT)`
+ `actionIntent.send()`, and on success the delayed cancellation at **250, 1000
and 2500 ms** is ported unchanged. Source apps mutate their notification right
after a reply lands; one attempt loses the race.

Unlike Relay, a failed `send()` is not swallowed into a bare "Reply failed" — the
exception class reaches the footer, because "the app is not running" and "the
intent was cancelled" ask the wearer for different things.

### 7. Allowlist and settings

Per-app allowlist, in the plugin's own phone UI, listing apps that have actually
produced a repliable notification since install rather than every package on the
device. Plus: image previews on/off, messages per thread, pause while the phone
screen is on (Relay's `isInteractive` predicate, ported), and clear-after-reply.

Dropped from Relay, because the platform owns them now: popup duration, font
size, overlay Y offset, inbox combo, swipe mode, STT engine and API keys.

### 8. Glyphs

`reply`, `send`, `retry`, `cancel` and `mic` are added to the **shared** set in
[NexusGlyphs](../bus-client/src/main/java/com/anezium/rokidbus/client/ui/NexusGlyphs.kt),
not shipped as plugin custom paths. They are conversation marks, not Relay
marks; leaving them per-plugin is how five plugins end up drawing five slightly
different arrows. Adding a name was never a breaking change.

### 9. The inbox

A plain surface, opened from the Nexus menu: recent repliable notifications,
newest first, select one to read its thread paged, reply from there through the
same speech flow. Relay reached this with a global `LLRR` touchpad combo caught
by its accessibility service; the menu makes that entire subsystem unnecessary.

Entries whose `PendingIntent` died with a previous process are shown as
unrepliable rather than failing on tap.

## MUST NOT

- MUST NOT log, persist, or send anywhere any notification text, transcript,
  partial, sender name, or image. Not to logcat, not to a crash breadcrumb.
- MUST NOT hold the bus connection open between events.
- MUST NOT push a notice for a notification the allowlist did not admit.
- MUST NOT send a reply without an explicit confirming gesture.
- MUST NOT calculate page breaks, band geometry, text size, or the chip row.
- MUST NOT ship a glasses-side APK, an accessibility service, or any self-arm
  code. If something appears to need one, the design is wrong.
- MUST NOT require the hub to know that notifications exist. No new capability,
  no hub UI, no BUSSPEC change beyond plan 016's `wakeDisplay`.

## Acceptance

1. Unit tests, no device, over the ported logic: the admission predicate, the
   stable id, the dedup comparison, the text precedence ladder including the
   "genuinely richer" heuristic, top-trimming to 1024, and the image retry
   ladder against the 64 KiB budget.
2. A fake notification harness — Relay's `DiagnosticsPanel` had exactly this and
   its absence is why its own QA matrix records the direct-reply run as only
   PARTIAL. Ours ships first, not last: post a repliable thread, append
   messages, attach an image, all without a second phone.
3. On-device end to end: a real WhatsApp message wakes the optics, reads whole,
   answers by voice, arrives in the conversation, and clears from the phone.
4. Burst: five messages in three seconds. No dropped reply, no rate-limit error
   reaching the wearer, and the band ends on the most recent.
5. Recovery: kill the plugin process mid-thread, reopen the inbox, confirm the
   rebuilt pending map and that dead entries read as unrepliable.
6. Denial paths: notification access revoked mid-session; `stt` grant revoked;
   glasses disconnected between the notice and the reply.

## Phase 2 — notifications that cannot be replied to

Deliberately out of v1, deliberately not out of the design.

Restricting v1 to repliable notifications is not only parity with Relay, it is a
free and excellent noise filter: `RemoteInput` is almost a definition of "a human
is talking to you". Everything else — deliveries, banks, alarms, builds — is a
different product: read-only notices, no chips, expiring on their own.

It is worth building, and the notice tier is a better vehicle for it than the
ROM's own notification widget, which is a fixed set fed by CXR that we cannot
extend. Pages, an image, platform-owned geometry and one honest answer beat it
on the two things that matter in the optics: legibility and knowing when you are
being asked something.

The blocker is not the tier, it is the volume. A read-only relay lives or dies on
its filters, so phase 2 is the allowlist growing into a real rules model — per
app, per channel importance, ongoing and group-summary exclusion, quiet hours —
and the notice work is the easy half. Do not start it until v1 has run on the
owner's own phone for a week and produced the list of what actually gets through.
