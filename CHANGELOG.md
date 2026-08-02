# Changelog

## 1.1.4

- **Lists follow the selection past the viewport.** A card list rendered every
  row and let the screen clip the rest: with more conversations than the optics
  hold — eight notifications in the Relay inbox was enough — moving the
  selection past the last visible row kept working but showed nothing, on every
  plugin that sends list rows. The glasses now window the list around the
  selection: the selected row is always fully visible with a row of context
  after it when it fits, and muted `▴ 3` / `▾ 12` markers say how many rows
  hide on each side. A list that fits renders exactly as before. Thanks to
  Brilliant-Flight3682 for the report.

- **A notice can black out everything behind it — when you ask it to.** By
  default a notice still lands on top of whatever the wearer is doing, and the
  scene stays visible around it; that superposition is the point of a
  heads-up display. But a notification arriving over standby widgets reads as
  a collage, so the notice protocol gains `backdrop`: an opt-in, show-only
  flag that fades an opaque black scrim in with the band and hides every
  window behind it until the notice leaves. On the additive optics the scrim
  emits nothing — the rest of the display simply goes away. Relay surfaces it
  as "Black out behind notifications", off by default. Thanks to
  Brilliant-Flight3682 for describing the Even G2 experience this borrows
  from.

- **Glasses updates no longer fail on Android 11 phones.** The downloaded
  glasses APK was verified by asking the phone's PackageManager to parse it —
  and Android refuses to parse any archive whose minSdk it does not meet. The
  glasses hub ships with minSdk 31, so an Android 11 phone, the oldest Nexus
  supports, reported every glasses APK as "unreadable" and could never install
  or update the glasses app over the air, while the same APK installed fine
  over a dev cable. The phone now accepts an archive it cannot parse when the
  GitHub release digest has already verified it; phones new enough to parse
  still enforce the package name as before. Thanks to Sofathinker for the
  report and the settings log that pinned it.

## 1.1.3

- **Messages leave the glasses over SPP again.** 1.1.2 sent control traffic over
  CXR first, on the reasoning that it kept small messages out of the queue photo
  sync moves megabytes through. That reasoning still holds; the path no longer
  does. On Hi Rokid Global G1.11.11.0727 the glasses-to-phone CXR direction does
  not arrive: the glasses report `CXR-S TX ... result=0`, the phone's Rokid app
  logs the frame with its full payload and answers RESPONSE_SUCCEED, and the
  bound third-party client is never called. Opening a plugin from the glasses
  did nothing at all on an updated phone.

  Phone-to-glasses over the same link is unaffected, so the ordering is now
  deliberately asymmetric - the two directions no longer have the same
  reliability, and pretending otherwise cost a working feature. Thanks to
  @gtacoder-collab for the report that pinned it to the callback and for holding
  the line when we reverted it in 1.1.2.

- **The CXR receive path says when it drops something.** An unexpected key or an
  undecodable payload used to return in silence, which made "the callback was
  never invoked" and "the callback ran and threw the frame away" impossible to
  tell apart from a log. They have completely different causes; the absence of
  these lines is what proved which one this was.

## 1.1.2

- **Control messages leave the glasses over CXR again**, with SPP as the
  fallback rather than the first choice. 1.1.1 reversed that order to route
  around a CXR link that reported sends it had not delivered. It was the wrong
  trade: SPP is one RFCOMM channel with a single write lock, and it is the
  channel photo sync moves megabytes over, so a control message queued behind a
  chunk waits for that chunk to finish. CXR's separate path and small size
  ceiling are what keep control traffic out of that queue. The SPP fallback was
  already there and is unchanged; what it cannot cover is a send reported as
  successful that never arrived, which needs a real acknowledgement rather than
  a different running order.

## 1.1.1

A fix for something that only ever worked on one desk, found and fixed by
someone it did not work for.

- **The glasses are found by what they are, not by whose they are.** The phone
  hub matched one hard-coded Bluetooth address, written on the first day of the
  project and never generalised because the only tester owned that unit. Any
  other pair was invisible to it. The hub now identifies the glasses by the
  Nexus SPP service they advertise, with the `Glasses_*` name and the
  last-known address as fallbacks for when Android drops the remote metadata
  after a disconnect. Reported and fixed by Alexander Zhilin (#1, #2).
- **Photo Sync can move captures off any pair of glasses.** Outbound binary
  frames are the one path with no CXR fallback, so without a working SPP socket
  a capture could not leave the glasses at all. It could not have worked for
  anyone but us, and now it does.
- **Control messages from the glasses prefer the live SPP socket**, falling
  back to CXR, rather than the other way round.
- **The update banner no longer asks you to reinstall an app that is current.**
  Glasses going quiet used to erase the record of what they were running, so a
  stopped hub or a dropped link turned into "Reinstall latest glasses app" for
  an app that was already up to date. Both version numbers now survive the hub,
  and a hub that has not reported in yet no longer overrides what they prove.

## 1.1.0

The release Relay arrived in: messages on your glasses, answered out loud,
without touching the phone. Plus a first run that no longer needs a computer,
and the platform work both of those needed.

### Messages on your glasses

- A message you can reply to now reaches the glasses as it arrives, lights the
  display if it was dark, and is answered out loud. Each message keeps its
  sender beside it rather than being flattened into one paragraph, and a long
  conversation turns pages instead of stopping at eight lines.
- Speak your reply and it sends itself after a few seconds - visibly counted
  down on the chip you are looking at, cancellable for its whole length.
  Nothing is sent that you were not shown: if the glasses went dark or lost the
  link before the transcript appeared, the clock stops and waits for you.
- Miss one, or let it expire, and it is in **Messages** in the Nexus menu:
  every conversation waiting on you, newest first, read and answered the same
  way. It updates while you are looking at it.
- Everything with a reply box is relayed, from any app. Nothing else is, and
  nothing is stored: revoke notification access and what it captured goes with
  it.

### First run without a computer

- Setting up a new pair no longer needs a PC or a cable. The phone drives the
  glasses through pairing, turns their Wi-Fi on rather than sending you to
  another app, and hands the six-digit code to the phone instead of leaving it
  in your eyes to type blind.
- Setup asks for one switch at a time and gets out of the way. A single bad
  hand-off no longer closes the door on the whole flow, the manual route gives
  up quickly instead of retrying the slow one, and finishing hands you back to
  Nexus rather than to the ROM launcher.
- Wireless Debugging navigation reads the firmware's own localized labels, so
  it works in every language that firmware ships - no Nexus translation needed.
- Setup keeps a log you can read and send when something still goes wrong.
- The product speaks one language throughout. The half-French screens are gone.

### Glasses that stay in sync

- Captures taken while nothing was listening now arrive. A photo still being
  written left the run eligible while its own bytes were settling, and never
  came back for it; scanning and settling are separate decisions now.
- The hub comes back on its own after a reboot or an app update, instead of
  waiting to be opened by hand while captures pile up on the glasses.

### The HUD

- **Bands can carry a real message.** Up to sixteen structured lines, broken
  where the sender broke them, measured and paged on the glasses themselves.
- **Bands can light a dark display** for an event worth it - at most one wake
  every five seconds across every plugin, always a short pulse, never held on.
- **A band pages unless its answers need the swipes.** One answer or none, and
  the directions turn pages while a tap still replies.
- **Surfaces can be lists**: rows with a second line, a weight that says how
  much each matters, and a selection the glasses draw themselves. A row can
  also be prose under a fixed label, which is what makes a conversation read
  like one.
- A band is answered on the firmware's verdict about a touch, never on the
  contact that opens one - starting a swipe used to count as a tap.
- Five new marks in the shared glyph set: reply, send, retry, cancel, mic.

### For plugin authors — SDK 0.9.0

- `NexusRowTone`, plus `sub`, `tone` and `selected` on `NexusCardLine` and
  `subtitle` on `NexusCard`. Reference: `docs/surface-list-rows.html`.
- `lines` on `NexusNotice` and `NexusNoticeUpdate`; notice surface contract v3.
- `wakeDisplay` on `NexusNotice` and `NexusActivity`, off by default.
- `approvedCapabilities` on the bus: your grants are true by the time you are
  told you are approved, so a plugin that pushes the instant approval lands no
  longer reads an empty grant set.
- Everything above reuses the `surfaces` grant and plugin API version 3. No
  existing plugin needs a change.

### New plugins

- **Relay 1.0.0** - phone notifications on the glasses, answered by voice.
- **Photo Sync 1.0.1** - the auto-sync fixes above.

## 1.0.48

### Activities

- A new tier between a pin and a surface: an ongoing process — a ride
  approaching, a timer running, a transfer in flight — holds a stable corner
  of the HUD. It draws as a compact chip, and the primary activity can open
  into a panel with progress, details, and up to three platform-drawn
  actions the wearer steps through with scroll and fires with a tap.
- A significant update lets the panel flare to catch the eye — throttled by
  the platform, and never by holding the screen on: an activity still cannot
  wake or keep the display, exactly like every other tier.
- An idle panel folds back to its chip after about ten seconds. A new
  Settings switch keeps the primary activity expanded instead; it is the
  wearer's preference, and no plugin can read, set, or override it.

### Notices learn to ask a real question

- A notice can now carry up to three answers, drawn as glyph chips under
  the band — the same row an activity panel uses, drawn by the same view,
  so the wearer learns it once. Scroll steps along it, the selection wraps
  and follows its answer across updates, and a tap fires the one that is
  chosen.
- A notice takes exactly one answer, of either kind. The moment it is
  given, the row leaves the band, nothing fires again, and the phone
  refuses a duplicate — measured on hardware, two temple taps 188 ms apart
  used to mean two replies sent. This deliberately changes 1.0.46
  behaviour, where an interactive band replied on every tap.
- Clearing a field of a shown notice now actually reaches the glasses: the
  phone relays the patch a plugin sent instead of re-serialising its own
  state, so an emptied footer, a withdrawn question, or a cleared row
  arrives as exactly that.

### Plugin SDK 0.6.0

- The activity tier and notice actions land in the SDK: `NexusActivity`,
  `NexusNoticeAction`, `actions` on notices and their updates, the
  `onNexusActivityAction` and `onNexusNoticeAction` callbacks, and a
  nullable `interactive` on `NexusNoticeUpdate` so a band can be asked
  again.
- The guide gains a visual reference of the notice band's four states,
  with the one-answer rule as an interactive demo
  (`docs/notice-band-states.html`).

## 1.0.47

### Phone battery

- Your phone's charge now sits in the glasses' status row, beside the clock
  and the weather: a small phone glyph and the percentage, with a plus while
  it charges. The glasses always knew their own battery; now they admit the
  phone they depend on has one too.
- The chip behaves like the ROM's own indicators. It shows on the launcher
  and its screens, follows the status row when an app like the teleprompter
  moves it, tucks in beside the clock when the weather steps out, and never
  sits on top of anything.
- Not interested? Settings has a switch. Off means off — the chip leaves
  immediately and stays gone.

### Plugin marks

- A plugin's own icon now reaches the glasses. Until now a custom mark showed
  on the phone and fell back to a generic tile in the glasses launcher, which
  never had the plugin's APK to load it from; the mark itself now travels, as
  bare geometry the glasses draw in the HUD's one green.
- The design system holds: a plugin ships a shape, never a colour, a size, or
  a look. Tests now catch a mark that drifts from the rules instead of prose
  hoping it will not.

## 1.0.46

### Notices

- Plugins can interrupt you briefly with a band across the top of your view: a
  message that just arrived, a delivery at the door, one thing that happened
  and is worth a glance. It arrives, says its piece, and leaves on its own.
- You can answer one without opening anything. Tap the band and the plugin
  hears you, even though it has no screen open and never did — which is the
  point of the whole thing. Back always dismisses, and no plugin can take that
  key away from you.
- A band claims two gestures, not your glasses. Scroll still reaches whatever
  is underneath it, the launcher still opens, and every other control keeps
  working while a notice is up.
- Nothing can leave a band in your view: it clears on its own deadline, and no
  amount of updating it pushes past a minute.

### Motion

- The HUD moves now. Bands slide in and out instead of appearing, and the whole
  interface shares one set of timings rather than each screen inventing its
  own.
- Motion marks something happening, never decoration. Nothing on the glasses
  animates in a loop while you are wearing them and walking around.

## 1.0.45

### Pins

- Plugins can leave a small panel pinned in a corner of your view — a plate
  number, a gate, a door code. It stays there while you get on with things, and
  nothing has to hold a screen open to keep it in front of you.
- A pin outlives the plugin that put it there. A plugin woken by a notification
  can push one and go straight back to sleep, which is the whole point: the taxi
  that is eight minutes out should not need an app left open to tell you its
  plate.
- Pins pushed while the glasses are asleep on a table are no longer lost. They
  are kept and delivered the moment the glasses come back, instead of failing
  silently at exactly the moment a background plugin had something to say.
- A pin that names no deadline now clears itself after thirty minutes, so a
  plugin killed before it can tidy up cannot strand one in your view. Plugins
  that know their own horizon still set it, from a second to a day.
- Pins step aside while the camera is in use, and come back afterwards.

### Speech without an account

- Transcription now works out of the box on the phone's own speech engine: no
  key, no account, nothing to pay. It is the default until you pick something
  else, and whatever you pick still wins.
- The microphone is requested where you need it, on the dictation card, instead
  of sending you off to set something up elsewhere first.
- The Speech screen tells the truth about the engine you chose. It no longer
  offers to save an API key for an engine that takes none, and the language grid
  locks itself when the engine detects the language on its own.

## 1.0.44

### Glasses that keep themselves maintained

- Manual setup no longer fails after a successful pairing. It was looking for the
  glasses' connect port over mDNS, which plenty of routers never forward; the
  glasses now hand that port to the phone directly.
- Glasses whose pairing credential is no longer accepted repair themselves: the
  refused identity is dropped and a fresh pairing runs on its own, instead of
  every later maintenance pass failing silently for the life of the install.
- Setup no longer reports itself complete while the pairing is missing, which is
  what let a unit look healthy and still be unable to refresh its own watchdog.

### Fixes

- Text fields are no longer hidden behind the keyboard — the pairing form in
  particular, where you cannot check what you typed against a code that expires.
- The glasses display stays awake while you copy a pairing code, so the dialog
  no longer closes halfway through.

## 1.0.43

### Photos Sync

- Captures taken on the glasses now copy themselves into your phone gallery, in
  the same `Download/Hi Rokid/` album Hi Rokid imports into — over the Bluetooth
  connection the glasses already have, so neither device needs Wi-Fi. Install the
  new Photos Sync plugin from the Store to turn it on.
- Sync runs while charging by default; you can set it to sync as soon as you
  capture, or only when you tap Sync now. Interrupted transfers resume where they
  stopped, and every file is checksum-verified before it reaches the gallery.
- The transfer stays out of the way of everything else on the connection: it
  pauses for a camera session, yields whenever anything else is talking, and
  keeps only one chunk in flight.
- Deleting captures from the glasses after they are safely on the phone is
  available as an opt-in, and honestly reports when the glasses refuse.

### Glasses maintenance

- The command bridge now updates itself from the installed app, so a glasses unit
  whose ADB self-arm has gone stale still receives new privileged capabilities.
- The manual setup flow keeps the glasses display awake while you copy the
  pairing code, and the phone form no longer hides behind the keyboard.

## SDK 0.3.0

- New speech session API: plugins holding the new `stt` capability can start hub
  speech sessions and receive live state, partial and final transcript callbacks,
  without ever touching raw microphone audio or provider credentials. See the
  "Speech to text" section of the plugin SDK guide.

## 1.0.42

### Speech to text

- The hub now runs cloud speech engines against the glasses microphone: pick
  OpenAI, ElevenLabs or Azure, paste your provider key, and dictation works end
  to end from the new Speech settings screen — with live partial text on
  realtime engines and on-phone voice-activity endpointing.
- Twelve transcription languages with provider-tuned handling, including
  Cantonese and both Chinese scripts.
- ElevenLabs keys show the remaining credit balance with a usage gauge that
  refreshes after every dictation.
- Plugins can request the new "Speech to text" capability to receive transcripts
  through the SDK. The grant is separate from the raw microphone grant and is
  managed from the phone permissions screen; provider keys never leave the hub.
- Provider keys are stored encrypted with the phone's hardware keystore.

## SDK 0.2.1

- Restore JitPack distribution after the `sdk-v0.2.0` build failed while
  resolving an unused Kotlin Gradle plugin.
- Make SDK releases wait for the published JitPack POM before creating their
  GitHub release, so a green release can no longer advertise a missing
  artifact.

## 1.0.41

### R08 ring compatibility

- The Rokid R08 ring can now drive the Nexus HUD end to end alongside the R08 Access Bridge companion app: a triple tap opens the Nexus launcher, ring swipes move the selection, a single tap opens the selected plugin, and a double tap goes back.
- Ring control follows you into plugin surfaces. The hub translates ring gestures into the same key input plugins already receive from the temple touchpad, so every existing plugin works with the ring without an update.
- The glasses hub exposes an OPEN_LAUNCHER broadcast endpoint, and hands the ring back to R08 Access Bridge the moment no Nexus UI is on screen (with a bounded handoff while a plugin surface is opening). Requires R08 Access Bridge with the matching "Nexus launcher" ring action.

### Plugin microphone capability

- Plugins can request the glasses microphone and receive 16 kHz mono PCM through the new SDK audio session, with the grant managed from the phone permissions screen. No Android record permission is involved; audio comes from the glasses over the hub.

### Phone app

- The phone app now supports Android 11 and newer (previously Android 12+).

## 1.0.38

- The phone now offers a forced reinstall when the glasses package exists but its hub cannot report a version.
- Wi-Fi activation continues to use the full YodaOS Wi-Fi Settings page before the incompatible panel fallback.

## 1.0.37

- Wi-Fi activation now opens the full YodaOS Wi-Fi Settings page first, matching the proven R08 Access Bridge flow.
- The incompatible Android Wi-Fi panel remains only as a final fallback instead of bouncing users back to the launcher.

## 1.0.36

- Manual setup step 3 now enables glasses Wi-Fi through the privileged local command bridge before falling back to Settings accessibility.
- The flow still waits for a connected Wi-Fi network and the real Wireless debugging page before reporting success.

## 1.0.35

- Manual setup step 3 now turns on glasses Wi-Fi before opening Wireless debugging directly.
- The phone waits until the real Wireless debugging page is visible instead of treating a Settings launch request as success.

## 1.0.34

- Manual setup now confirms the Build-number taps only after Developer options are truly enabled on the glasses.
- Developer options and Wireless debugging no longer bounce back to the launcher when step 1 did not complete; the phone explains exactly which step to retry.

## 1.0.33

- Manual pairing now has three explicit controls: six rapid Build-number taps to enable Developer options, direct Developer options, and direct Wireless debugging positioning.
- The six-tap helper targets the displayed build identifier instead of relying on the Settings language and does not automate the rest of the Settings menus.

## 1.0.32

- Direct manual Settings buttons now clear any stale Settings sub-screen before opening, so **Open Developer options** reliably returns to the main developer screen and **Show Wireless debugging** reliably positions the Wireless Debugging row even when Settings was already open.

## 1.0.31

- Manual setup is now always available from onboarding while the glasses app is installed but setup is incomplete, even when the failing transport never delivers a diagnostic.
- The manual wizard no longer drives the glasses Settings menus automatically. It provides separate **Open Developer options** and **Show Wireless debugging** buttons; the latter opens the public Developer options screen already positioned with Wireless Debugging visible for the wearer to select.

## 1.0.30

- Automatic glasses setup now has a clear recovery path: after the initial secure-transport attempt and two internal retries fail, the phone surfaces a guided **Manual setup** action.
- The manual wizard opens the required Wireless Debugging pairing screen on compatible glasses, waits for their acknowledgement, and guides the user through the remaining values without storing the six-digit code.

## 1.0.29

- Failed automatic setup now offers a guided phone-side pairing fallback that opens the required glasses settings itself. The phone waits for a glasses acknowledgement and asks for an app update instead of showing a pairing form when the glasses build is too old.
- Developer mode on the phone now exposes the manual glasses setup wizard for support and testing.

## 1.0.28

- First-run glasses setup is much more resilient: if the secure channel drops mid-arm (including during the planned adbd restart), Nexus reconnects and resumes instead of failing with a support code.

## 1.0.27

- Setup failures on the glasses now show a short support code on the retry card, so a photo of the lens is enough to diagnose what went wrong.

## 1.0.26

- Glasses app updates now ask you to turn on phone Wi-Fi before starting instead of failing during delivery.
- First-run glasses setup now waits for the glasses to join a Wi-Fi network and explains how to recover when none is connected.

## 1.0.25

- The phone now surfaces the glasses' AI-assist button presses and wearing status to plugins, opening the door to features that react to them.

## 1.0.24

- The phone now checks for app and plugin updates on its own, even if you never open Rokid Nexus — you'll get a notification the moment one is available.

## 1.0.23

- Lens opens noticeably faster when the phone's Wi-Fi is off: the glasses no longer set up a Wi-Fi Direct group they were just going to discard, connecting straight to the phone's hotspot instead.

## 1.0.22

- Lens now works even when the phone's Wi-Fi is off: the phone hosts the camera link itself, brings the glasses onto it automatically, and no setting has to be toggled by hand.
- Faster Lens connection when the phone's Wi-Fi is off — the glasses join on the first try instead of retrying.
- Long lyric lines no longer lose their last words; the text shrinks to fit instead of clipping.
- Lens is steadier in longer sessions: it recovers after a plugin update mid-session, handles camera rotation more gracefully, keeps its adaptive text layout in more cases, and reconfigures its video decoder without a brief stutter.

## 1.0.7

- The "Set up your glasses" step can open the Nexus app on the lens directly, so the wearer never hunts through the glasses launcher.

## 1.0.6

- Fix a launch crash in 1.0.5: the install Wi-Fi check needed the ACCESS_WIFI_STATE permission.

## 1.0.5

- The glasses install step now checks that phone Wi-Fi is on first — the APK travels over a direct Wi-Fi link — and offers to turn it on instead of failing with an opaque error.
- On the glasses, enabling the accessibility service flows straight into the secure self-arm; no second tap needed.

## 1.0.4

- Split the glasses onboarding into an install-only card and a dedicated "Set up your glasses" card that owns the How it works guide; drop the Manual download link.
- The glasses report their self-arm setup state to the phone, so the setup step completes exactly when the launcher appears on the lens.

## 1.0.3

- Fix onboarding steps hiding their main button whenever a secondary action was shown — the automated "Install Nexus" glasses install and the notifications "Allow" were invisible.
- Drop the redundant Skip button from the notifications step; denying the system dialog already moves the setup along.

## 1.0.2

- Fix a first-launch crash: the hub no longer starts its foreground service before the Bluetooth permission is granted, and it starts automatically once the permission arrives.
- Ask for each permission from its own onboarding step — Bluetooth, notifications (skippable), and app installs — instead of prompting cold at launch.
- Only mark the first-plugin onboarding step done once a plugin is approved.

## 1.0.1

- Fix the self-arm watchdog script line endings so the secure bootstrap installs a working watchdog.
- Return to the Nexus HUD automatically after the accessibility service is enabled in Settings.
- Slim the glasses launcher, scroll it AR-clean, and show plugin icons.
- Add a phone-side "How it works" walkthrough of the on-glasses setup.
- Report the glasses app version to the phone and offer glasses updates from there.

## 1.0.0

- First public signed release of the Rokid Nexus phone and glasses apps.
- Provides the headless plugin platform, including the Store and developer mode.
- Supports camera and Lens capabilities plus feeds, transit, lyrics, and media plugins.
