# Wireless ADB

Wireless ADB is a headless Rokid Nexus phone plugin that enables Android's real
wireless debugging transport on the glasses and creates a temporary pairing
command for a trusted computer on the same LAN.

It does not automate Settings and does not use Accessibility. The plugin sends
typed requests through Nexus; the phone hub verifies the signer-bound
`wireless_debugging` grant and stamps the plugin identity, then the glasses hub
performs only the fixed system operations required to trust the current Wi-Fi
network, start pairing, cancel pairing, or disable ADB.

## Requirements

- Rokid Nexus phone and glasses hubs 1.3.0 or newer, installed through their normal
  signed upgrade path.
- Glasses on Wi-Fi, with Nexus self-arm/developer access already complete, and a
  computer on the same LAN.
- The validated Rokid Android 12L/API 32 firmware. Other API levels fail closed.

## Use

1. Install the plugin and approve **Wireless debugging** in Nexus plugin access.
2. Open the plugin settings and tap **Enable & pair computer**.
3. Run the displayed `adb pair` command, then the displayed `adb connect`
   command, from a computer on the same LAN.
4. Use **Disable wireless debugging** when LAN access is no longer needed.

Nexus never logs or persists the six-digit pairing code, and the code expires after
two minutes. **Copy command** explicitly places the command, including that code, on
the Android clipboard; clear it after use if the phone syncs its clipboard to other
devices. The settings window blocks screenshots and screen recording while the code
is visible via Android's `FLAG_SECURE`. Wireless debugging itself remains enabled
until it is explicitly disabled.

The pairing code is never restored after a hub restart. Only the non-secret service
name and expiry deadline are kept so the temporary pairing service can still be
closed. If its normal stop command fails, Nexus disables wireless debugging; if
that also fails, it retains the active state and retries instead of claiming the
window is closed.

## Build

```powershell
.\gradlew.bat :plugin-wireless-adb:testDebugUnitTest :plugin-wireless-adb:assembleDebug -PskipCxrGlobal=true
```
