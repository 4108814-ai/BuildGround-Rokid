from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-nexus-phone-1423-hub-cpu-hold.py <BuildGround-Rokid-root>")

ROOT = Path(sys.argv[1]).resolve()
HUB = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt"
BUILD = ROOT / "phone-hub/build.gradle.kts"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# This patch intentionally applies after BuildGround Nexus Phone 1.4.22.
hub = HUB.read_text(encoding="utf-8")
for marker in (
    "private var connectedGlassesCpuWakeLock: PowerManager.WakeLock? = null",
    "private fun updateConnectedGlassesCpuHold(connected: Boolean)",
    "updateConnectedGlassesCpuHold(connected)",
    "updateConnectedGlassesCpuHold(false)",
    "override fun onCreate()",
    "override fun onDestroy()",
):
    if marker not in hub:
        raise SystemExit(f"Nexus phone 1.4.22 marker missing before hub CPU-hold patch: {marker}")

# 1.4.22 tied the PARTIAL_WAKE_LOCK to Hi Rokid's onGlassBtConnected callback.
# Physical testing still showed the same failure: with the phone display off,
# audio arrives immediately but Relay visuals remain pending until the display is
# woken to the lock screen. That means the vendor BT callback is not a reliable
# ownership signal for the CPU hold across the screen-off boundary. Make the
# hold follow BusHubService lifetime instead: acquire at service creation, keep
# it across transient CXR/BT state changes, and release only when the hub stops.
replace_once(
    HUB,
    "    private var connectedGlassesCpuWakeLock: PowerManager.WakeLock? = null\n",
    "    private var hubCpuWakeLock: PowerManager.WakeLock? = null\n",
    "hub CPU wake-lock field",
)

replace_once(
    HUB,
    "    private fun updateConnectedGlassesCpuHold(connected: Boolean) {\n"
    "        if (!connected) {\n"
    "            val wakeLock = connectedGlassesCpuWakeLock\n"
    "            connectedGlassesCpuWakeLock = null\n",
    "    private fun updateHubCpuHold(held: Boolean) {\n"
    "        if (!held) {\n"
    "            val wakeLock = hubCpuWakeLock\n"
    "            hubCpuWakeLock = null\n",
    "hub CPU hold helper header",
)

replace_once(
    HUB,
    "                log(\"Connected glasses CPU hold released\")\n",
    "                log(\"Hub CPU hold released\")\n",
    "hub CPU hold release log",
)

replace_once(
    HUB,
    "        if (connectedGlassesCpuWakeLock?.isHeld == true) return\n",
    "        if (hubCpuWakeLock?.isHeld == true) return\n",
    "hub CPU hold existing-lock check",
)

replace_once(
    HUB,
    "            \"rokidbus:connected-glasses\",\n",
    "            \"rokidbus:phone-hub\",\n",
    "hub CPU wake-lock tag",
)

replace_once(
    HUB,
    "            log(\"Connected glasses CPU hold acquire failed cause=${failure.javaClass.simpleName}\")\n",
    "            log(\"Hub CPU hold acquire failed cause=${failure.javaClass.simpleName}\")\n",
    "hub CPU hold acquire failure log",
)

replace_once(
    HUB,
    "            connectedGlassesCpuWakeLock = wakeLock\n"
    "            log(\"Connected glasses CPU hold acquired\")\n",
    "            hubCpuWakeLock = wakeLock\n"
    "            log(\"Hub CPU hold acquired\")\n",
    "hub CPU hold acquired state",
)

replace_once(
    HUB,
    "    override fun onCreate() {\n"
    "        super.onCreate()\n",
    "    override fun onCreate() {\n"
    "        super.onCreate()\n"
    "        updateHubCpuHold(true)\n",
    "acquire hub CPU hold at service creation",
)

replace_once(
    HUB,
    "            glassBtConnected = connected\n"
    "            updateConnectedGlassesCpuHold(connected)\n"
    "            if (!connected) glassesWorn = false\n",
    "            glassBtConnected = connected\n"
    "            if (!connected) glassesWorn = false\n",
    "remove vendor BT callback CPU-hold ownership",
)

replace_once(
    HUB,
    "            cxrConnected = false\n"
    "            glassBtConnected = false\n"
    "            updateConnectedGlassesCpuHold(false)\n"
    "            glassesWorn = false\n",
    "            cxrConnected = false\n"
    "            glassBtConnected = false\n"
    "            glassesWorn = false\n",
    "keep hub CPU hold across failed CXR bind",
)

replace_once(
    HUB,
    "    override fun onDestroy() {\n"
    "        updateConnectedGlassesCpuHold(false)\n",
    "    override fun onDestroy() {\n"
    "        updateHubCpuHold(false)\n",
    "release hub CPU hold on destroy",
)

replace_once(
    BUILD,
    "        versionCode = 10422\n        versionName = \"1.4.22\"\n",
    "        versionCode = 10423\n        versionName = \"1.4.23\"\n",
    "phone 1.4.23 version",
)

final_hub = HUB.read_text(encoding="utf-8")
final_build = BUILD.read_text(encoding="utf-8")
for marker in (
    "private var hubCpuWakeLock: PowerManager.WakeLock? = null",
    "private fun updateHubCpuHold(held: Boolean)",
    '"rokidbus:phone-hub"',
    "updateHubCpuHold(true)",
    "updateHubCpuHold(false)",
    "Hub CPU hold acquired",
    "Hub CPU hold released",
):
    if marker not in final_hub:
        raise SystemExit(f"Nexus phone 1.4.23 marker missing after patch: {marker}")
if "updateConnectedGlassesCpuHold(connected)" in final_hub:
    raise SystemExit("Nexus phone 1.4.23 still ties CPU hold to onGlassBtConnected")
if 'versionCode = 10423' not in final_build or 'versionName = "1.4.23"' not in final_build:
    raise SystemExit("Nexus phone 1.4.23 version bump missing")

print("Nexus Phone 1.4.23 hub-lifetime CPU hold patch applied")
