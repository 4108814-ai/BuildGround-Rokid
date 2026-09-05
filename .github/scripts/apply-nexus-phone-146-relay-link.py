from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-nexus-phone-146-relay-link.py <Rokid-Nexus-root>")

ROOT = Path(sys.argv[1]).resolve()
HUB = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt"
BUILD = ROOT / "phone-hub/build.gradle.kts"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


hub = HUB.read_text(encoding="utf-8")
for marker in (
    "private val sppLoopStarted = AtomicBoolean(false)",
    "private fun connectSpp()",
    "backoffMs = (backoffMs * 2).coerceAtMost(30_000L)",
    "override fun onGlassBtConnected(connected: Boolean)",
):
    if marker not in hub:
        raise SystemExit(f"Nexus phone 1.4.5 SPP marker missing: {marker}")

# The phone hub already has one permanent SPP connection thread. The defect for
# a notification fast path is its recovery latency: a failed RFCOMM connection
# exponentially backs off to 30 seconds even when CXR reports the glasses are
# physically back on Bluetooth. Keep the battery-friendly 30 s ceiling while
# glasses are absent, but wake that sleeping loop immediately when the Rokid BT
# callback says the glasses returned. While BT is known up, cap retry at 2 s.
replace_once(
    HUB,
    "    private val sppLoopStarted = AtomicBoolean(false)\n",
    "    private val sppLoopStarted = AtomicBoolean(false)\n"
    "    private val sppRetryWake = java.util.concurrent.Semaphore(0)\n",
    "SPP retry wake field",
)

replace_once(
    HUB,
    "    @SuppressLint(\"MissingPermission\")\n"
    "    private fun connectSpp() {\n",
    "    private fun waitForSppRetry(delayMs: Long) {\n"
    "        runCatching { sppRetryWake.tryAcquire(delayMs, TimeUnit.MILLISECONDS) }\n"
    "        sppRetryWake.drainPermits()\n"
    "    }\n\n"
    "    private fun wakeSppRetry(reason: String) {\n"
    "        Log.i(TAG, \"SPP retry wake reason=$reason\")\n"
    "        sppRetryWake.release()\n"
    "    }\n\n"
    "    @SuppressLint(\"MissingPermission\")\n"
    "    private fun connectSpp() {\n",
    "SPP wake helpers",
)

replace_once(
    HUB,
    "                sleepQuietly(backoffMs)\n"
    "                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)\n",
    "                waitForSppRetry(backoffMs)\n"
    "                val retryCeilingMs = if (glassBtConnected) 2_000L else 30_000L\n"
    "                backoffMs = (backoffMs * 2).coerceAtMost(retryCeilingMs)\n",
    "SPP adaptive retry",
)

replace_once(
    HUB,
    "        override fun onGlassBtConnected(connected: Boolean) {\n"
    "            glassBtConnected = connected\n"
    "            if (!connected) glassesWorn = false\n"
    "            log(\"Hi Rokid glass BT connected=$connected\")\n"
    "            notifyLinkState()\n",
    "        override fun onGlassBtConnected(connected: Boolean) {\n"
    "            glassBtConnected = connected\n"
    "            if (!connected) glassesWorn = false\n"
    "            log(\"Hi Rokid glass BT connected=$connected\")\n"
    "            if (connected) wakeSppRetry(\"glass_bt_up\")\n"
    "            notifyLinkState()\n",
    "wake SPP on glass BT up",
)

replace_once(
    BUILD,
    "        versionCode = 10405\n        versionName = \"1.4.5\"\n",
    "        versionCode = 10406\n        versionName = \"1.4.6\"\n",
    "phone 1.4.6 version",
)

final_hub = HUB.read_text(encoding="utf-8")
for marker in (
    "private val sppRetryWake = java.util.concurrent.Semaphore(0)",
    "waitForSppRetry(backoffMs)",
    "if (glassBtConnected) 2_000L else 30_000L",
    "wakeSppRetry(\"glass_bt_up\")",
):
    if marker not in final_hub:
        raise SystemExit(f"Nexus phone 1.4.6 marker missing after patch: {marker}")

final_build = BUILD.read_text(encoding="utf-8")
if 'versionCode = 10406' not in final_build or 'versionName = "1.4.6"' not in final_build:
    raise SystemExit("Nexus phone 1.4.6 version bump missing")

print("Nexus phone 1.4.6 Relay link recovery patch applied")
