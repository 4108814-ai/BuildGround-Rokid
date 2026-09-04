from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt"
MANIFEST = ROOT / "phone-hub/src/main/AndroidManifest.xml"
BUS = ROOT / "shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt"

# Reconstruct the exact stable Phone 1.4.14 runtime foundation: 1.4.5 source +
# the already field-proven 1.4.6 Meeting Audio Transport. Do not apply any
# call-state bridge or Glasses runtime patch.
runpy.run_path(str(ROOT / ".github/scripts/apply-meeting-audio-transport.py"), run_name="__main__")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:180]!r}; got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

# The old callback latched glassAiAssistActive before resolving the approved
# assistant, and then returned early on any subsequent START while that latch
# remained true. That made separate wake-word invocations race with plugin
# readiness / STOP delivery and intermittently fall through to native Rokid.
#
# New rule: only suppress duplicate CXR START callbacks inside a short 600 ms
# hardware burst. Every distinct wake-word START is routed independently, so a
# stale boolean can never block the next utterance. If no approved assistant is
# currently resolvable, clear the latch rather than poisoning the next START.
replace_once(
    SERVICE,
    "    private val glassAiAssistActive = AtomicBoolean(false)\n",
    "    private val glassAiAssistActive = AtomicBoolean(false)\n"
    "    private val lastGlassAiAssistStartAtMs = AtomicLong(0L)\n",
)

replace_once(
    SERVICE,
    """        override fun onGlassAiAssistStart() {
            if (!glassAiAssistActive.compareAndSet(false, true)) return
            val assistant = approvedAssistantPrincipal() ?: return
            val gestureId = UUID.randomUUID().toString()
            val alreadyActive = externalPluginController.activeId() == assistant.descriptor.id
""",
    """        override fun onGlassAiAssistStart() {
            val nowMs = SystemClock.elapsedRealtime()
            val previousStartAtMs = lastGlassAiAssistStartAtMs.getAndSet(nowMs)
            if (previousStartAtMs > 0L && nowMs - previousStartAtMs < 600L) {
                log("assistant gesture duplicate START suppressed deltaMs=${nowMs - previousStartAtMs}")
                return
            }
            val assistant = approvedAssistantPrincipal() ?: run {
                glassAiAssistActive.set(false)
                log("assistant gesture ignored reason=NO_APPROVED_ASSISTANT")
                return
            }
            glassAiAssistActive.set(true)
            val gestureId = UUID.randomUUID().toString()
            val alreadyActive = externalPluginController.activeId() == assistant.descriptor.id
""",
)

text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "private val lastGlassAiAssistStartAtMs = AtomicLong(0L)",
    "assistant gesture duplicate START suppressed",
    "assistant gesture ignored reason=NO_APPROVED_ASSISTANT",
    "glassAiAssistActive.set(true)",
):
    if marker not in text:
        raise SystemExit(f"Missing Phone 1.4.16 Assistant routing marker: {marker}")

if "if (!glassAiAssistActive.compareAndSet(false, true)) return" in text:
    raise SystemExit("Old sticky Assistant START gate still present")
if "android.permission.READ_PHONE_STATE" in MANIFEST.read_text(encoding="utf-8"):
    raise SystemExit("READ_PHONE_STATE leaked into Phone 1.4.16")
if "PHONE_CALL_STATE" in BUS.read_text(encoding="utf-8"):
    raise SystemExit("Phone call-state bridge leaked into Phone 1.4.16")

print("Phone 1.4.16 deterministic Assistant routing patch applied")
