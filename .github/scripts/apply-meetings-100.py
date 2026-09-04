from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant"
SERVICE = SRC / "AssistantPluginService.kt"
MANIFEST = ROOT / "plugins/assistant/src/main/AndroidManifest.xml"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Dedicated Meetings identity. Keep the existing Android package so this APK updates Assistant
# in place and preserves the user's ChatGPT/provider sign-in and private meeting archive.
replace_once(MANIFEST, 'android:label="Assistant"', 'android:label="Meetings"')
replace_once(
    MANIFEST,
    'android:name="com.anezium.rokidbus.plugin.ID"\n                android:value="assistant"',
    'android:name="com.anezium.rokidbus.plugin.ID"\n                android:value="meetings"',
)
replace_once(
    MANIFEST,
    'android:name="com.anezium.rokidbus.plugin.DISPLAY_NAME"\n                android:value="Assistant"',
    'android:name="com.anezium.rokidbus.plugin.DISPLAY_NAME"\n                android:value="Meetings"',
)
replace_once(
    MANIFEST,
    'android:value="surfaces,microphone,stt,tts,camera,ink_surface"',
    'android:value="surfaces,microphone"',
)
replace_once(
    MANIFEST,
    'android:value="/plugin/assistant,/system/plugin,/stt,/tts/started,/tts/done,/camera/snapshot/result,/camera/snapshot/error"',
    'android:value="/plugin/meetings"',
)

service = SERVICE.read_text(encoding="utf-8")

# Opening Meetings never starts recording by itself. It only presents the explicit control.
# If a process died during an earlier meeting, recover/archive that interrupted source first.
open_pattern = re.compile(
    r"    override fun onNexusOpen\(\) \{.*?\n    \}\n\n    override fun onNexusClose\(\) \{",
    re.DOTALL,
)
open_replacement = r'''    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        inkSurface = nexusInkSurfaceSession(INK_SURFACE_ID)
        uiController.onOpen()
        uiController.cancelLauncherHint()
        scheduleAccountContextSyncIfStale()

        if (meetingRecorder.active && !meetingAudioOwnedByCurrentProcess) {
            val restoredMeetingId = meetingRecorder.id
            serviceScope.launch(Dispatchers.IO) {
                meetingAudioRecorder.recoverCompleted(restoredMeetingId)
            }
            val interruptedMeeting = meetingRecorder.finish()
            val interruptedMeetingId = interruptedMeeting?.id
            meetingAudioOwnedByCurrentProcess = false
            meetingAudioSuspended = true
            meetingRearmPending = false
            meetingAudioRetryJob?.cancel()
            meetingAudioRetryJob = null
            if (interruptedMeetingId != null) {
                serviceScope.launch(Dispatchers.IO) {
                    if (meetingAudioRecorder.finish(interruptedMeetingId) != null) {
                        meetingStore.markMeetingAudioRecovered(interruptedMeetingId)
                    }
                }
            }
        }

        renderMeetingsControl()
    }

    override fun onNexusClose() {'''
service, count = open_pattern.subn(open_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"Expected one onNexusOpen block, replaced {count}")

# The only glasses controls are explicit confirm and Back. Back must never cancel a live meeting;
# the recording is stopped only by the visible Finish control.
input_pattern = re.compile(
    r"    override fun onNexusInput\(event: NexusInputEvent\) \{.*?\n    \}\n\n    override fun onNexusLinkState",
    re.DOTALL,
)
input_replacement = r'''    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                when (MeetingsControlPolicy.action(meetingRecorder.active)) {
                    MeetingsControlAction.START -> {
                        handleMeetingTranscript("начать совещание")
                        if (meetingRecorder.active) {
                            renderMeetingsControl()
                        }
                    }
                    MeetingsControlAction.STOP -> {
                        requestMeetingFinish()
                    }
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                // Keep the plugin session and microphone lease alive. A surface self-hide is treated
                // by Nexus as plugin close, so Back is intentionally non-destructive during recording.
                if (meetingRecorder.active) {
                    renderMeetingsControl()
                } else {
                    surface?.hide()
                    uiController.onSurfaceHidden()
                }
            }
        }
    }

    override fun onNexusLinkState'''
service, count = input_pattern.subn(input_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"Expected one onNexusInput block, replaced {count}")

# Add the explicit on-glasses button/status card before link-state handling.
marker = "    override fun onNexusLinkState(state: Int) {\n"
if service.count(marker) != 1:
    raise SystemExit("Could not locate onNexusLinkState marker")
controls = r'''    private fun renderMeetingsControl() {
        uiController.cancelLauncherHint()
        val lines = if (meetingRecorder.active) {
            listOf(
                "● СОВЕЩАНИЕ ИДЁТ",
                "Фрагментов: ${meetingRecorder.segmentCount}",
                "",
                "Нажмите: завершить совещание",
            )
        } else {
            listOf(
                "РЕЖИМ СОВЕЩАНИЯ",
                "Запись + расшифровка + протокол",
                "",
                "Нажмите: начать совещание",
            )
        }
        renderCard(lines, forceShow = true)
    }

'''
service = service.replace(marker, controls + marker, 1)

# This build no longer presents itself as a general-purpose Assistant surface.
if 'title = "Assistant"' not in service:
    raise SystemExit('Expected Assistant card title marker')
service = service.replace('title = "Assistant"', 'title = "Meetings"', 1)

SERVICE.write_text(service, encoding="utf-8")

(SRC / "MeetingsControlPolicy.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

internal enum class MeetingsControlAction { START, STOP }

internal object MeetingsControlPolicy {
    fun action(meetingActive: Boolean): MeetingsControlAction =
        if (meetingActive) MeetingsControlAction.STOP else MeetingsControlAction.START
}
''', encoding="utf-8")

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "MeetingsControlPolicyTest.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingsControlPolicyTest {
    @Test
    fun idleControlStarts() {
        assertEquals(MeetingsControlAction.START, MeetingsControlPolicy.action(false))
    }

    @Test
    fun activeControlStops() {
        assertEquals(MeetingsControlAction.STOP, MeetingsControlPolicy.action(true))
    }
}
''', encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
service = SERVICE.read_text(encoding="utf-8")
required = (
    'android:value="meetings"',
    'android:value="Meetings"',
    'android:value="surfaces,microphone"',
    'android:value="/plugin/meetings"',
    'renderMeetingsControl()',
    'MeetingsControlPolicy.action(meetingRecorder.active)',
    'Нажмите: начать совещание',
    'Нажмите: завершить совещание',
    'title = "Meetings"',
)
for item in required:
    if item not in manifest and item not in service:
        raise SystemExit(f"Missing Meetings 1.0.0 marker: {item}")

for forbidden in (
    'android:value="assistant"',
    'surfaces,microphone,stt,tts,camera,ink_surface',
):
    if forbidden in manifest:
        raise SystemExit(f"Forbidden Meetings manifest marker remains: {forbidden}")
