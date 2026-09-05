from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"

text = SERVICE.read_text(encoding="utf-8")

# Physical RV101 test showed the real 1.0.4-1.0.6 failure: the one-shot Notice action can close the
# plugin session before the delayed meeting rearm acquires the raw CXR audio lease. In addition,
# AssistantPluginService.onNexusClose() still called suspendMeetingAudioCapture(), undoing the base
# class retention hook. Result: a durable session/transcript shell with no WAV and an unstable Stop.
# 1.0.7 starts the raw lease before returning from Start and never suspends that lease on a transient
# plugin close while the explicitly-started current-process meeting remains active.

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
        // The Start action itself is explicit user intent and owns no SpeechSession. Acquire the
        // raw meeting lease now, before the one-shot Notice is allowed to close the plugin session.
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

# Keep ordinary Assistant cleanup, but a live meeting must not call suspendMeetingAudioCapture().
# The base NexusPluginService 1.0.4 retention hook already keeps the raw client lease and foreground
# anchor. This subclass guard is the missing half of that fix.
close_pattern = re.compile(
    r'''    override fun onNexusClose\(\) \{\n(?P<body>.*?)\n    \}\n\n    override fun onNexusInput''',
    re.DOTALL,
)
match = close_pattern.search(text)
if match is None:
    raise SystemExit("Meetings 1.0.7 onNexusClose block not found")
body = match.group("body")
old_cleanup = '''        uiController.onClose()
        meetingRearmJob?.cancel()
        meetingRearmJob = null
        meetingRearmPending = false
        suspendMeetingAudioCapture()
        resetCapture()
        cancelPipeline()'''
new_cleanup = '''        uiController.onClose()
        meetingRearmJob?.cancel()
        meetingRearmJob = null
        meetingRearmPending = false
        if (meetingRecorder.active && meetingAudioOwnedByCurrentProcess && !meetingStopPending) {
            // Transient HUD/Notice close is not a meeting-stop command. Keep the continuous raw
            // audio session, crash-safe spool and foreground anchor alive.
            resetCapture()
            return
        }
        suspendMeetingAudioCapture()
        resetCapture()
        cancelPipeline()'''
if body.count(old_cleanup) != 1:
    raise SystemExit("Meetings 1.0.7 close cleanup marker changed unexpectedly")
new_body = body.replace(old_cleanup, new_cleanup, 1)
text = text[:match.start("body")] + new_body + text[match.end("body"):]

# When the same service instance is reopened, re-present Stop without perturbing a healthy lease.
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
            // Duplicate launcher opens and temporary foreground switches re-present Stop. A healthy
            // raw session is left untouched; if it really died, reacquire it now while the session
            // is explicitly open again.
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

# Generated-runtime invariants. These fail the release build rather than silently shipping another
# transcript-only meeting implementation.
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

# The transient close path may contain suspendMeetingAudioCapture only after the live-meeting guard.
close = close_pattern.search(check)
if close is None:
    raise SystemExit("Meetings 1.0.7 final close block missing")
close_body = close.group("body")
guard_pos = close_body.find("meetingRecorder.active && meetingAudioOwnedByCurrentProcess")
suspend_pos = close_body.find("suspendMeetingAudioCapture()")
if guard_pos < 0 or suspend_pos < 0 or suspend_pos < guard_pos:
    raise SystemExit("Meetings 1.0.7 close lifecycle invariant failed")
