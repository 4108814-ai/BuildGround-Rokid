from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant"
SERVICE = SRC / "AssistantPluginService.kt"


def replace_once(old: str, new: str) -> None:
    text = SERVICE.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one AssistantPluginService match, found {count}: {old[:180]!r}")
    SERVICE.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1.5.7 invariant: only a meeting explicitly started in the current process may own the continuous
# microphone lease. A persisted/crash-restored meeting is archival state, never an implicit mic owner.
replace_once(
    "    private var meetingAudioRetryJob: Job? = null\n"
    "    private var meetingAudioSuspended = false\n"
    "    private var meetingStopPending = false\n",
    "    private var meetingAudioRetryJob: Job? = null\n"
    "    private var meetingAudioSuspended = false\n"
    "    private var meetingAudioOwnedByCurrentProcess = false\n"
    "    private var meetingStopPending = false\n",
)

# Do not resurrect a continuous CXR microphone lease after process/service recreation. Finalize the
# interrupted meeting and its crash-safe source spool instead, then return the microphone to ordinary
# Assistant speech. The next meeting needs a fresh explicit start command.
replace_once(
    "        serviceScope.launch(Dispatchers.IO) {\n"
    "            meetingAudioRecorder.recoverCompleted(meetingRecorder.id)\n"
    "        }\n"
    "        if (meetingRecorder.active) {\n"
    "            meetingAudioSuspended = false\n"
    "            meetingRecorder.id?.let(meetingAudioRecorder::resume)\n"
    "            meetingRearmPending = true\n"
    "            uiController.dismissTransient()\n"
    "            scheduleMeetingRearm()\n"
    "        }\n",
    "        val restoredMeetingId = meetingRecorder.id\n"
    "        serviceScope.launch(Dispatchers.IO) {\n"
    "            meetingAudioRecorder.recoverCompleted(restoredMeetingId)\n"
    "        }\n"
    "        if (meetingRecorder.active) {\n"
    "            val interruptedMeeting = meetingRecorder.finish()\n"
    "            meetingAudioOwnedByCurrentProcess = false\n"
    "            meetingAudioSuspended = true\n"
    "            meetingRearmPending = false\n"
    "            meetingAudioRetryJob?.cancel()\n"
    "            meetingAudioRetryJob = null\n"
    "            uiController.dismissTransient()\n"
    "            if (interruptedMeeting != null) {\n"
    "                serviceScope.launch(Dispatchers.IO) {\n"
    "                    if (meetingAudioRecorder.finish(interruptedMeeting.id) != null) {\n"
    "                        meetingStore.markMeetingAudioRecovered(interruptedMeeting.id)\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "        }\n",
)

# A fresh voice command is the only point that grants this process ownership of Meeting Mode's raw mic.
replace_once(
    "            meetingStopPending = false\n"
    "            meetingAudioSuspended = false\n"
    "            meetingRearmPending = true\n",
    "            meetingStopPending = false\n"
    "            meetingAudioSuspended = false\n"
    "            meetingAudioOwnedByCurrentProcess = true\n"
    "            meetingRearmPending = true\n",
)

# Back/cancel must revoke current-process ownership before releasing the raw session.
replace_once(
    "            suspendMeetingAudioCapture()\n"
    "            meetingRecorder.cancel()\n",
    "            meetingAudioOwnedByCurrentProcess = false\n"
    "            suspendMeetingAudioCapture()\n"
    "            meetingRecorder.cancel()\n",
)

# Rearm only a meeting explicitly owned by this process; never race ordinary SpeechSession/fallback.
replace_once(
    "    private fun scheduleMeetingRearm() {\n"
    "        if (!meetingRecorder.active) return\n",
    "    private fun scheduleMeetingRearm() {\n"
    "        if (!meetingRecorder.active || !meetingAudioOwnedByCurrentProcess) return\n",
)
replace_once(
    "            if (!meetingRecorder.active || !isNexusSessionOpen || meetingAudioSuspended) return@launch\n"
    "            // Wait until the start-command SpeechSession (or raw fallback) has released the one mic lease.\n"
    "            if (speechSession != null || audioSession != null) return@launch\n",
    "            if (!meetingRecorder.active || !meetingAudioOwnedByCurrentProcess ||\n"
    "                !isNexusSessionOpen || meetingAudioSuspended\n"
    "            ) return@launch\n"
    "            // One physical CXR audio lease exists. Never race ordinary SpeechSession/fallback.\n"
    "            if (speechSession != null || audioSession != null) {\n"
    "                scheduleMeetingAudioRetry()\n"
    "                return@launch\n"
    "            }\n",
)

replace_once(
    "    private fun startMeetingAudioCapture() {\n"
    "        if (!meetingRecorder.active || !isNexusSessionOpen || meetingAudioSuspended) return\n"
    "        if (meetingAudioSession != null) return\n",
    "    private fun startMeetingAudioCapture() {\n"
    "        if (!AssistantMeetingCapturePolicy.canAcquire(\n"
    "                meetingActive = meetingRecorder.active,\n"
    "                ownedByCurrentProcess = meetingAudioOwnedByCurrentProcess,\n"
    "                nexusSessionOpen = isNexusSessionOpen,\n"
    "                suspended = meetingAudioSuspended,\n"
    "                stopPending = meetingStopPending,\n"
    "                ordinarySpeechActive = speechSession != null,\n"
    "                fallbackAudioActive = audioSession != null,\n"
    "                meetingAudioActive = meetingAudioSession != null,\n"
    "            )\n"
    "        ) {\n"
    "            if (meetingRecorder.active && meetingAudioOwnedByCurrentProcess &&\n"
    "                !meetingAudioSuspended && !meetingStopPending &&\n"
    "                (speechSession != null || audioSession != null)\n"
    "            ) scheduleMeetingAudioRetry()\n"
    "            return\n"
    "        }\n",
)

# Callbacks also validate ownership. A stale callback from a superseded raw session cannot keep/rearm it.
replace_once(
    "                if (meetingAudioSession !== createdAudio || !meetingRecorder.active) {\n",
    "                if (meetingAudioSession !== createdAudio || !meetingRecorder.active ||\n"
    "                    !meetingAudioOwnedByCurrentProcess\n"
    "                ) {\n",
)
replace_once(
    "                if (meetingAudioSession !== createdAudio || !meetingRecorder.active) return\n"
    "                // Source audio is persisted first. STT below is an independent best-effort contour.\n",
    "                if (meetingAudioSession !== createdAudio || !meetingRecorder.active ||\n"
    "                    !meetingAudioOwnedByCurrentProcess\n"
    "                ) return\n"
    "                // Source audio is persisted first. STT below is an independent best-effort contour.\n",
)
replace_once(
    "                if (!meetingRecorder.active || meetingAudioSuspended) return\n"
    "                Log.w(TAG, \"Meeting microphone stopped: $reason\")\n",
    "                if (!meetingRecorder.active || !meetingAudioOwnedByCurrentProcess ||\n"
    "                    meetingAudioSuspended\n"
    "                ) return\n"
    "                Log.w(TAG, \"Meeting microphone stopped: $reason\")\n",
)

# Retry is deliberately conservative: no 1 Hz lease contention while normal dialogue owns the mic.
replace_once(
    "    private fun scheduleMeetingAudioRetry() {\n"
    "        if (!meetingRecorder.active || meetingAudioSuspended || meetingStopPending) return\n",
    "    private fun scheduleMeetingAudioRetry() {\n"
    "        if (!meetingRecorder.active || !meetingAudioOwnedByCurrentProcess ||\n"
    "            meetingAudioSuspended || meetingStopPending\n"
    "        ) return\n",
)
replace_once(
    "            if (!meetingRecorder.active || !isNexusSessionOpen || meetingAudioSuspended) return@launch\n"
    "            startMeetingAudioCapture()\n",
    "            if (!meetingRecorder.active || !meetingAudioOwnedByCurrentProcess ||\n"
    "                !isNexusSessionOpen || meetingAudioSuspended || meetingStopPending\n"
    "            ) return@launch\n"
    "            if (speechSession != null || audioSession != null) {\n"
    "                scheduleMeetingAudioRetry()\n"
    "                return@launch\n"
    "            }\n"
    "            startMeetingAudioCapture()\n",
)

# Stop/finalization permanently releases ownership. Callback finalization remains valid because
# meetingStopPending is handled before the rearm guard.
replace_once(
    "        meetingStopPending = true\n"
    "        meetingRearmPending = false\n",
    "        meetingStopPending = true\n"
    "        meetingAudioOwnedByCurrentProcess = false\n"
    "        meetingRearmPending = false\n",
)
replace_once(
    "        meetingStopPending = false\n"
    "        meetingAudioSuspended = false\n"
    "        captureActive = false\n",
    "        meetingStopPending = false\n"
    "        meetingAudioOwnedByCurrentProcess = false\n"
    "        meetingAudioSuspended = false\n"
    "        captureActive = false\n",
)

# Keep the policy pure and unit-testable so future Meeting Mode changes cannot silently reintroduce
# the single-lease race.
(SRC / "AssistantMeetingCapturePolicy.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

internal object AssistantMeetingCapturePolicy {
    fun canAcquire(
        meetingActive: Boolean,
        ownedByCurrentProcess: Boolean,
        nexusSessionOpen: Boolean,
        suspended: Boolean,
        stopPending: Boolean,
        ordinarySpeechActive: Boolean,
        fallbackAudioActive: Boolean,
        meetingAudioActive: Boolean,
    ): Boolean =
        meetingActive &&
            ownedByCurrentProcess &&
            nexusSessionOpen &&
            !suspended &&
            !stopPending &&
            !ordinarySpeechActive &&
            !fallbackAudioActive &&
            !meetingAudioActive
}
''', encoding="utf-8")

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "AssistantMeetingCapturePolicyTest.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMeetingCapturePolicyTest {
    @Test
    fun restoredMeetingCannotSilentlyReacquireMicrophone() {
        assertFalse(canAcquire(owned = false))
    }

    @Test
    fun ordinarySpeechBlocksMeetingLease() {
        assertFalse(canAcquire(owned = true, speech = true))
    }

    @Test
    fun fallbackAudioBlocksMeetingLease() {
        assertFalse(canAcquire(owned = true, fallback = true))
    }

    @Test
    fun explicitCurrentProcessMeetingCanAcquireWhenMicIsFree() {
        assertTrue(canAcquire(owned = true))
    }

    @Test
    fun stopPendingBlocksReacquire() {
        assertFalse(canAcquire(owned = true, stopPending = true))
    }

    private fun canAcquire(
        owned: Boolean,
        speech: Boolean = false,
        fallback: Boolean = false,
        stopPending: Boolean = false,
    ): Boolean = AssistantMeetingCapturePolicy.canAcquire(
        meetingActive = true,
        ownedByCurrentProcess = owned,
        nexusSessionOpen = true,
        suspended = false,
        stopPending = stopPending,
        ordinarySpeechActive = speech,
        fallbackAudioActive = fallback,
        meetingAudioActive = false,
    )
}
''', encoding="utf-8")

text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "meetingAudioOwnedByCurrentProcess = false",
    "meetingAudioOwnedByCurrentProcess = true",
    "AssistantMeetingCapturePolicy.canAcquire",
    "val interruptedMeeting = meetingRecorder.finish()",
    "scheduleMeetingAudioRetry()",
):
    if marker not in text:
        raise SystemExit(f"Missing Assistant 1.5.7 stability marker: {marker}")

if "if (!meetingRecorder.active || !meetingAudioOwnedByCurrentProcess) return" not in text:
    raise SystemExit("Meeting rearm is not guarded by current-process ownership")
