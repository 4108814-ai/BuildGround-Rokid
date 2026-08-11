# Fix: manual/auto glasses pairing fails with connectPort=0 → mDNS fallback dies

## Problem (confirmed root cause, do NOT re-derive)

Real device report (Samsung Fold 6): pairing to the glasses succeeds, then setup
fails at the CONNECTING stage with:

    IOException: No matching Wireless Debugging connect mDNS service found

Chain of causation, already traced in the code:

1. The glasses read their own Wireless Debugging *connect* port with a single,
   no-wait call to `SelfArmWirelessAdbController.readWirelessPort()`:
   - `SelfArmWirelessDebuggingAutomator.kt:701` (`handleWirelessDebuggingPage`)
   - `SelfArmWirelessDebuggingAutomator.kt:779` (`readPairingDialog`)
   - `GlassesHub.kt:867` — `wirelessConnectPort()` inside the manual-nav reply
2. At that instant adbd has not yet published `service.adb.tls.port`, so the read
   returns `0`. `wirelessConnectPort()` returns null → `putOpt("connectPort", null)`
   writes nothing → the phone keeps `reportedConnectPort = 0`.
3. Phone side, `GlassesManualPairingEngine.submit` (`GlassesManualPairingEngine.kt:240-245`):
   `known = reportedConnectPort` is 0 → it takes the mDNS fallback branch
   `backend.discoverConnectEndpoint(cleanHost)`.
4. `AdbMdnsPairingResolver.resolveConnectEndpoint` → the Fold 6 / its router does
   not forward multicast → no service found → IOException →
   "Paired, but the phone could not open the glasses shell."

The AUTO (local self-pairing) path dies at the same cause: local self-pairing
requires `connectPort > 0` (`SelfArmWirelessDebuggingAutomator.kt:852`); with 0 it
never starts and falls back to the same phone/mDNS route.

The intent in the existing comments is already "take the glasses' word for the port,
remove the network (mDNS) from the equation" — but the port is read too early, so it
is 0 and the intent is defeated.

## Goal

Make manual AND phone-assisted glasses pairing succeed on networks where mDNS
multicast is blocked, by reliably obtaining the glasses connect port instead of
falling back to mDNS. mDNS must become a last resort, never the default when the
glasses can report the port.

## What to change

### A. Glasses: obtain the connect port reliably, and re-report it after it appears

There is already a polling helper: `SelfArmWirelessAdbController.waitForWirelessPort(timeoutMs: Int? no — Long)`
at `SelfArmWirelessAdbController.kt:105` (`fun waitForWirelessPort(timeoutMs: Long): Int`,
polls readWirelessPort every 150 ms until deadline).

1. In `GlassesHub.wirelessConnectPort()` (`GlassesHub.kt:880`) and/or at the manual-nav
   reply site (`GlassesHub.kt:867`), do not send a single instantaneous read. Give the
   port a short bounded chance to appear. IMPORTANT: this call may run on a bus/IPC
   thread — do NOT block it for long. Use a small budget (e.g. ~1500-2500 ms max) and
   never block the main/binder thread indefinitely. If still 0, send absent as today.
2. The connect port often only becomes readable AFTER the wireless-debug daemon fully
   comes up (post-toggle, sometimes post-pair). Add a mechanism so that once the port
   becomes known on the glasses (it is polled in the pairing-dialog loop anyway), the
   glasses PUSH the connect port to the phone over the bus so a phone that started with
   0 can learn it. Reuse the existing bus paths — the phone already ingests
   `connectPort` from `GLASSES_SELFARM_MANUAL_REPLY` (`BusHubService.kt:4744`
   → `manualPairingEngine.onGlassesConnectPort(...)`). Prefer sending an extra reply /
   status envelope carrying the now-known `connectPort` rather than inventing a whole
   new contract, unless a new lightweight path is clearly cleaner.

### B. Phone: do not hard-depend on mDNS

In `GlassesManualPairingEngine.submit` / `AndroidGlassesManualPairingBackend`
(`GlassesManualPairingBackend.kt:27` and `GlassesManualPairingEngine.kt:240`):

1. When `reportedConnectPort` is 0 at CONNECTING time, the current behavior goes
   straight to mDNS and fails hard on multicast-blocked networks. Make the engine
   tolerate a late-arriving port: if the port is still 0, wait a short bounded window
   for `onGlassesConnectPort(...)` to deliver it (the glasses push from part A) BEFORE
   falling back to mDNS. Only if no port arrives in that window should mDNS be tried.
2. Keep mDNS as the final fallback (do not delete it) — some setups have no bus-reported
   port and mDNS is the only option there.

## Hard constraints (MUST NOT)

- MUST NOT block the binder/IPC/main thread for a long time. All port waits are
  bounded (single-digit seconds max) and happen off the UI/binder thread where the
  existing pairing work already runs (the engine already uses a worker executor and a
  timeout scheduler — reuse them; see `GlassesManualPairingEngine.create`).
- MUST NOT store or log the pairing code, IP literals, or full ports beyond what the
  existing redaction (`ManualPairingSupportDiagnostic`, `redactedHost`) already allows.
- MUST NOT change the wire contract in `SetupPairingOfferContract` /
  `WirelessAdbContract` in a way that breaks existing tests; if you extend a payload,
  keep it backward-compatible (optional field).
- MUST NOT remove the mDNS resolver or the multicast-lock handling.
- MUST NOT regress the "stale ack must not re-plant an obsolete port" protection at
  `BusHubService.kt:4741-4744` — a pushed port must correlate to the live attempt.
- Keep the existing behavior when the port IS already known (fast path unchanged).

## Acceptance criteria

1. On a network with multicast blocked, if the glasses can read their connect port
   (immediately or shortly after pairing), the phone connects using that port and
   NEVER reaches the mDNS fallback. New/updated unit tests prove: given a glasses-pushed
   connectPort arriving after PAIRING, `GlassesManualPairingEngine` connects to that
   port without calling `discoverConnectEndpoint`.
2. If `readWirelessPort()` returns 0 on the first read but becomes non-zero within the
   bounded wait, the glasses report the real port (test the wait/poll path with a fake
   controller).
3. mDNS is still used when no port is ever reported (existing fallback test still green).
4. All existing tests still pass: `phone-hub` (`GlassesManualPairingEngineTest`,
   `PhoneAssistedSetupPolicyTest`, `SetupPairingOfferContractTest`, `WirelessAdbContractTest`)
   and `glasses-hub` self-arm tests. Add tests for the new behavior.
5. No new lint errors: run `:phone-hub:lintDebug` and the glasses-hub lint if present.

## Build / verify commands (Windows, from repo root)

    .\gradlew.bat :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest
    .\gradlew.bat :phone-hub:lintDebug

Report: exact files changed, the new bus flow for the pushed port (one sentence),
which tests you added, and the test/lint output tails.
