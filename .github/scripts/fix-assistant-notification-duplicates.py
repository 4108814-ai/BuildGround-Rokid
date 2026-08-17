from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"

# The final MVP generator currently leaves two notification-listener implementations
# in the generated source tree. AssistantNotificationBridge.kt is the complete one;
# the standalone listener is an older partial implementation and cannot coexist.
legacy_listener = SRC / "AssistantNotificationListenerService.kt"
if legacy_listener.exists():
    legacy_listener.unlink()

# LIST_NOTIFICATIONS_TOOL_NAME is owned by AssistantNotificationBridge.kt. Remove the
# duplicate declaration emitted into AssistantMvpTools.kt while leaving all call sites.
mvp_tools = SRC / "AssistantMvpTools.kt"
text = mvp_tools.read_text(encoding="utf-8")
duplicate = 'internal const val LIST_NOTIFICATIONS_TOOL_NAME = "list_notifications"\n'
count = text.count(duplicate)
if count != 1:
    raise SystemExit(f"Expected exactly one duplicate LIST_NOTIFICATIONS_TOOL_NAME, found {count}")
mvp_tools.write_text(text.replace(duplicate, "", 1), encoding="utf-8")

print("Removed duplicate notification listener and tool-name declaration.")
