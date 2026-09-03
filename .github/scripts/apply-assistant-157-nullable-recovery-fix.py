from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"

text = SERVICE.read_text(encoding="utf-8")
old = '''            if (interruptedMeeting != null) {
                serviceScope.launch(Dispatchers.IO) {
                    if (meetingAudioRecorder.finish(interruptedMeeting.id) != null) {
                        meetingStore.markMeetingAudioRecovered(interruptedMeeting.id)
                    }
                }
            }
'''
new = '''            val interruptedMeetingId = interruptedMeeting?.id
            if (interruptedMeetingId != null) {
                serviceScope.launch(Dispatchers.IO) {
                    if (meetingAudioRecorder.finish(interruptedMeetingId) != null) {
                        meetingStore.markMeetingAudioRecovered(interruptedMeetingId)
                    }
                }
            }
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one nullable recovery block, found {count}")
text = text.replace(old, new, 1)
SERVICE.write_text(text, encoding="utf-8")

verified = SERVICE.read_text(encoding="utf-8")
if "val interruptedMeetingId = interruptedMeeting?.id" not in verified:
    raise SystemExit("Non-null restored meeting id guard missing")
