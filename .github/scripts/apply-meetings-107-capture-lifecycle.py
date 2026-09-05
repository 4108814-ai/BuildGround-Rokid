from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"

text = SERVICE.read_text(encoding="utf-8")

# RV101 showed that Meetings could create archive metadata while losing the raw audio lease:
# Start used delayed re-arm, and a transient Notice/plugin close could suspend meeting audio before
# the lease was safely established. 1.0.7 acquires raw audio synchronously from Start and keeps a
# live current-process meeting across transient UI closes.

old_start = '''        meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        meetingAudioSuspended = false
        scheduleMeetingRearm()
        showMeetingsNotice()
        startMeetingsNoticeTicker()
'''
new_start = '''        meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        meetingAudioSuspended = false
        meetingRearmPending = false
        meetingRearmJob?.cancel()
        meetingRearmJob = null
        startMeetingAudioCapture()
        if (meetingAudioSession == null) {
            Log.w(TAG, "Meeting microphone was not armed synchronously from Start")
            scheduleMeetingAudioRetry()
        }
        showMeetingsNotice()
        startMeetingsNoticeTicker()
'''
if text.count(old_start) != 1:
    raise SystemExit("Meetings 1.0.7 Start marker changed unexpectedly")
text = text.replace(old_start, new_start, 1)

close_pattern = re.compile(
    r'''    override fun onNexusClose\(\) \{\n(?P<body>.*?)\n    \}\n\n    override fun onNexusInput''',
    re.DOTALL,
)
match = close_pattern.search(text)
if match is None:
    raise SystemExit("Meetings 1.0.7 onNexusClose block not found")
body = match.group("body")

# Generated 1.0.6 cleanup layout may vary as older feature patches evolve. The invariant we need is
# simpler and safer: onNexusClose must contain exactly one meeting-audio suspension point, and the
# live-meeting guard must execute immediately before it.
suspend_line = "        suspendMeetingAudioCapture()\n"
if body.count(suspend_line) != 1:
    raise SystemExit(
        f"Meetings 1.0.7 expected one close suspension point, found {body.count(suspend_line)}"
    )
guarded_suspend = '''        if (meetingRecorder.active && meetingAudioOwnedByCurrentProcess && !meetingStopPending) {
            // Transient HUD/Notice close is not a meeting-stop command. Keep the continuous raw
            // audio session, crash-safe spool and foreground anchor alive.
            resetCapture()
            return
        }
        suspendMeetingAudioCapture()
'''
body = body.replace(suspend_line, guarded_suspend, 1)
text = text[:match.start("body")] + body + text[match.end("body"):]

old_reopen = '''        } else if (meetingRecorder.active && meetingAudioOwnedByCurrentProcess) {
            // Duplicate launcher opens and temporary foreground switches re-present the control.
            // If the raw lease was suspended by a plugin close, explicitly re-arm it.
            meetingAudioSuspended = false
            if (meetingsStartedElapsedRealtimeMs == null) {
                meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
            }
            scheduleMeetingRearm()
        }
'''
new_reopen = '''        } else if (meetingRecorder.active && meetingAudioOwnedByCurrentProcess) {
            // Re-present Stop without perturbing a healthy raw lease. If the lease really died,
            // reacquire it immediately while the plugin session is explicitly open again.
            meetingAudioSuspended = false
            if (meetingsStartedElapsedRealtimeMs == null) {
                meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
            }
            if (meetingAudioSession == null) startMeetingAudioCapture()
        }
'''
if text.count(old_reopen) != 1:
    raise SystemExit("Meetings 1.0.7 reopen marker changed unexpectedly")
text = text.replace(old_reopen, new_reopen, 1)

SERVICE.write_text(text, encoding="utf-8")

check = SERVICE.read_text(encoding="utf-8")
for marker in (
    "startMeetingAudioCapture()",
    'Log.w(TAG, "Meeting microphone was not armed synchronously from Start")',
    "meetingRecorder.active && meetingAudioOwnedByCurrentProcess && !meetingStopPending",
    "if (meetingAudioSession == null) startMeetingAudioCapture()",
    "override fun retainNexusAudioOnClose(): Boolean",
):
    if marker not in check:
        raise SystemExit(f"Meetings 1.0.7 capture marker missing: {marker}")

close = close_pattern.search(check)
if close is None:
    raise SystemExit("Meetings 1.0.7 final close block missing")
close_body = close.group("body")
guard_pos = close_body.find("meetingRecorder.active && meetingAudioOwnedByCurrentProcess")
suspend_pos = close_body.find("suspendMeetingAudioCapture()")
if guard_pos < 0 or suspend_pos < 0 or suspend_pos < guard_pos:
    raise SystemExit("Meetings 1.0.7 close lifecycle invariant failed")
