from pathlib import Path
import re
import runpy

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / ".github/scripts/apply-meetings-102.py"
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
SERVICE = SRC / "AssistantPluginService.kt"
MEETINGS_ACTIVITY = SRC / "AssistantMeetingsActivity.kt"

runpy.run_path(str(BASE), run_name="__main__")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Meetings 1.0.3 uses the transient Notice tier, not a regular Surface or Activity.
# Notice expires independently from the plugin session, so the HUD can disappear while the
# continuous raw meeting audio lease remains alive.
replace_once(
    SERVICE,
    "import com.anezium.rokidbus.client.plugin.NexusNotice\n",
    "import com.anezium.rokidbus.client.plugin.NexusNotice\n"
    "import com.anezium.rokidbus.client.plugin.NexusNoticeAction\n",
    "notice action import",
)

replace_once(
    SERVICE,
    "    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)\n",
    "    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)\n"
    "    private var meetingsStopping = false\n"
    "    private var meetingsCompletion: String? = null\n"
    "    private var meetingsNoticeVisible = false\n"
    "    private var meetingsStartedElapsedRealtimeMs: Long? = null\n"
    "    private var meetingsNoticeTickerJob: Job? = null\n",
    "meeting notice state",
)

replace_once(
    SERVICE,
    "    private var meetingTranscriptionJob: Job? = null\n",
    "    private var meetingTranscriptionJob: Job? = null\n"
    "    private var meetingFinalizeTimeoutJob: Job? = null\n",
    "meeting STT drain state",
)

service = SERVICE.read_text(encoding="utf-8")

open_pattern = re.compile(
    r"    override fun onNexusOpen\(\) \{.*?\n    \}\n\n    override fun onNexusClose\(\) \{",
    re.DOTALL,
)
open_replacement = r'''    override fun onNexusOpen() {
        // No regular Surface: hiding a Surface is a plugin self-close. Meetings keeps the
        // plugin session alive and presents only a short-lived Notice.
        surface = null
        inkSurface = null
        uiController.onOpen()
        uiController.cancelLauncherHint()
        scheduleAccountContextSyncIfStale()

        if (meetingRecorder.active && !meetingAudioOwnedByCurrentProcess) {
            // A process-restored meeting is archival state. Never silently reacquire the mic.
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
            meetingFinalizeTimeoutJob?.cancel()
            meetingFinalizeTimeoutJob = null
            meetingsStartedElapsedRealtimeMs = null
            if (interruptedMeetingId != null) {
                serviceScope.launch(Dispatchers.IO) {
                    if (meetingAudioRecorder.finish(interruptedMeetingId) != null) {
                        meetingStore.markMeetingAudioRecovered(interruptedMeetingId)
                    }
                }
            }
        } else if (meetingRecorder.active && meetingAudioOwnedByCurrentProcess) {
            // Duplicate launcher opens and temporary foreground switches re-present the control.
            // If the raw lease was suspended by a plugin close, explicitly re-arm it.
            meetingAudioSuspended = false
            if (meetingsStartedElapsedRealtimeMs == null) {
                meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
            }
            scheduleMeetingRearm()
        }

        meetingsStopping = meetingStopPending
        showMeetingsNotice()
        if (meetingRecorder.active && !meetingsStopping) startMeetingsNoticeTicker()
    }

    override fun onNexusClose() {'''
service, count = open_pattern.subn(open_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"onNexusOpen: expected one block, replaced {count}")
SERVICE.write_text(service, encoding="utf-8")

text = SERVICE.read_text(encoding="utf-8")
close_marker = "    override fun onNexusClose() {\n"
if text.count(close_marker) != 1:
    raise SystemExit("Could not locate onNexusClose marker")
text = text.replace(
    close_marker,
    close_marker
    + "        meetingsNoticeTickerJob?.cancel()\n"
    + "        meetingsNoticeTickerJob = null\n"
    + "        meetingsNoticeVisible = false\n",
    1,
)
SERVICE.write_text(text, encoding="utf-8")

service = SERVICE.read_text(encoding="utf-8")
input_pattern = re.compile(
    r"    override fun onNexusInput\(event: NexusInputEvent\) \{.*?\n    \}\n\n"
    r"    private fun renderMeetingsControl\(\) \{.*?\n    \}\n\n"
    r"    override fun onNexusLinkState",
    re.DOTALL,
)
input_replacement = r'''    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusNoticeAction(id: String) {
        when (id) {
            MEETINGS_ACTION_START -> startMeetingFromNotice()
            MEETINGS_ACTION_STOP -> stopMeetingFromNotice()
        }
    }

    private fun startMeetingFromNotice() {
        if (meetingRecorder.active || meetingsStopping || meetingStopPending) return
        meetingsCompletion = null
        if (!handleMeetingTranscript("начать совещание") || !meetingRecorder.active) {
            meetingsCompletion = "Не удалось начать"
            showMeetingsNotice()
            return
        }

        // Button/Notice start has no start-command SpeechSession callback. This explicit rearm is
        // the missing bridge in 1.0.1/1.0.2: it starts the proven continuous raw NexusAudioSession.
        meetingsStartedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        meetingAudioSuspended = false
        scheduleMeetingRearm()
        showMeetingsNotice()
        startMeetingsNoticeTicker()
    }

    private fun stopMeetingFromNotice() {
        if (!meetingRecorder.active || meetingsStopping || meetingStopPending) return
        meetingsStopping = true
        meetingsNoticeTickerJob?.cancel()
        meetingsNoticeTickerJob = null
        showMeetingsNotice()
        requestMeetingFinish()
    }

    private fun showMeetingsNotice() {
        val client = nexusClient ?: return
        val title: String
        val lines: List<String>
        val actions: List<NexusNoticeAction>
        val ttlMs: Long

        when {
            meetingsStopping -> {
                title = "Meetings"
                lines = listOf("Сохраняю совещание", "Аудио → текст → протокол")
                actions = emptyList()
                ttlMs = MEETINGS_STOPPING_NOTICE_TTL_MS
            }
            meetingRecorder.active -> {
                title = "Meetings · ${meetingElapsedLabel()}"
                lines = listOf("● Совещание идёт", "Запись + расшифровка")
                actions = listOf(
                    NexusNoticeAction(
                        id = MEETINGS_ACTION_STOP,
                        glyph = "stop",
                        label = "Остановить",
                    ),
                )
                ttlMs = MEETINGS_ACTIVE_NOTICE_TTL_MS
            }
            meetingsCompletion != null -> {
                title = "Meetings · ГОТОВО"
                lines = listOf(meetingsCompletion!!.take(120))
                actions = listOf(
                    NexusNoticeAction(
                        id = MEETINGS_ACTION_START,
                        glyph = "record",
                        label = "Новое",
                    ),
                )
                ttlMs = MEETINGS_COMPLETE_NOTICE_TTL_MS
            }
            else -> {
                title = "Meetings"
                lines = listOf("Режим совещания", "Запись + текст + протокол")
                actions = listOf(
                    NexusNoticeAction(
                        id = MEETINGS_ACTION_START,
                        glyph = "record",
                        label = "Начать",
                    ),
                )
                ttlMs = MEETINGS_IDLE_NOTICE_TTL_MS
            }
        }

        val result = if (meetingsNoticeVisible) {
            client.updateNotice(
                NexusNoticeUpdate(
                    title = title,
                    lines = lines,
                    actions = actions,
                    ttlMs = ttlMs,
                ),
            )
        } else {
            client.showNotice(
                NexusNotice(
                    title = title,
                    lines = lines,
                    actions = actions,
                    ttlMs = ttlMs,
                    wakeDisplay = false,
                ),
            )
        }
        meetingsNoticeVisible = result == NexusSdkResult.SENT
    }

    private fun startMeetingsNoticeTicker() {
        meetingsNoticeTickerJob?.cancel()
        meetingsNoticeTickerJob = serviceScope.launch {
            repeat(MEETINGS_NOTICE_COUNTER_UPDATES) {
                delay(MEETINGS_NOTICE_COUNTER_INTERVAL_MS)
                if (!meetingRecorder.active || meetingsStopping || !meetingsNoticeVisible) {
                    return@launch
                }
                val client = nexusClient ?: return@launch
                // Do not resend actions here. A text-only update preserves the Stop row without
                // reopening a one-shot action after every timer tick.
                val result = client.updateNotice(
                    NexusNoticeUpdate(
                        title = "Meetings · ${meetingElapsedLabel()}",
                        lines = listOf("● Совещание идёт", "Запись + расшифровка"),
                        ttlMs = MEETINGS_ACTIVE_NOTICE_TTL_MS,
                    ),
                )
                if (result != NexusSdkResult.SENT) {
                    meetingsNoticeVisible = false
                    return@launch
                }
            }
            // No hide call: the Notice TTL now expires naturally. That closure does not close
            // the plugin session or release the meeting audio lease.
        }
    }

    private fun meetingElapsedLabel(): String {
        val started = meetingsStartedElapsedRealtimeMs ?: return "00:00"
        val totalSeconds =
            ((SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun completeMeetingsNotice(message: String) {
        meetingsStopping = false
        meetingsNoticeTickerJob?.cancel()
        meetingsNoticeTickerJob = null
        meetingsStartedElapsedRealtimeMs = null
        meetingsCompletion = message
        showMeetingsNotice()
    }

    override fun onNexusLinkState'''
service, count = input_pattern.subn(input_replacement, service, count=1)
if count != 1:
    raise SystemExit(f"Meetings Surface/input block: expected one replacement, got {count}")
SERVICE.write_text(service, encoding="utf-8")

replace_once(
    SERVICE,
    '''    override fun onNexusNoticeClosed(reason: NexusNoticeCloseReason) {
        if (reason == NexusNoticeCloseReason.USER) stopAnswerSpeech()
        uiController.onNoticeClosed(reason)
    }
''',
    '''    override fun onNexusNoticeClosed(reason: NexusNoticeCloseReason) {
        if (meetingsNoticeVisible) {
            meetingsNoticeVisible = false
            meetingsNoticeTickerJob?.cancel()
            meetingsNoticeTickerJob = null
            return
        }
        if (reason == NexusNoticeCloseReason.USER) stopAnswerSpeech()
        uiController.onNoticeClosed(reason)
    }
''',
    "meeting notice close isolation",
)

replace_once(
    SERVICE,
    '''                if (meetingStopPending) {
                    finalizeMeetingSession()
                    return
                }
''',
    '''                if (meetingStopPending) {
                    maybeFinalizeMeetingAfterTranscription()
                    return
                }
''',
    "audio-stop STT drain",
)

text = SERVICE.read_text(encoding="utf-8")
transcription_pattern = re.compile(
    r"    private fun enqueueMeetingTranscription\(pcm: ByteArray, format: NexusAudioFormat\) \{.*?\n"
    r"    \}\n\n"
    r"    private fun requestMeetingFinish\(\) \{",
    re.DOTALL,
)
transcription_replacement = r'''    private fun enqueueMeetingTranscription(pcm: ByteArray, format: NexusAudioFormat) {
        if (pcm.isEmpty() || !meetingRecorder.active) return
        if (meetingTranscriptionQueue.size >= MAX_MEETING_STT_QUEUE) {
            Log.w(TAG, "Meeting STT queue full; source audio remains preserved")
            return
        }
        meetingTranscriptionQueue.addLast(AssistantMeetingPcmChunk(pcm, format))
        ensureMeetingTranscriptionWorker()
    }

    private fun ensureMeetingTranscriptionWorker() {
        if (!meetingRecorder.active || meetingTranscriptionQueue.isEmpty()) {
            maybeFinalizeMeetingAfterTranscription()
            return
        }
        if (meetingTranscriptionJob?.isActive == true) return

        meetingTranscriptionJob = serviceScope.launch {
            try {
                while (meetingTranscriptionQueue.isNotEmpty() && meetingRecorder.active) {
                    val pending = meetingTranscriptionQueue.removeFirst()
                    val transcript = try {
                        transcriber.transcribe(pending.pcm, pending.format).trim()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.w(TAG, "Meeting STT failed: ${error.javaClass.simpleName}")
                        continue
                    }
                    val normalized = normalizeTranscript(transcript)
                    if (normalized.isBlank()) continue

                    // Meetings 1.0.3 is explicitly button-controlled. Spoken phrases such as
                    // "завершить совещание" are transcript content, never hidden control commands.
                    meetingRecorder.append(normalized)
                }
            } finally {
                meetingTranscriptionJob = null
                if (meetingTranscriptionQueue.isNotEmpty() && meetingRecorder.active) {
                    ensureMeetingTranscriptionWorker()
                } else {
                    maybeFinalizeMeetingAfterTranscription()
                }
            }
        }
    }

    private fun maybeFinalizeMeetingAfterTranscription() {
        if (!meetingStopPending || meetingAudioSession != null) return
        if (meetingTranscriptionJob?.isActive == true || meetingTranscriptionQueue.isNotEmpty()) {
            return
        }
        finalizeMeetingSession()
    }

    private fun scheduleMeetingFinalizeTimeout() {
        meetingFinalizeTimeoutJob?.cancel()
        meetingFinalizeTimeoutJob = serviceScope.launch {
            delay(MEETING_STT_DRAIN_TIMEOUT_MS)
            meetingFinalizeTimeoutJob = null
            if (!meetingStopPending) return@launch
            Log.w(TAG, "Meeting STT drain timed out; finalizing preserved source audio")
            meetingTranscriptionJob?.cancel()
            meetingTranscriptionJob = null
            meetingTranscriptionQueue.clear()
            finalizeMeetingSession()
        }
    }

    private fun requestMeetingFinish() {'''
text, count = transcription_pattern.subn(transcription_replacement, text, count=1)
if count != 1:
    raise SystemExit(f"meeting transcription worker: expected one replacement, got {count}")
SERVICE.write_text(text, encoding="utf-8")

replace_once(
    SERVICE,
    "        meetingStopPending = true\n"
    "        meetingAudioOwnedByCurrentProcess = false\n",
    "        meetingStopPending = true\n"
    "        meetingsStopping = true\n"
    "        meetingAudioOwnedByCurrentProcess = false\n",
    "meeting stopping state",
)
replace_once(
    SERVICE,
    "        meetingTranscriptionQueue.clear()\n"
    "        uiController.showTransient(\"Готовлю протокол…\")\n",
    "        scheduleMeetingFinalizeTimeout()\n"
    "        showMeetingsNotice()\n",
    "stop drain queue preservation",
)
replace_once(
    SERVICE,
    '''        val activeAudio = meetingAudioSession
        if (activeAudio != null) {
            activeAudio.stop()
        } else {
            finalizeMeetingSession()
        }
''',
    '''        val activeAudio = meetingAudioSession
        if (activeAudio != null) {
            activeAudio.stop()
        } else {
            maybeFinalizeMeetingAfterTranscription()
        }
''',
    "stop waits for STT",
)

replace_once(
    SERVICE,
    "    private fun finalizeMeetingSession() {\n"
    "        if (!meetingStopPending) return\n"
    "        val meeting = meetingRecorder.finish()\n",
    "    private fun finalizeMeetingSession() {\n"
    "        if (!meetingStopPending) return\n"
    "        meetingFinalizeTimeoutJob?.cancel()\n"
    "        meetingFinalizeTimeoutJob = null\n"
    "        val meeting = meetingRecorder.finish()\n",
    "finalize timeout cleanup",
)

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
                completeMeetingsNotice("Совещание завершено")
                return@launch
            }
            if (meeting.segments.isEmpty()) {
                completeMeetingsNotice(
                    if (audio != null) "Аудио сохранено · нет текста" else "Нет аудио и текста",
                )
                return@launch
            }
            showMeetingsNotice()
            launchPipeline {
                streamAssistantAnswer(meeting.summaryPrompt(), meeting.id)
            }
'''
if text.count(old_finalize) != 1:
    raise SystemExit(f"finalizeMeetingSession result UI: expected one match, found {text.count(old_finalize)}")
text = text.replace(old_finalize, new_finalize, 1)
SERVICE.write_text(text, encoding="utf-8")

text = SERVICE.read_text(encoding="utf-8")
old_protocol = '''                if (meetingProtocolId != null) {
                    followUpController.cancel()
                    automaticFollowUpCapture = false
                    uiController.showTransient("Протокол готов • сохранён на телефоне")
                    serviceScope.launch {
                        delay(MEETING_RESULT_CONFIRMATION_MS)
                        if (!meetingRecorder.active) uiController.dismissTransient()
                    }
                } else {
'''
new_protocol = '''                if (meetingProtocolId != null) {
                    followUpController.cancel()
                    automaticFollowUpCapture = false
                    serviceScope.launch {
                        val stored = withContext(Dispatchers.IO) {
                            val archive = meetingStore.meeting(meetingProtocolId)
                            val protocolSaved = !archive?.protocol.isNullOrBlank()
                            val transcriptSaved = !archive?.segments.isNullOrEmpty()
                            val audioSaved = meetingStore.meetingAudioFile(meetingProtocolId) != null
                            Triple(audioSaved, transcriptSaved, protocolSaved)
                        }
                        val completion = when {
                            stored.first && stored.second && stored.third ->
                                "Аудио + текст + протокол"
                            stored.first && stored.second ->
                                "Аудио + текст сохранены"
                            stored.first ->
                                "Сохранено только аудио"
                            stored.second && stored.third ->
                                "Текст + протокол сохранены"
                            stored.second ->
                                "Сохранён только текст"
                            else ->
                                "Ошибка сохранения"
                        }
                        completeMeetingsNotice(completion)
                    }
                } else {
'''
if text.count(old_protocol) == 1:
    text = text.replace(old_protocol, new_protocol, 1)
else:
    protocol_pattern = re.compile(
        r"                if \(meetingProtocolId != null\) \{\n.*?"
        r"                \} else \{\n",
        re.DOTALL,
    )
    text, count = protocol_pattern.subn(new_protocol, text, count=1)
    if count != 1:
        raise SystemExit(
            f"protocol completion branch: exact={text.count(old_protocol)} regex={count}"
        )
SERVICE.write_text(text, encoding="utf-8")

replace_once(
    MEETINGS_ACTIVITY,
    '"No meetings yet. Say “начать совещание” on the glasses.",',
    '"No meetings yet. Open Meetings on the glasses and press Start.",',
    "phone archive empty state",
)

text = SERVICE.read_text(encoding="utf-8")
constant_marker = "        private const val MEETING_RESULT_CONFIRMATION_MS = 2_500L\n"
if text.count(constant_marker) != 1:
    raise SystemExit("Could not locate meeting constants")
text = text.replace(
    constant_marker,
    constant_marker
    + "        private const val MEETINGS_NOTICE_COUNTER_INTERVAL_MS = 1_000L\n"
    + "        private const val MEETINGS_NOTICE_COUNTER_UPDATES = 7\n"
    + "        private const val MEETINGS_ACTIVE_NOTICE_TTL_MS = 2_500L\n"
    + "        private const val MEETINGS_IDLE_NOTICE_TTL_MS = 15_000L\n"
    + "        private const val MEETINGS_STOPPING_NOTICE_TTL_MS = 15_000L\n"
    + "        private const val MEETINGS_COMPLETE_NOTICE_TTL_MS = 8_000L\n"
    + "        private const val MEETING_STT_DRAIN_TIMEOUT_MS = 20_000L\n"
    + '        private const val MEETINGS_ACTION_START = "meetings-start"\n'
    + '        private const val MEETINGS_ACTION_STOP = "meetings-stop"\n',
    1,
)
SERVICE.write_text(text, encoding="utf-8")

service = SERVICE.read_text(encoding="utf-8")
for marker in (
    "NexusNoticeAction(",
    'MEETINGS_ACTION_START = "meetings-start"',
    'MEETINGS_ACTION_STOP = "meetings-stop"',
    "scheduleMeetingRearm()",
    "maybeFinalizeMeetingAfterTranscription()",
    "scheduleMeetingFinalizeTimeout()",
    "meetingRecorder.append(normalized)",
    "MEETING_STT_DRAIN_TIMEOUT_MS = 20_000L",
    "wakeDisplay = false",
):
    if marker not in service:
        raise SystemExit(f"Missing Meetings 1.0.3 marker: {marker}")

for forbidden in (
    "renderMeetingsControl()",
    "NexusActivity(",
    "NexusActivityAction(",
    'uiController.showTransient("Протокол готов • сохранён на телефоне")',
):
    if forbidden in service:
        raise SystemExit(f"Legacy Meetings UI remains: {forbidden}")

if "surface = nexusSurfaceSession(SURFACE_ID)" in service:
    raise SystemExit("Meetings 1.0.3 still mounts a regular Surface")
