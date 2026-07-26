# Plan 009 — Speech-to-text capability

Status: slices 1-3 shipped. Slice 4 (continuous mode) is what remains.

## Goal

Give Nexus a platform STT layer on top of the microphone capability shipped in
`8cf844f`/`4d2b5f1`: engines run in the phone hub, consume the glasses-mic PCM
(16 kHz / mono / PCM16 LE from the audio lease), and deliver transcripts to
plugins through a new granted `stt` capability and to hub-owned UX. The engine
stack is ported from Rokid Relay v0.1.16 (`E:\Tools\Rokid\Rokid Relay`), which
field-validated the whole pipeline (glasses mic over CXR-L → VAD → cloud or
Android recognition).

Analysis sources (Codex read-only reports, 2026-07-24):
`E:\Tools\Rokid\tmp\stt-analysis\report-relay.md` (final section) and
`report-nexus.md` (final section).

## Architecture

```text
audio lease (BusHubService, unchanged single-holder rule)
        │ internal holder: hub speech session
        ▼
SpeechSessionManager (hub)          ← generic replacement for Relay VoiceController
  ├─ VoiceActivityDetector (ported) ← local endpointing, providers stay manual-commit
  ├─ SttSession (unified interface) ← buffered + realtime + android behind one API
  │    ├─ OpenAI realtime WS / 4o transcribe REST
  │    ├─ ElevenLabs Scribe realtime WS / REST
  │    ├─ Azure REST
  │    └─ AndroidCxr injected recognizer (later slice)
  ├─ SpeechSettings (engine, model, language) + HubSecretStore (Keystore AES-GCM)
  └─ delivery
       ├─ /stt/* bus paths → granted plugins (targeted binder, pluginId stamped)
       └─ hub-internal consumers (Speech settings test row, future system UX)
```

Key decisions:

- **STT lives in the hub, above the lease, not beside it.** The speech session
  acquires the existing audio lease internally (same arbitration): a plugin
  holding the raw mic makes STT start fail `BUSY` and vice versa. No second
  `CXRLink`, no tee in v1.
- **`stt` is a separate capability from `microphone`.** Transcripts are derived
  sensitive data; a plugin can deserve text without deserving raw PCM. Distinct
  grant in `PluginCapability`/`PathRules`/`PluginPermissionsActivity`.
  Capability-set changes send existing grants back to Pending (by design).
- **Engines and API keys are user-level, owned by the hub.** Plugins never see
  provider choice or credentials. Keys stored via a new hub `HubSecretStore`
  (port of Relay `SttKeystoreAesGcm`, new alias, version-checked envelope);
  do NOT copy glasses-hub `AdbKeyStore` (not Keystore-backed).
- **Payloads carry `version: 1` from day one** (audio v1 forgot this).
- **Session modes designed in from day one:** `utterance` (VAD-endpointed
  one-shot, v1) and `continuous` (segmented live transcription for realtime
  engines — enables live captions; later slice).

## Bus protocol sketch

- `/stt/session/start` `{version:1, mode:"utterance", language?:"auto"}` →
  `/stt/session/start/reply` `{accepted, sessionId, engineKind}` or denial
  `{reason: BUSY|NO_LINK|NO_ENGINE|NOT_GRANTED|START_FAILED}`
- `/stt/session/stop` `{sessionId}` (+ `/reply`)
- hub → holder: `/stt/state` `{sessionId, state: listening|recognizing|processing}`,
  `/stt/partial` `{sessionId, text, seq}`, `/stt/final` `{sessionId, text}`,
  `/stt/session/ended` `{sessionId, reason}`
- Conventions per BUSSPEC: reply = request path + `/reply`, same id; targeted
  delivery only; every hub-generated event stamps the holder `pluginId`.

SDK: `NexusSpeechSession` mirroring `NexusAudioSession` (IDLE/PENDING/ACTIVE,
`start()/stop()`, callbacks `onReady/onState/onPartial/onFinal/onStopped(reason)`,
one session per client, main-thread callbacks, offload heavy work).

## Port map from Relay

Port nearly as-is (fix issues below while porting):
- `SpeechToTextConfig.kt` (engine registry; drop `requiresMicrophonePermission`
  coupling), `TranscriptionLanguageConfig.kt` (incl. Cantonese `yue`/`zh-HK`
  mapping + OpenAI script-steering prompts)
- `CompletedAudioSpeechToText.kt` provider parts (OpenAI/ElevenLabs/Azure REST,
  WAV encoder, multipart writer), `RealtimeSpeechToText.kt` (OpenAI 24 kHz
  resampled WS, ElevenLabs 16 kHz WS, PcmChunker 3200 B/100 ms)
- `VoiceActivityDetector.kt` (pure Kotlin; thresholds avg 350 / peak 2800,
  2.5 s silence, 1.8 s first-byte, 8 s no-speech, 30 s cap)
- `SttKeystoreAesGcm` → `HubSecretStore`

Adapt:
- `CxrBufferedAudioCapture` → thin adapter over the hub's internal lease/PCM tap
  (`forwardAudioFrame` seam); hub stays sole CXR-L owner
- `AndroidCxrSpeechRecognizer` → keep recognizer walk + segmented-session logic,
  replace `CXRLink` + `RelayService` deps with the audio-session abstraction and
  a foreground-policy delegate (hub currently has no RECORD_AUDIO / mic FGS —
  this slice adds them)
- `VoiceController` → do not port; `SpeechSessionManager` keeps only the state
  machine ideas (listening/recognizing/processing, cancel/retry, engine branch
  removed via unified `SttSession`)

Do not port: reply flow, review countdown, `voice_state` HUD protocol, BLE wake,
Relay service idle lifecycle.

## Mandatory fixes while porting (from the Relay report)

1. Multipart field values must be UTF-8 (`writeBytes` corrupts Chinese prompts).
2. OpenAI realtime + Cantonese: prompt-only forcing must not fall back to the
   phone-locale language code.
3. Add a post-commit final-result timeout for realtime sessions (today: stuck in
   `processing` forever if the provider never answers).
4. Make buffered cancellation sticky before network work starts (cancel race
   still uploads audio today).
5. Validate Azure region (`[a-z0-9-]+`) before URL interpolation.
6. Structured error kinds (source-unavailable / no-speech / auth / quota /
   network / timeout / unsupported-language / cancelled / provider) instead of
   strings; per-provider error labels (ElevenLabs errors currently say OpenAI).
7. No transcript text in logs, ever (hub `log()` broadcasts to the Settings UI;
   safety plan allows counts/latency/language/size only).
8. Keep the hard-earned Android recognizer lessons: default target first,
   Auto = no language hint, segmented callbacks implemented, EOF-not-stop for
   segmented targets, one-shot error-11 retry, partial fallback; do not trust
   `checkRecognitionSupport()`.

## Slices

1. **Hub speech core** — port engines + VAD + `HubSecretStore` (with fixes),
   `SpeechSessionManager` over an internal lease holder, Speech settings screen
   (NexusUi patterns: engine/provider/model picker, language grid, key
   management) + a dev test row (hold-to-dictate → transcript shown) for
   on-device e2e validation. Cloud engines only.
2. **Platform capability** — `stt` capability + `/stt/*` paths + hub handlers +
   `NexusSpeechSession` SDK + permissions UI + docs (BUSSPEC, PLUGIN_SDK,
   docs/PLUGINS, plugins/AGENTS, TESTPLAN incl. stale API-v2/frame-rate fixes)
   + grant/route/protocol tests.
3. **Android CXR engine** — injected-recognizer port + hub mic foreground
   policy (RECORD_AUDIO, `FOREGROUND_SERVICE_MICROPHONE`, companion-device
   consideration). Free no-key default once proven. *Shipped: the recognizer is
   fed the glasses PCM through a `ParcelFileDescriptor` pipe behind the same
   `SttSession` interface as the cloud engines, and it is the engine a profile
   lands on until the user picks another. Two lessons worth keeping: the
   `RecognizerIntent` timing extras must be `Int` (a `Long` is silently dropped
   for 0 — Relay has been losing them all along), and the engine must never
   rewrite the user's stored language, only run on auto and lock the grid.*
4. **Continuous mode** — segmented live transcription on realtime engines
   (VAD-driven segment commits, provider reconnect); enables live captions and
   long-form Scribe-style consumers.

## Notes

- 16 kHz mono PCM16 LE is exactly what all Relay engines expect — no format work.
- Realtime OpenAI needs 24 kHz linear resample (ported); ElevenLabs takes 16 kHz.
- PLUGIN_SDK.md frame-rate doc bug (~50 fps vs real ~10 fps) gets fixed in
  slice 2 docs pass.
- First real consumer candidates (VISION.md): Scribe notes plugin, live
  subtitles. Out of scope here; the settings test row is the validation harness.
