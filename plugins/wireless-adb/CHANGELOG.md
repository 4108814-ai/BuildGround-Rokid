# Changelog

## 1.0.1

- **Enable & pair now restores Wi-Fi when needed.** If the glasses' Wi-Fi radio
  is off, Nexus turns it on and waits for a saved network before enabling ADB.
- **Disable remains scoped to ADB.** It closes wireless debugging without
  disconnecting the glasses from their normal Wi-Fi network.

## 1.0.0

- Enable wireless debugging on the glasses without cable or Settings automation.
- Create a two-minute ADB pairing code and copyable `adb pair` / `adb connect` commands.
- Cancel an active pairing window or disable the wireless debugging transport.
- Keep periodic status checks from redrawing or disabling the settings controls.
- Require signed Nexus phone and glasses hubs 1.3.0 or newer and validated Rokid
  Android 12L/API 32 firmware.
- Mark copied commands as sensitive and prevent screenshots while a pairing code is visible.
