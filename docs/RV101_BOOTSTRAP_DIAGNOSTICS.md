# RV101 Global bootstrap diagnostics

Status: **hardware compatibility gate pending**. Source support is not evidence of operation on an RV101 Global.

## Baseline build record

The repository requires the checked-in Gradle 9.5.1 wrapper, JDK 17, a configured Android SDK with platform 36/build tools, and the real `CxrGlobal` dependency. JDK 17.0.2 and Gradle 9.5.1 are available. The Android SDK is not installed or exposed through `ANDROID_HOME`/`ANDROID_SDK_ROOT`, and downloading the official command-line tools is rejected by the environment proxy with HTTP 403. The observed Gradle failure is therefore `SDK location not found`; repository policy forbids creating a private SDK or machine-specific `local.properties` as a workaround.

`../CxrGlobal` is **optional**, not a baseline prerequisite. `phone-hub` declares the real Maven coordinate `com.example.cxrglobal:lib:0.2.0`; the sibling composite in `settings.gradle.kts` substitutes it only when that directory exists. Maven resolution must be attempted first after the Android SDK is configured. Only an observed dependency-resolution error may establish a `CxrGlobal` blocker; no fake or stub is permitted.

Expected unchanged artifacts are:

| Module | Package | Version | SDK |
|---|---|---|---|
| `:phone-hub` | `com.anezium.rokidbus.phone` | 1.4.3 (10403) | min 30, target 36 |
| `:glasses-hub` | `com.anezium.rokidbus.glasses` | 1.4.3 (10403) | min 31, target 32 |
| `:plugin-assistant` | `com.anezium.rokidbus.plugin.assistant` | 1.4.2 (9) | min 30, target 36 |

No APK or hash is reported until a build actually completes.

Commands attempted with `JAVA_HOME` and `PATH` pointing to JDK 17.0.2 were:

```sh
./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug \
  :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug \
  :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug
./gradlew :ink-engine:test --no-daemon
```

The Android command stopped during task dependency calculation with `SDK location not found`. The
pure Kotlin test reached dependency resolution, but every declared remote repository returned HTTP
403 for ordinary Kotlin/JSON artifacts. Consequently no Android task, unit test, APK metadata, APK
hash, or signing-certificate hash can be reported as completed. `CxrGlobal` resolution was not
reached and is not listed as a blocker.

## Bootstrap flow and diagnostic boundaries

| Stage | File / entry point | Input and output | Failure visibility |
|---|---|---|---|
| Discovery | `phone-hub/.../CxrLAuth.kt`, `isGlobalHiRokidInstalled` | Looks up Global Hi Rokid | Package absent is explicit. Firmware/service compatibility is not established. |
| Authorization | `CxrLAuth.requestAuthorization` / `parseAuthorizationResult` | Opens Rokid authorization Activity; returns token, cancel, or generic failure | Unknown Rokid result codes and result extras collapse to `Authorization failed`; the token must never be logged. |
| CXR-L | `BusHubService.startCxr` and `linkCallback` | Configures one CUSTOMAPP session for the glasses package and calls `connect(token)` | Synchronous result is only `bound: Boolean`; later callbacks expose CXR-L and glass-Bluetooth booleans, not a service error code. |
| Glasses CXR-S | `glasses-hub/.../CxrBusBridge.start` | Subscribes to `rokidbus`, sends a probe | Subscription/send return integers are logged. Status callbacks provide connection state but no installer state. |
| SPP phone | `BusHubService.connectSpp` | Chooses a bonded device and opens the Nexus RFCOMM UUID | Exception type and retry are logged; no Bluetooth address or device identity is logged. |
| SPP glasses | `glasses-hub/.../SppServerManager` | Listens on the Nexus UUID and accepts a client | Listen, accept, frame receipt, and exception are logged. This is distinct from the Rokid-APKs APK-transfer SPP protocol. |
| Package query | `BusHubService.requestGlassesAppQuery` | Calls vendor `appIsInstalled` | The API exposes only `installed: Boolean`; version, signer, source directory, installer owner, flags, ABI, SDK, and PackageInstaller status are unavailable at this boundary. |
| APK resolution | `resolveLatestGlassesAppRelease` | Selects a stable GitHub release asset | HTTP and parse exceptions are reduced to the user-facing release-resolution error but retained in the console log. |
| APK verification | `downloadAndInstallGlassesApp` and `GlassesApkVerificationPolicy` | Verifies release digest and expected package | The diagnostic build now records filename, package, version, bytes, actual SHA-256, and archive signer SHA-256. |
| Upload/install | `downloadAndInstallGlassesApp` | Calls `CXRLink.appUploadAndInstall(path, callback)` | The vendor API is the principal blind spot: dispatch return plus `onInstallAppResult(Boolean)` carry no PackageInstaller status, error code, confirmation Activity result, or firmware-policy reason. |
| Package start | `openGlassesAppOnLens` / `startGlassesSetupOnLens` | Calls vendor `appStart` | Callback is only success/failure. |
| Glasses runtime | `RokidBusAccessibilityService.onServiceConnected` then `GlassesHub.start` | Starts overlays, CXR-S, and SPP server | A package replace may disable Accessibility before this point. The phone cannot inspect that setting through the vendor install API. |
| Handshake | CXR `/hub/probe`, link callbacks, and SPP frames | Establishes CXR control and/or SPP data link | Phone console separates CXR, glass Bluetooth, and Nexus SPP states. |
| Gate 0: install query | Console **RUNTIME** -> `runHardwareGateZero` | Calls vendor `appIsInstalled` | Reports `INSTALLED`, `NOT_INSTALLED`, or `UNKNOWN`; `INSTALLED` is Boolean-only until the glasses runtime reports its own metadata. |
| Gate 0: runtime | `appStart(MainActivity)` followed by glasses `/hub/probe` | Requests package start and waits for evidence emitted by the running hub | `appStart=true` is not proof. Only a received hub probe records `gate0_runtime state=RUNNING`. |
| Gate 1: HUD | Console **HUD TEST** -> `BusHubService.startHardwareGate` | Sends an ordinary card over existing routing | Rejected unless a recent Gate 0 runtime probe exists. The wearer must still confirm that `NEXUS TEST OK` rendered on real glasses. |

### Critical blind spot

The observed sequence “SPP receive complete -> Update -> return to launcher” occurs below the caller-visible Nexus boundary if it is driven by `appUploadAndInstall`. Nexus receives only a dispatch result and a Boolean callback. It cannot distinguish signature conflict, downgrade, installer ownership, missing install permission, System UI cancellation, or a firmware policy denial. A PackageInstaller/PackageManager status must be collected on the glasses with system diagnostics, or the vendor API must expose a richer callback.

## Root-cause test matrix

| ID | Candidate | Current evidence | Counter-evidence | Confidence | Required physical test |
|---|---|---|---|---|---|
| H1 | Signature mismatch | Update UI proves the package name may already exist; a different signer blocks Android upgrades. | No installed-package signer has been captured. | Medium-high | Compare `dumpsys package` signer digests for installed and candidate APK, or uninstall the old package and perform a clean install. |
| H2 | Version policy | Both Nexus hubs use versionCode 10403; repeating the same update can exercise equal-version policy. | Android normally permits equal-version reinstall in some installer modes. | Medium | Record installed versionCode, then test a clean install and a strictly higher diagnostic build separately. |
| H3 | Package ownership | Rokid firmware may associate the existing package with another installer. | Ordinary Android updates are primarily signer-gated, not installer-gated. | Low-medium | Capture `InstallSourceInfo`/`dumpsys package` before the attempt. |
| H4 | CXR installer permission | Transfer completes and confirmation is shown, but commit is not confirmed. | A confirmation UI suggests the request reached Android's installer. | Medium-high | Capture PackageInstaller/PackageManager/System UI logs from button press through return. |
| H5 | Global firmware restriction | Failures are specific to RV101 Global provisioning paths tested so far. | Rokid-APKs can transfer and launch the confirmation UI. | Medium | Clean-install a known-compatible, correctly signed tiny APK through the same installer path. |
| H6 | ABI incompatibility | Not yet measured. | The hubs are predominantly Kotlin/Java; native vendor libraries may still impose ABI constraints. | Low | Record device ABI and `lib/*` entries in the built APK. |
| H7 | API incompatibility | Glasses hub requires API 31 and targets API 32. | RV101 is described as Android 12L/API 32, which should satisfy this. | Low | Record `ro.build.version.sdk` and inspect candidate manifest with `apkanalyzer`. |
| H8 | PackageInstaller/System UI lifecycle | The user returns to the launcher immediately after Update and no success is observed. | No Activity or PackageInstaller status log is available. | High | Video the screen and capture installer/package-manager logs simultaneously. |
| H9 | Broken prior Nexus install | The system offers Update rather than Install. | The installed package may still be healthy and merely differently signed. | Medium | Record package state, uninstall intentionally, reboot, and try one clean install. |
| H10 | Accessibility lifecycle | Android package replacement can remove/disable the accessibility anchor needed by Nexus. | It does not explain failure to commit the package itself. | Medium for runtime, low for install | After a successful install, verify the service switch and `onServiceConnected`; re-enable only through normal Settings UI. |
| H11 | CXR authentication | Nexus automatic/manual pairing failed. | Rokid-APKs SPP APK transfer does not prove Nexus CXR-L/CXR-S authentication. | Medium-high | Confirm authorization success plus CXR-L, CXR-S probe, and HUD gate events independently. |
| H12 | Nexus pairing implementation | Both Nexus pairing modes failed while another project's SPP transfer reached completion. | The two SPP protocols and installer paths may differ. | Medium-high | Install the glasses hub independently, enable Accessibility, then test Nexus CXR and Nexus SPP without invoking provisioning. |

## Diagnostic events

The phone console now emits structured, secret-free events:

- `cxr_link_state`, `glasses_bluetooth_state`, `cxr_session_connect_requested`;
- `spp_state`, `spp_connect_failed`;
- `glasses_package_query` with the explicit `boolean_only` limitation;
- `glasses_apk_download_progress`, `glasses_apk_verified`, `glasses_apk_upload_start`;
- `glasses_install_invoked`, `glasses_install_dispatch_return`, `glasses_install_callback`, `glasses_install_exception`, and delayed `glasses_post_install_query` checks;
- glasses-local `glasses_package_state` with package/version/signer/install-source/platform metadata when the hub process can start;
- Gate 0 `gate0_installation`, `gate0_start_requested`, and `gate0_runtime`;
- Gate 1 `gate1_rejected`, `hardware_gate_waiting`, `hardware_gate_sent`, or `hardware_gate_failed`.

Tokens, Bluetooth addresses, serial numbers, Wi-Fi identifiers, transcripts, and user content are not logged.

## Test procedure for Dmitry

These steps require APKs produced by a successful trusted build; none were produced in the blocked environment described above.

1. Install the provided **Nexus phone debug APK** on the phone. Do not uninstall a working glasses package yet.
2. Open Nexus on the phone and approve Bluetooth/notification permissions when Android asks.
3. Complete **Hi Rokid authorization** from Nexus. Do not send authorization tokens or pairing codes to anyone.
4. Open **Settings -> Console** on the phone.
5. Tap **RUNTIME** once. This is Gate 0; do not tap HUD TEST yet.
6. If the Console reports `gate0_installation state=NOT_INSTALLED` or `UNKNOWN`, stop and share the log. Installer/bootstrap diagnosis remains the primary task.
7. If it reports `INSTALLED`, wait up to ten seconds for `gate0_runtime state=RUNNING evidence=hub_probe`. `gate0_start_requested success=true` alone is not sufficient.
8. Only after `gate0_runtime state=RUNNING`, tap **HUD TEST**. This is Gate 1.
9. Look at the glasses. The required result is a card reading **NEXUS TEST OK**.
10. If it appears, take one photo of the glasses display and tap **SHARE** in Console to send the log.
11. If it does not appear, wait ten seconds, tap **SHARE**, and send the complete console log plus a photo/video of what the glasses show.
12. If an install/update is attempted, start a screen recording before tapping **Update**. Send the recording and the console log. Do not repeatedly retry or uninstall until the log is reviewed.

Gate 0 passes only when installation state is `INSTALLED` and a real glasses-originated hub probe proves the runtime is `RUNNING`. A `hardware_gate_sent` line alone is not a Gate 1 pass; Gate 1 passes only when a person observes **NEXUS TEST OK** on the RV101 Global display.

## Hardware gates and architecture decision

Gate 0 requires confirmed installation plus a running glasses hub. Gate 1 then requires physical confirmation of phone-to-glasses transport and HUD render. Gate 2 remains entirely **NOT TESTED** until Gate 1 passes: touchpad return traffic, STT, Russian STT, TTS, Russian TTS through glasses speakers, AI-button callback, and reconnect recovery.

Preliminary recommendation is **Option A (Nexus Runtime)** only if Gate 1 passes reproducibly after disconnect/reconnect. If independent installation succeeds but Nexus pairing does not, repair the provisioning layer before considering a fork. Choose **Option B (minimal bridge)** only after logs establish a fundamental runtime/provisioning incompatibility; a new glasses APK is otherwise exposed to the same installer restriction.

## License boundary

This repository is Apache-2.0. No Rokid-APKs source has been copied. Its observed SPP behavior is evidence only. Rokid-APKs code must remain outside this derivative until its repository, license, and provenance are available and reviewed.
