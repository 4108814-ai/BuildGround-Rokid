from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-nexus-phone-1421-notice-spp.py <BuildGround-Rokid-root>")

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
    "val bytes = FrameProtocol.toJsonBytes(envelope)",
    "if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && isCxrUp())",
):
    if marker not in hub:
        raise SystemExit(f"Nexus phone baseline marker missing: {marker}")

# Preserve the 1.4.20 SPP recovery behavior. The release tags point at the
# ungenerated source branch; BuildGround release workflows apply the transport
# patches at build time, so every forward release must carry the prior patch.
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

# Physical lock-state discriminator on the BuildGround phone:
#   phone awake/unlocked -> Relay visual notice reaches the glasses reliably;
#   phone locked        -> audio still arrives, while the visual notice does not.
#
# sendRemote currently prefers CXR for every small control packet. CXR's
# sendCustomCmd return value only confirms that the vendor API accepted the
# command locally; it is not an end-to-end receipt from the glasses. That lets a
# locked-phone drop be recorded as a successful CXR send and prevents the
# existing SPP fallback from running.
#
# Notices are tiny, latency-sensitive state messages and the hub already owns a
# permanent RFCOMM/SPP data plane. Prefer that explicit socket for notice
# show/update/hide whenever it is connected. If the SPP write fails, writeSpp()
# closes the broken socket and the existing CXR path remains the immediate
# fallback. Other control traffic keeps its existing CXR-first policy.
replace_once(
    HUB,
    "        val bytes = FrameProtocol.toJsonBytes(envelope)\n"
    "        if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && isCxrUp()) {\n",
    "        val bytes = FrameProtocol.toJsonBytes(envelope)\n"
    "        if (isNoticePath(envelope.path) && output != null) {\n"
    "            if (writeSpp(envelope)) {\n"
    "                recordRemoteTransport(envelope, PluginBusJournal.Verdict.OK, \"SPP_NOTICE\")\n"
    "                return null\n"
    "            }\n"
    "        }\n"
    "        if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && isCxrUp()) {\n",
    "notice SPP-first transport",
)

# BuildGround phone release source still carries the historical 1.4.5 baseline;
# release workflows forward-version the generated APK without rewriting that
# baseline commit.
replace_once(
    BUILD,
    "        versionCode = 10405\n        versionName = \"1.4.5\"\n",
    "        versionCode = 10421\n        versionName = \"1.4.21\"\n",
    "phone 1.4.21 version",
)

final_hub = HUB.read_text(encoding="utf-8")
for marker in (
    "private val sppRetryWake = java.util.concurrent.Semaphore(0)",
    "sppRetryWake.tryAcquire(delayMs, TimeUnit.MILLISECONDS)",
    "if (glassBtConnected) 2_000L else 30_000L",
    "wakeSppRetry(\"glass_bt_up\")",
    "if (isNoticePath(envelope.path) && output != null)",
    "PluginBusJournal.Verdict.OK, \"SPP_NOTICE\"",
):
    if marker not in final_hub:
        raise SystemExit(f"Nexus phone 1.4.21 marker missing after patch: {marker}")

final_build = BUILD.read_text(encoding="utf-8")
if 'versionCode = 10421' not in final_build or 'versionName = "1.4.21"' not in final_build:
    raise SystemExit("Nexus phone 1.4.21 version bump missing")

print("Nexus phone 1.4.21 notice SPP-first patch applied")
