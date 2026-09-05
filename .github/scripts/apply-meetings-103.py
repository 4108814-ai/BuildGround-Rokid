from pathlib import Path
import re
import runpy

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / ".github/scripts/apply-meetings-102.py"
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant"
SERVICE = SRC / "AssistantPluginService.kt"
MEETINGS_ACTIVITY = SRC / "AssistantMeetingsActivity.kt"

runpy.run_path(str(BASE), run_name="__main__")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}: {old[:160]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Activity surfaces are the correct platform primitive for a background meeting:
# unlike a regular Nexus Surface they do not set FLAG_KEEP_SCREEN_ON, they collapse
# automatically, and quiet updates do not wake a dark display.
replace_once(
    SERVICE,
    "import com.anezium.rokidbus.client.plugin.NexusAudioStopReason\n",
    "import com.anezium.rokidbus.client.plugin.NexusAudioStopReason\n"
    "import com.anezium.rokidbus.client.plugin.NexusActivity\n"
    "import com.anezium.rokidbus.client.plugin.NexusActivityAction\n"
    "import com.anezium.rokidbus.client.plugin.NexusActivityProgress\n",
    "activity imports",
)

replace_once(
    SERVICE,
    "    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)\n",
    "    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)\n"
    "    private var meetingsActivityStarted = false\n"
    "    private var meetingsStopping = false\n"
    "    private var meetingsCompletion: String? = null\n"
    "    private var meetingsStartedElapsedRealtimeMs: Long? = null\n"
    "    private var meetingsTimerJob: Job? = null\n",
    "meeting activity state",
)

service = SERVICE.read_text(encoding="utf-8")

open_pattern = re.compile(
    r"    override fun onNexusOpen\(\) \{.*?\n    \}\n\n    override fun onNexusClose\(\) \{",
    re.DOTALL,
)
open_replacement = r'''    override fun onNexusOpen() {
        // Meetings deliberately does not mount a regular Surface. A regular Surface keeps the
        // glasses display awake and owns foreground input. Canonical Activity is background-safe.
        surface = null
        inkSurface = null
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

        meetingsStopping = false
        meetingsCompletion = null
        meetingsActivityStarted = false
        if (meetingRecorder.active) startMeetingsTimer(resetBaseline = false)
        renderMeetingsActivity(significant = true)
    }

    override fun onNexusClose() {'''
service, count = open_pattern.subn(open_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"onNexusOpen: expected one block, replaced {count}")

input_pattern = re.compile(
    r"    override fun onNexusInput\(event: NexusInputEvent\) \{.*?\n    \}\n\n    private fun renderMeetingsControl\(\) \{.*?\n    \}\n\n    override fun onNexusLinkState",
    re.DOTALL,
)
input_replacement = r'''    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusActivityAction(id: String) {
        when (id) {
            MEETINGS_ACTION_START -> startMeetingFromActivity()
            MEETINGS_ACTION_STOP -> stopMeetingFromActivity()
        }
    }

    override fun onNexusActivityClosed(reason: String) {
        // Closing only the presentation must never cancel a live recording.
        meetingsActivityStarted = false
    }

    private fun startMeetingFromActivity() {
        if (meetingRecorder.active || meetingsStopping) return
        meetingsCompletion = null
        handleMeetingTranscript("начать совещание")
        if (!meetingRecorder.active) {
            meetingsCompletion = "Не удалось начать"
            renderMeetingsActivity(significant = true)
            return
        }

        // Button/action start has no SpeechSession callback. Explicitly arm the proven continuous
        // raw meeting session here; this is the missing bridge in 1.0.1/1.0.2.
        meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        scheduleMeetingRearm()
        startMeetingsTimer(resetBaseline = false)
        renderMeetingsActivity(significant = true)
    }

    private fun stopMeetingFromActivity() {
        if (!meetingRecorder.active || meetingsStopping || meetingStopPending) return
        meetingsStopping = true
        meetingsCompletion = null
        meetingsTimerJob?.cancel()
        meetingsTimerJob = null
        renderMeetingsActivity(significant = true)
        requestMeetingFinish()
    }

    private fun startMeetingsTimer(resetBaseline: Boolean) {
        if (resetBaseline || meetingsStartedElapsedRealtimeMs == null) {
            meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
        meetingsTimerJob?.cancel()
        meetingsTimerJob = serviceScope.launch {
            while (meetingRecorder.active && !meetingsStopping) {
                renderMeetingsActivity(significant = false)
                delay(MEETINGS_TIMER_TICK_MS)
            }
        }
    }

    private fun renderMeetingsActivity(significant: Boolean) {
        val client = nexusClient ?: return
        val activity = when {
            meetingsStopping -> NexusActivity(
                glyph = "stop",
                primary = "СТОП",
                secondary = "Сохраняю совещание",
                progress = NexusActivityProgress.Indeterminate,
                detail = listOf("Аудио + текст + протокол"),
                actions = emptyList(),
                wakeDisplay = false,
            )
            meetingRecorder.active -> NexusActivity(
                glyph = "record",
                primary = meetingElapsedLabel(),
                secondary = "Совещание идёт",
                detail = listOf("Запись + расшифровка"),
                actions = listOf(
                    NexusActivityAction(
                        id = MEETINGS_ACTION_STOP,
                        glyph = "stop",
                        label = "Остановить",
                    ),
                ),
                wakeDisplay = false,
            )
            meetingsCompletion != null -> NexusActivity(
                glyph = "check",
                primary = "ГОТОВО",
                secondary = meetingsCompletion!!.take(28),
                detail = listOf("Архив на телефоне"),
                actions = listOf(
                    NexusActivityAction(
                        id = MEETINGS_ACTION_START,
                        glyph = "record",
                        label = "Новое",
                    ),
                ),
                wakeDisplay = false,
            )
            else -> NexusActivity(
                glyph = "record",
                primary = "ГОТОВО",
                secondary = "Режим совещания",
                detail = listOf("Запись + текст + протокол"),
                actions = listOf(
                    NexusActivityAction(
                        id = MEETINGS_ACTION_START,
                        glyph = "record",
                        label = "Начать",
                    ),
                ),
                wakeDisplay = false,
            )
        }
        val result = if (meetingsActivityStarted) {
            client.updateActivity(activity, significant = significant)
        } else {
            client.startActivity(activity)
        }
        if (result == NexusSdkResult.SENT) meetingsActivityStarted = true
    }

    private fun meetingElapsedLabel(): String {
        val started = meetingsStartedElapsedRealtimeMs ?: return "00:00"
        val totalSeconds = ((SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun completeMeetingsActivity(message: String) {
        meetingsStopping = false
        meetingsTimerJob?.cancel()
        meetingsTimerJob = null
        meetingsStartedElapsedRealtimeMs = null
        meetingsCompletion = message
        renderMeetingsActivity(significant = true)
    }

    override fun onNexusLinkState'''
service, count = input_pattern.subn(input_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"Meetings Surface/input block: expected one replacement, got {count}")

SERVICE.write_text(service, encoding="utf-8")

# Do not throw an Assistant transient over Rokid while the Activity already says "stopping".
text = SERVICE.read_text(encoding="utf-8")
request_pattern = re.compile(
    r"(    private fun requestMeetingFinish\(\) \{.*?meetingTranscriptionQueue\.clear\(\)\n)"
    r"        uiController\.showTransient\(\"Готовлю протокол…\"\)\n",
    re.DOTALL,
)
text, count = request_pattern.subn(r"\1        renderMeetingsActivity(significant = true)\n", text, count=1)
if count != 1:
    raise SystemExit(f"requestMeetingFinish transient: expected one replacement, got {count}")
SERVICE.write_text(text, encoding="utf-8")

# Finalization must report what really made it into phone storage.
text = SERVICE.read_text(encoding="utf-8")
old_finalize = '''            if (meeting == null) {
                uiController.showTransient("Совещание завершено")
                scheduleMeetingResultDismiss()
                return@launch
            }
            if (meeting.segments.isEmpty()) {
                uiController.showTransient(
                    if (audio != null) "Запись совещания сохранена" else "Совещание завершено",
                )
                scheduleMeetingResultDismiss()
                return@launch
            }
            uiController.showTransient("Готовлю протокол…")
            launchPipeline {
                streamAssistantAnswer(meeting.summaryPrompt(), meeting.id)
            }
'''
new_finalize = '''            if (meeting == null) {
                completeMeetingsActivity("Совещание завершено")
                return@launch
            }
            if (meeting.segments.isEmpty()) {
                completeMeetingsActivity(
                    if (audio != null) "Аудио сохранено · нет текста" else "Нет аудио и текста",
                )
                return@launch
            }
            renderMeetingsActivity(significant = true)
            launchPipeline {
                streamAssistantAnswer(meeting.summaryPrompt(), meeting.id)
            }
'''
if text.count(old_finalize) != 1:
    raise SystemExit(f"finalizeMeetingSession: expected one match, found {text.count(old_finalize)}")
text = text.replace(old_finalize, new_finalize, 1)
SERVICE.write_text(text, encoding="utf-8")

# The protocol path already persists protocol.md before this branch. Confirm actual archive state
# before showing success; do not emit an Assistant notice/HUD.
text = SERVICE.read_text(encoding="utf-8")
old = '''                if (meetingProtocolId != null) {
                    followUpController.cancel()
                    automaticFollowUpCapture = false
                    uiController.showTransient("Протокол готов • сохранён на телефоне")
                    serviceScope.launch {
                        delay(MEETING_RESULT_CONFIRMATION_MS)
                        if (!meetingRecorder.active) uiController.dismissTransient()
                    }
                } else {
'''
new = '''                if (meetingProtocolId != null) {
                    followUpController.cancel()
                    automaticFollowUpCapture = false
                    val stored = withContext(Dispatchers.IO) {
                        val archive = meetingStore.meeting(meetingProtocolId)
                        val protocolSaved = !archive?.protocol.isNullOrBlank()
                        val transcriptSaved = !archive?.segments.isNullOrEmpty()
                        val audioSaved = meetingStore.meetingAudioFile(meetingProtocolId) != null
                        Triple(audioSaved, transcriptSaved, protocolSaved)
                    }
                    val completion = when {
                        stored.first && stored.second && stored.third -> "Аудио + текст + протокол"
                        stored.first && stored.second -> "Аудио + текст сохранены"
                        stored.first -> "Сохранено только аудио"
                        stored.second && stored.third -> "Текст + протокол сохранены"
                        stored.second -> "Сохранён только текст"
                        else -> "Ошибка сохранения"
                    }
                    completeMeetingsActivity(completion)
                } else {
'''
if text.count(old) != 1:
    raise SystemExit(f"protocol completion branch: expected one match, found {text.count(old)}")
text = text.replace(old, new, 1)
SERVICE.write_text(text, encoding="utf-8")

# Phone archive language should match the button-driven Meetings product.
replace_once(
    MEETINGS_ACTIVITY,
    '"No meetings yet. Say “начать совещание” on the glasses.",',
    '"No meetings yet. Open Meetings on the glasses and press Start.",',
    "phone archive empty state",
)

# Constants for the canonical Activity UI.
text = SERVICE.read_text(encoding="utf-8")
constant_marker = "        private const val MEETING_RESULT_CONFIRMATION_MS = 2_500L\n"
if text.count(constant_marker) != 1:
    raise SystemExit("Could not locate meeting constants")
text = text.replace(
    constant_marker,
    constant_marker
    + '        private const val MEETINGS_TIMER_TICK_MS = 1_000L\n'
    + '        private const val MEETINGS_ACTION_START = "meetings-start"\n'
    + '        private const val MEETINGS_ACTION_STOP = "meetings-stop"\n',
    1,
)
SERVICE.write_text(text, encoding="utf-8")

# Pure UI-state contract to keep Start/Stop deterministic.
(SRC / "MeetingsActivityPolicy.kt").write_text(
    r'''package com.anezium.rokidbus.plugin.assistant

internal enum class MeetingsActivityMode { IDLE, ACTIVE, STOPPING, COMPLETE }

internal object MeetingsActivityPolicy {
    fun mode(
        active: Boolean,
        stopping: Boolean,
        hasCompletion: Boolean,
    ): MeetingsActivityMode = when {
        stopping -> MeetingsActivityMode.STOPPING
        active -> MeetingsActivityMode.ACTIVE
        hasCompletion -> MeetingsActivityMode.COMPLETE
        else -> MeetingsActivityMode.IDLE
    }
}
''',
    encoding="utf-8",
)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "MeetingsActivityPolicyTest.kt").write_text(
    r'''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingsActivityPolicyTest {
    @Test fun idle() =
        assertEquals(MeetingsActivityMode.IDLE, MeetingsActivityPolicy.mode(false, false, false))

    @Test fun active() =
        assertEquals(MeetingsActivityMode.ACTIVE, MeetingsActivityPolicy.mode(true, false, false))

    @Test fun stoppingWinsOverActive() =
        assertEquals(MeetingsActivityMode.STOPPING, MeetingsActivityPolicy.mode(true, true, false))

    @Test fun complete() =
        assertEquals(MeetingsActivityMode.COMPLETE, MeetingsActivityPolicy.mode(false, false, true))
}
''',
    encoding="utf-8",
)

# Guard the exact 1.0.3 intent.
service = SERVICE.read_text(encoding="utf-8")
for marker in (
    "NexusActivityAction(",
    'MEETINGS_ACTION_START = "meetings-start"',
    'MEETINGS_ACTION_STOP = "meetings-stop"',
    "scheduleMeetingRearm()",
    "startMeetingsTimer(resetBaseline = false)",
    "meetingStore.meetingAudioFile(meetingProtocolId)",
    "completeMeetingsActivity(completion)",
    "override fun onNexusActivityAction(id: String)",
):
    if marker not in service:
        raise SystemExit(f"Missing Meetings 1.0.3 marker: {marker}")

for forbidden in (
    "renderMeetingsControl()",
    'uiController.showTransient("Протокол готов • сохранён на телефоне")',
):
    if forbidden in service:
        raise SystemExit(f"Legacy Meetings UI remains: {forbidden}")

# No Glasses/Relay changes are made by this patch. Activity updates are quiet and never wake the
# display; physical screen-off timing remains native Rokid behavior and is device-verified separately.
