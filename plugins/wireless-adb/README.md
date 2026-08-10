# Wireless ADB

Wireless ADB is a headless Rokid Nexus phone plugin that enables Android's real
wireless debugging transport on the glasses and creates a temporary pairing
command for a trusted computer on the same LAN.

It does not automate Settings and does not use Accessibility. The plugin sends
typed requests through Nexus; the phone hub verifies the signer-bound
`wireless_debugging` grant and stamps the plugin identity, then the glasses hub
performs only the fixed system operations required to trust the current Wi-Fi
network, start pairing, cancel pairing, or disable ADB.

## Use

1. Install the plugin and approve **Wireless debugging** in Nexus plugin access.
2. Open the plugin settings and tap **Enable & pair computer**.
3. Run the displayed `adb pair` command, then the displayed `adb connect`
   command, from a computer on the same LAN.
4. Use **Disable wireless debugging** when LAN access is no longer needed.

The six-digit pairing code is kept only in process memory and expires after two
minutes. Wireless debugging itself remains enabled until it is explicitly
disabled.

The privileged Binder operations are intentionally limited to the validated
Rokid Android 12L/API 32 firmware. Other API levels fail closed until their
transaction contract has been verified.

## Build

```powershell
.\gradlew.bat :plugin-wireless-adb:testDebugUnitTest :plugin-wireless-adb:assembleDebug -PskipCxrGlobal=true
```
