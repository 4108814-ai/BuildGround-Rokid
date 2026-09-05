from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-relay-124-control-link-notice.py <Rokid-Nexus-root>")

ROOT = Path(sys.argv[1]).resolve()
CLIENT = ROOT / "bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginClient.kt"
TEST = ROOT / "bus-client/src/test/java/com/anezium/rokidbus/client/plugin/NexusPluginClientTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


client = CLIENT.read_text(encoding="utf-8")
for marker in (
    "val supportsNoticeSurface: Boolean",
    "LinkStateBits.SPP_DATA_UP",
    "private fun noticePreflight()",
):
    if marker not in client:
        raise SystemExit(f"Relay 1.2.4 client baseline marker missing: {marker}")

# Physical BuildGround discriminator:
# - phone/glasses fully awake: Relay visual notice is immediate;
# - phone locked / glasses sleeping: notification audio still arrives, while visual notices wait;
# - wearing/waking the glasses later releases the older visual notices in a burst.
#
# Relay's SDK gate was stricter than the phone hub that actually transports notices.
# NexusPluginClient declared a notice reachable only when SPP_DATA_UP was present, so Relay kept
# the notice in its replay/FIFO queue and waited for onLinkState instead of calling showNotice.
# BusHubService accepts a notice when either CXR_CONTROL_UP or SPP_DATA_UP is available, and its
# sendRemote path can carry a text notice over either control plane. Match the SDK preflight to
# that actual hub contract. Image notices remain SPP-only through supportsImageSurface.
replace_once(
    CLIENT,
    "    val supportsNoticeSurface: Boolean\n"
    "        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&\n"
    "            hubCapabilities and BusCapabilityBits.NOTICE_SURFACE != 0\n",
    "    val supportsNoticeSurface: Boolean\n"
    "        get() = currentLinkState and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0 &&\n"
    "            hubCapabilities and BusCapabilityBits.NOTICE_SURFACE != 0\n",
    "notice control-link availability",
)

anchor = '''    @Test\n    fun `an older hub that cannot answer leaves the registration message in charge`() {\n'''
insert = '''    @Test\n    fun `text notice is available on CXR control even when SPP is asleep`() {\n        val (client, transport, _) = fixture()\n        transport.featureBits = BusCapabilityBits.NOTICE_SURFACE\n        transport.listener.onMessage(\n            BusPaths.PLUGIN_REGISTRATION,\n            "notice-cxr-registration",\n            payload()\n                .put("result", PluginRegistrationResult.APPROVED)\n                .put("capabilities", "surfaces"),\n        )\n        transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)\n\n        assertTrue(client.supportsNoticeSurface)\n        assertEquals(\n            NexusSdkResult.SENT,\n            client.showNotice(NexusNotice(title = "LOCKED PHONE NOTICE")),\n        )\n        assertEquals(BusPaths.NOTICE_SHOW, transport.sends.single().first)\n    }\n\n    @Test\n    fun `notice availability is false only when both control links are down`() {\n        val (client, transport, _) = fixture()\n        transport.featureBits = BusCapabilityBits.NOTICE_SURFACE\n        transport.listener.onMessage(\n            BusPaths.PLUGIN_REGISTRATION,\n            "notice-no-link-registration",\n            payload()\n                .put("result", PluginRegistrationResult.APPROVED)\n                .put("capabilities", "surfaces"),\n        )\n        transport.listener.onLinkState(0)\n\n        assertFalse(client.supportsNoticeSurface)\n        assertEquals(\n            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,\n            client.showNotice(NexusNotice(title = "NO LINK")),\n        )\n    }\n\n'''
replace_once(TEST, anchor, insert + anchor, "control-link notice regression tests")

final_client = CLIENT.read_text(encoding="utf-8")
final_test = TEST.read_text(encoding="utf-8")
for marker in (
    "LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP",
    "text notice is available on CXR control even when SPP is asleep",
    "notice availability is false only when both control links are down",
):
    if marker not in final_client and marker not in final_test:
        raise SystemExit(f"Relay 1.2.4 marker missing after patch: {marker}")

print("Relay 1.2.4 control-link notice patch applied")
