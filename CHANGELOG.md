# Changelog

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
