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


# Work Mode never owns the system/Rokid assistant route. Keep microphone/STT/TTS/camera for
# meeting capture and other explicit Nexus workflows, but remove the assistant capability itself.
replace_once(
    MANIFEST,
    'android:value="surfaces,microphone,stt,tts,assistant,camera,ink_surface"',
    'android:value="surfaces,microphone,stt,tts,camera,ink_surface"',
)

service = SERVICE.read_text(encoding="utf-8")

# Replace the whole Nexus-open block after the 1.5.7 integration bundle. Opening Assistant from the
# Nexus launcher is now the explicit Work Mode control: first open starts a meeting; opening it again
# in the same process stops/finalizes it. A crash-restored meeting is archived first and then a fresh
# explicit open starts a new session. No system-assistant wake path is involved.
open_pattern = re.compile(
    r"    override fun onNexusOpen\(\) \{.*?\n    \}\n\n    override fun onNexusClose\(\) \{",
    re.DOTALL,
)
open_replacement = r'''    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        inkSurface = nexusInkSurfaceSession(INK_SURFACE_ID)
        uiController.onOpen()
        scheduleAccountContextSyncIfStale()

        when (
            AssistantWorkModePolicy.openAction(
                meetingActive = meetingRecorder.active,
                ownedByCurrentProcess = meetingAudioOwnedByCurrentProcess,
            )
        ) {
            AssistantWorkModeOpenAction.STOP_MEETING -> {
                requestMeetingFinish()
                return
            }

            AssistantWorkModeOpenAction.RECOVER_THEN_START -> {
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
                uiController.dismissTransient()
                if (interruptedMeetingId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        if (meetingAudioRecorder.finish(interruptedMeetingId) != null) {
                            meetingStore.markMeetingAudioRecovered(interruptedMeetingId)
                        }
                    }
                }
            }

            AssistantWorkModeOpenAction.START_MEETING -> Unit
        }

        if (!handleMeetingTranscript("начать совещание")) {
            uiController.showError("Не удалось запустить режим совещания")
        }
    }

    override fun onNexusClose() {'''
service, count = open_pattern.subn(open_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"Expected one onNexusOpen block, replaced {count}")

# Defense in depth: even if a future host accidentally forwards old assistant wake events to this
# plugin, Work Mode ignores them. The stock Rokid assistant remains the sole voice assistant owner.
ai_button_pattern = re.compile(
    r"    override fun onNexusGlassesAiButton\(active: Boolean\) \{.*?\n    \}\n\n    override fun onNexusMessage",
    re.DOTALL,
)
ai_button_replacement = r'''    override fun onNexusGlassesAiButton(active: Boolean) = Unit

    override fun onNexusMessage'''
service, count = ai_button_pattern.subn(ai_button_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"Expected one onNexusGlassesAiButton block, replaced {count}")

message_pattern = re.compile(
    r"    override fun onNexusMessage\(path: String, id: String, payload: JSONObject\) \{.*?\n    \}\n\n    override fun onDestroy",
    re.DOTALL,
)
message_replacement = r'''    override fun onNexusMessage(path: String, id: String, payload: JSONObject) = Unit

    override fun onDestroy'''
service, count = message_pattern.subn(message_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"Expected one onNexusMessage block, replaced {count}")

SERVICE.write_text(service, encoding="utf-8")

# Keep the launcher behavior explicit and unit-testable.
(SRC / "AssistantWorkModePolicy.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

internal enum class AssistantWorkModeOpenAction {
    START_MEETING,
    STOP_MEETING,
    RECOVER_THEN_START,
}

internal object AssistantWorkModePolicy {
    fun openAction(
        meetingActive: Boolean,
        ownedByCurrentProcess: Boolean,
    ): AssistantWorkModeOpenAction = when {
        meetingActive && ownedByCurrentProcess -> AssistantWorkModeOpenAction.STOP_MEETING
        meetingActive -> AssistantWorkModeOpenAction.RECOVER_THEN_START
        else -> AssistantWorkModeOpenAction.START_MEETING
    }
}
''', encoding="utf-8")

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "AssistantWorkModePolicyTest.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantWorkModePolicyTest {
    @Test
    fun firstExplicitOpenStartsMeeting() {
        assertEquals(
            AssistantWorkModeOpenAction.START_MEETING,
            AssistantWorkModePolicy.openAction(false, false),
        )
    }

    @Test
    fun secondOpenInSameProcessStopsMeeting() {
        assertEquals(
            AssistantWorkModeOpenAction.STOP_MEETING,
            AssistantWorkModePolicy.openAction(true, true),
        )
    }

    @Test
    fun restoredMeetingIsRecoveredBeforeFreshStart() {
        assertEquals(
            AssistantWorkModeOpenAction.RECOVER_THEN_START,
            AssistantWorkModePolicy.openAction(true, false),
        )
    }
}
''', encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
service = SERVICE.read_text(encoding="utf-8")
for marker in (
    'android:value="surfaces,microphone,stt,tts,camera,ink_surface"',
    'AssistantWorkModePolicy.openAction(',
    'AssistantWorkModeOpenAction.STOP_MEETING',
    'handleMeetingTranscript("начать совещание")',
    'override fun onNexusGlassesAiButton(active: Boolean) = Unit',
    'override fun onNexusMessage(path: String, id: String, payload: JSONObject) = Unit',
):
    if marker not in manifest and marker not in service:
        raise SystemExit(f"Missing Assistant 1.6.0 Work Mode marker: {marker}")

if 'surfaces,microphone,stt,tts,assistant,camera,ink_surface' in manifest:
    raise SystemExit("System-assistant capability is still present")
