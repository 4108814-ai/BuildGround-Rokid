from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-nexus-phone-1422-cpu-hold.py <BuildGround-Rokid-root>")

ROOT = Path(sys.argv[1]).resolve()
HUB = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt"
MANIFEST = ROOT / "phone-hub/src/main/AndroidManifest.xml"
BUILD = ROOT / "phone-hub/build.gradle.kts"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# This patch intentionally applies after BuildGround Nexus Phone 1.4.21.
hub = HUB.read_text(encoding="utf-8")
for marker in (
    "private val sppRetryWake = java.util.concurrent.Semaphore(0)",
    "override fun onGlassBtConnected(connected: Boolean)",
    "private fun waitForSppRetry(delayMs: Long)",
    "if (!bound) {",
    "override fun onDestroy()",
):
    if marker not in hub:
        raise SystemExit(f"Nexus phone 1.4.21 marker missing before CPU-hold patch: {marker}")

# The physical discriminator is specific: while the phone display is off,
# notification audio still reaches the glasses but Relay visual notices wait;
# merely waking the phone to the lock screen flushes the pending visuals.
# Keep the CPU runnable for the lifetime of an actual glasses BT connection.
# PARTIAL_WAKE_LOCK does not turn the phone display on.
replace_once(
    MANIFEST,
    "    <uses-permission android:name=\"android.permission.FOREGROUND_SERVICE\" />\n",
    "    <uses-permission android:name=\"android.permission.FOREGROUND_SERVICE\" />\n"
    "    <uses-permission android:name=\"android.permission.WAKE_LOCK\" />\n",
    "WAKE_LOCK permission",
)

replace_once(
    HUB,
    "import android.os.Process\nimport android.os.SystemClock\n",
    "import android.os.Process\nimport android.os.PowerManager\nimport android.os.SystemClock\n",
    "PowerManager import",
)

replace_once(
    HUB,
    "    private val sppRetryWake = java.util.concurrent.Semaphore(0)\n",
    "    private val sppRetryWake = java.util.concurrent.Semaphore(0)\n"
    "    private var connectedGlassesCpuWakeLock: PowerManager.WakeLock? = null\n",
    "connected glasses wake-lock field",
)

replace_once(
    HUB,
    "    private fun waitForSppRetry(delayMs: Long) {\n",
    "    private fun updateConnectedGlassesCpuHold(connected: Boolean) {\n"
    "        if (!connected) {\n"
    "            val wakeLock = connectedGlassesCpuWakeLock\n"
    "            connectedGlassesCpuWakeLock = null\n"
    "            if (wakeLock?.isHeld == true) {\n"
    "                runCatching { wakeLock.release() }\n"
    "                log(\"Connected glasses CPU hold released\")\n"
    "            }\n"
    "            return\n"
    "        }\n"
    "        if (connectedGlassesCpuWakeLock?.isHeld == true) return\n"
    "        val power = getSystemService(PowerManager::class.java) ?: run {\n"
    "            log(\"Connected glasses CPU hold unavailable: no PowerManager\")\n"
    "            return\n"
    "        }\n"
    "        val wakeLock = power.newWakeLock(\n"
    "            PowerManager.PARTIAL_WAKE_LOCK,\n"
    "            \"rokidbus:connected-glasses\",\n"
    "        ).apply { setReferenceCounted(false) }\n"
    "        val acquired = runCatching {\n"
    "            wakeLock.acquire()\n"
    "            true\n"
    "        }.getOrElse { failure ->\n"
    "            log(\"Connected glasses CPU hold acquire failed cause=${failure.javaClass.simpleName}\")\n"
    "            false\n"
    "        }\n"
    "        if (acquired) {\n"
    "            connectedGlassesCpuWakeLock = wakeLock\n"
    "            log(\"Connected glasses CPU hold acquired\")\n"
    "        }\n"
    "    }\n\n"
    "    private fun waitForSppRetry(delayMs: Long) {\n",
    "CPU-hold helper",
)

replace_once(
    HUB,
    "        override fun onGlassBtConnected(connected: Boolean) {\n"
    "            glassBtConnected = connected\n"
    "            if (!connected) glassesWorn = false\n",
    "        override fun onGlassBtConnected(connected: Boolean) {\n"
    "            glassBtConnected = connected\n"
    "            updateConnectedGlassesCpuHold(connected)\n"
    "            if (!connected) glassesWorn = false\n",
    "CPU hold follows glasses BT callback",
)

replace_once(
    HUB,
    "        if (!bound) {\n"
    "            cxrConnected = false\n"
    "            glassBtConnected = false\n"
    "            glassesWorn = false\n",
    "        if (!bound) {\n"
    "            cxrConnected = false\n"
    "            glassBtConnected = false\n"
    "            updateConnectedGlassesCpuHold(false)\n"
    "            glassesWorn = false\n",
    "release CPU hold on failed CXR bind",
)

replace_once(
    HUB,
    "    override fun onDestroy() {\n"
    "        stopPeriodicUpdateChecks()\n",
    "    override fun onDestroy() {\n"
    "        updateConnectedGlassesCpuHold(false)\n"
    "        stopPeriodicUpdateChecks()\n",
    "release CPU hold on hub destroy",
)

replace_once(
    BUILD,
    "        versionCode = 10421\n        versionName = \"1.4.21\"\n",
    "        versionCode = 10422\n        versionName = \"1.4.22\"\n",
    "phone 1.4.22 version",
)

final_hub = HUB.read_text(encoding="utf-8")
final_manifest = MANIFEST.read_text(encoding="utf-8")
final_build = BUILD.read_text(encoding="utf-8")
for marker in (
    "import android.os.PowerManager",
    "private var connectedGlassesCpuWakeLock: PowerManager.WakeLock? = null",
    "PowerManager.PARTIAL_WAKE_LOCK",
    '"rokidbus:connected-glasses"',
    "updateConnectedGlassesCpuHold(connected)",
    "updateConnectedGlassesCpuHold(false)",
    "Connected glasses CPU hold acquired",
):
    if marker not in final_hub:
        raise SystemExit(f"Nexus phone 1.4.22 marker missing after patch: {marker}")
if 'android.permission.WAKE_LOCK' not in final_manifest:
    raise SystemExit("Nexus phone 1.4.22 WAKE_LOCK permission missing")
if 'versionCode = 10422' not in final_build or 'versionName = "1.4.22"' not in final_build:
    raise SystemExit("Nexus phone 1.4.22 version bump missing")

print("Nexus Phone 1.4.22 connected-glasses CPU hold patch applied")
