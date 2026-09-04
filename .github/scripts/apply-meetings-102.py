from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / ".github/scripts/apply-meetings-101.py"
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"

# Reuse the proven 1.0.1 generator exactly, then add the one compatibility fix needed
# for RV101 temple taps. On the clean 1.4.17 Glasses runtime, an unclassified single
# temple contact is deliberately forwarded to the active surface as KEYCODE_NOTIFICATION
# (83) after the triple-tap window expires. Meetings 1.0.1 only accepted ENTER/CENTER,
# so that legitimate fallback tap was ignored.
runpy.run_path(str(BASE), run_name="__main__")

text = SERVICE.read_text(encoding="utf-8")
old = '''            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {'''
new = '''            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_NOTIFICATION,
            -> {'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected one Meetings confirm-key block, found {count}")
text = text.replace(old, new, 1)
SERVICE.write_text(text, encoding="utf-8")

text = SERVICE.read_text(encoding="utf-8")
if text.count("KeyEvent.KEYCODE_NOTIFICATION") != 1:
    raise SystemExit("Meetings fallback temple-tap confirm marker missing or duplicated")
for marker in (
    "MeetingsControlPolicy.action(meetingRecorder.active)",
    "Нажмите: начать совещание",
    "Нажмите: завершить совещание",
):
    if marker not in text:
        raise SystemExit(f"Meetings 1.0.1 behavior marker lost: {marker}")
