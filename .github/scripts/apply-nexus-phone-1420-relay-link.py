from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-nexus-phone-1420-relay-link.py <BuildGround-Rokid-root>")

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
        raise SystemExit(f"Nexus phone SPP baseline marker missing: {marker}")

# The hub already owns one permanent RFCOMM/SPP connection thread. Its recovery
# path, however, can sleep for an exponentially backed-off interval of up to
# 30 seconds after a failed/closed SPP link. A Relay notification that arrives
# during that sleep is captured immediately on Android but cannot be rendered on
# the glasses until the data plane returns.
#
# Keep the existing battery-friendly 30-second ceiling while glasses are absent.
# When CXR-L reports the glasses Bluetooth link as present, wake the sleeping SPP
# retry immediately and cap subsequent retries at two seconds until SPP is back.
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
    "        try {\n"
    "            sppRetryWake.tryAcquire(delayMs, TimeUnit.MILLISECONDS)\n"
    "        } catch (_: InterruptedException) {\n"
    "            Thread.currentThread().interrupt()\n"
    "        } finally {\n"
    "            sppRetryWake.drainPermits()\n"
    "        }\n"
    "    }\n\n"
    "    private fun wakeSppRetry(reason: String) {\n"
    "        log(\"SPP retry wake reason=$reason\")\n"
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
    "adaptive SPP retry",
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
    "wake SPP retry when glasses BT returns",
)

# Source still carries the historical 1.4.5 baseline; BuildGround phone releases
# after that have been forward-versioned by release workflows. 1.4.20 is the next
# free forward-only phone version after the installed-version-authority 1.4.19.
replace_once(
    BUILD,
    "        versionCode = 10405\n        versionName = \"1.4.5\"\n",
    "        versionCode = 10420\n        versionName = \"1.4.20\"\n",
    "phone 1.4.20 version",
)

final_hub = HUB.read_text(encoding="utf-8")
for marker in (
    "private val sppRetryWake = java.util.concurrent.Semaphore(0)",
    "sppRetryWake.tryAcquire(delayMs, TimeUnit.MILLISECONDS)",
    "waitForSppRetry(backoffMs)",
    "if (glassBtConnected) 2_000L else 30_000L",
    "wakeSppRetry(\"glass_bt_up\")",
):
    if marker not in final_hub:
        raise SystemExit(f"Nexus phone 1.4.20 marker missing after patch: {marker}")

final_build = BUILD.read_text(encoding="utf-8")
if 'versionCode = 10420' not in final_build or 'versionName = "1.4.20"' not in final_build:
    raise SystemExit("Nexus phone 1.4.20 version bump missing")

print("Nexus phone 1.4.20 Relay link recovery patch applied")
