# Rokid AIUI runtime — field findings

Reference notes on Rokid's live AIUI runtime as it behaves on Rokid Glasses, gathered while evaluating it as a rendering target for Nexus (June–August 2026, firmware `SKQ1.240613.001` / Android 12, `assistserver` 0.3.5, Ink runtime `0.15.0-rc-20260716065001`).

**Nexus does not use this runtime.** The evaluation concluded that driving it from a third-party app depends on private, undocumented firmware surfaces and composes poorly with other display owners. Nexus instead reimplements the published AIUI *page format* natively — see [plans/020-ink-surface.md](../plans/020-ink-surface.md). These findings are preserved because they were hard-won, they document real firmware behavior, and they may help other projects (or a future fallback).

## Architecture

AIUI apps ("agents") are `.aix` packages — zip archives of `.ink` single-file components (WXML-like markup, WXSS-like styles, JS `<script setup>`) plus `app.json`/`AGENTS.md` metadata. They run on the glasses inside Rokid's assist server:

- Host process: `com.rokid.os.sprite.assistserver`
- Runtime: native engine (log tags `ink::instance`, `ink_core::storage`, `ink_script::console` — Rust crates), not a WebView.
- Page host: `JsaiActivity` / `InkViewHost`, viewport 480 px wide (bounded mode observed at 480×640 and 480×400).
- Packages extract to `/data/user_de/0/com.rokid.os.sprite.assistserver/cache/jsai/runtime/<Name>-<version>`; per-app storage under `files/jsai/data/<Name>-<version>/storage.json`.

## Launching pages

- Service: `com.rokid.os.sprite.assistserver/com.rokid.os.sprite.jsai.JsaiService`, action `com.rokid.os.sprite.jsai.OPEN_PAGE`, string extra `open_params` = `{"agentId":"<id>"}`.
- Agent resolution goes through `/sdcard/jsai/package/agents_index.json` (world-writable index: `agentId`, `agentName`, `agentDesc`, `permissions`, `nativeVersion`, `fileMd5`, `filePath`, `updatedAt`). Local development works by pushing a `.aix` and merging an entry pointing at its path; the official Hi Rokid app rewrites this index at will, so external entries must be re-merged, never assumed stable.
- Launch by `agentId` is the only reliable parameterization. Extra fields in `open_params` are **not** forwarded to the page: `onLoad(query)` receives `{}` and app-level `onLaunch(options)` receives `undefined` (verified on-device). Direct-path and prompt-carrying variants produce `PARAM_INVALID`.
- Shell-UID launch (`am startservice` over ADB) works. Whether a normal signed APK may call the service was never tested.

## Runtime behaviors and traps

- **Same-version relaunch never re-runs `onLoad`.** While a given `.aix` path is open, re-sending OPEN_PAGE brings the scene forward without reloading. Only a package version bump (new extraction path) forces a fresh load. Stale-cache behavior also keys on the version string, not file content — always bump.
- **Pages pause with the display.** A page launched while the display sleeps loads only after wake; JS (including network calls) runs on resume.
- Cold path measured: extract + load + first paint ≈ 434 ms after resume; extract-to-first-network ≈ 1.2 s warm.
- Scene exclusivity: opening a CXR-L CustomView closes `third_app`/`jsai` scenes. An accessibility overlay owned by another app draws *over* the jsai scene without closing it.

## Networking: the CXR proxy (the headline finding)

AIUI `wx.request` does **not** open sockets on the glasses. The chain is:

```
page wx.request → InkNetworkingCapability → JsaiNetProxyManager
  → CXR "Proxy" channel (Proxy_NetRequest / Proxy_NetResponse)
  → companion phone opens the real socket on its own network stack
```

Verified consequences (all on-device):

- A server bound to the **phone's** loopback (`127.0.0.1`) is reachable from a glasses-side AIUI page. Requests arrive with the page's headers intact (including `Authorization`) and use **absolute-form request targets** (`GET http://127.0.0.1:<port>/path HTTP/1.1`) — servers must normalize.
- This works with **glasses Wi-Fi fully disabled**: the proxy carries HTTP over the Bluetooth/CXR link and the phone's network. Sustained 1 Hz polling ran at exact cadence.
- It works while both the official companion app and a third-party CXR-connected app are alive on the phone.
- A server on the **glasses'** loopback is unreachable (`status=10002`): the connect happens phone-side, where nothing listens. Proxy status `10001` = success, `10002` = connect failure.
- Requests are logged verbosely by the firmware (`NetProxyEngine`, `ProxyCmdHelper` tags), including hosts and byte counts.

Security note: because the socket opens phone-side, any phone app can host a service that glasses-side AIUI pages reach on `127.0.0.1` — and conversely, a loopback server is reachable by every app on the phone, so such a server needs its own authentication. With `open_params` not forwarded, per-session token delivery to a page is impossible; a secret baked into the `.aix` at pack time is the only workable bootstrap.

## Documented API surface (from the official Apache-2.0 repo)

The `jsar-project/AIUI` repository documents the page format and JS APIs: components (`view`, `text`, `image`, `scroll-view`, `chart` with a guaranteed line/area/pie/radar contract, `lottie-view`, A2UI), WXSS subset (flexbox, transitions; keyframes documented unsupported despite sample usage), `wx.request`/EventSource/WebSocket factories (only plain requests verified on this firmware), `SpeechRecognition` (verified working), `LanguageModel` and `speechSynthesis` (host-configured), and the monochrome-green design token spec for these optics. Official samples in `samples/capabilities/` exercise all of it.

## Why Nexus set this path aside

Feasible — the transport was proven end-to-end — but every load-bearing piece (service action, index schema, proxy behavior, scene lifecycle) is private firmware surface that Rokid may change without notice; launch authority for normal APKs is unproven; pages cannot receive launch parameters; and the jsai scene composes with nothing (fullscreen, exclusive, killed by CustomView). Porting the published format to a Nexus-owned native renderer keeps the authoring model and discards every one of those dependencies.
