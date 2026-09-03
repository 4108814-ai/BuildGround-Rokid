from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"


def replace_once(old: str, new: str) -> None:
    text = SERVICE.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one AssistantPluginService match, found {count}: {old[:160]!r}")
    SERVICE.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "    private val meetingAudioRecorder by lazy {\n"
    "        AssistantMeetingAudioRecorder(applicationContext, meetingStore)\n"
    "    }\n",
    "    private val meetingAudioRecorder by lazy {\n"
    "        AssistantMeetingAudioRecorder(applicationContext, meetingStore)\n"
    "    }\n"
    "    private val meetingAudioSegmenter = AssistantMeetingAudioSegmenter()\n"
    "    private var meetingAudioSession: NexusAudioSession? = null\n"
    "    private var meetingAudioFormat: NexusAudioFormat? = null\n"
    "    private var meetingAudioRetryJob: Job? = null\n"
    "    private var meetingAudioSuspended = false\n"
    "    private var meetingStopPending = false\n"
    "    private val meetingTranscriptionQueue = java.util.ArrayDeque<AssistantMeetingPcmChunk>()\n"
    "    private var meetingTranscriptionJob: Job? = null\n",
)

# Restore the same meeting-id spool after service/process recreation and recover any completed orphan.
replace_once(
    "        if (meetingRecorder.active) {\n"
    "            meetingAudioRecorder.resume()\n"
    "            meetingRearmPending = true\n"
    "            uiController.dismissTransient()\n"
    "            scheduleMeetingRearm()\n"
    "        }\n",
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
)

# Link/service shutdown pauses the raw lease but preserves the app-private spool and active transcript.
replace_once(
    "        captureTriggerGate.resetSession()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
    "        captureTriggerGate.resetSession()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        suspendMeetingAudioCapture()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
)
replace_once(
    "        uiController.onClose()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
    "        uiController.onClose()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        suspendMeetingAudioCapture()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
)

# Explicit Back remains the destructive cancel path; both contours are cancelled together.
replace_once(
    "            meetingRecorder.cancel()\n"
    "            meetingAudioRecorder.cancel()\n"
    "            resetCapture()\n"
    "            surface?.hide()\n",
    "            suspendMeetingAudioCapture()\n"
    "            meetingRecorder.cancel()\n"
    "            meetingAudioRecorder.cancel()\n"
    "            meetingTranscriptionQueue.clear()\n"
    "            meetingStopPending = false\n"
    "            resetCapture()\n"
    "            surface?.hide()\n",
)

# Start ties audio to the stable meeting id; failure to open the source spool aborts the meeting.
replace_once(
    "            meetingRecorder.start()\n"
    "            meetingAudioRecorder.startFresh()\n"
    "            meetingRearmPending = true\n",
    "            meetingRecorder.start()\n"
    "            val meetingId = meetingRecorder.id\n"
    "            if (meetingId == null || !meetingAudioRecorder.startFresh(meetingId)) {\n"
    "                meetingRecorder.cancel()\n"
    "                uiController.showError(\"Не удалось открыть запись совещания\")\n"
    "                return true\n"
    "            }\n"
    "            meetingAudioSegmenter.reset()\n"
    "            meetingTranscriptionQueue.clear()\n"
    "            meetingStopPending = false\n"
    "            meetingAudioSuspended = false\n"
    "            meetingRearmPending = true\n",
)

# Stop is a control command. Do not append it; stop the raw stream first, then archive and summarize.
replace_once(
    "        if (isMeetingStopCommand(transcript)) {\n"
    "            followUpController.cancel()\n"
    "            automaticFollowUpCapture = false\n"
    "            meetingRearmPending = false\n"
    "            meetingRearmJob?.cancel()\n"
    "            meetingRearmJob = null\n"
    "            val meeting = meetingRecorder.finish()\n"
    "            if (meeting != null) meetingAudioRecorder.finish(meeting.id)\n"
    "            if (meeting == null || meeting.segments.isEmpty()) {\n"
    "                uiController.showTransient(\"Совещание завершено • записей нет\")\n"
    "                return true\n"
    "            }\n"
    "            uiController.showTransient(\"Готовлю протокол…\")\n"
    "            launchPipeline {\n"
    "                streamAssistantAnswer(meeting.summaryPrompt(), meeting.id)\n"
    "            }\n"
    "            return true\n"
    "        }\n",
    "        if (isMeetingStopCommand(transcript)) {\n"
    "            requestMeetingFinish()\n"
    "            return true\n"
    "        }\n",
)

# The speech-session PCM tee from the prototype is deliberately disabled. Meeting Mode owns one raw
# NexusAudioSession after the start command, so there is no second microphone lease or utterance gap.
replace_once(
    "            override fun onSpeechAudioPcm(\n"
    "                pcm: ByteArray,\n"
    "                sampleRateHz: Int,\n"
    "                channels: Int,\n"
    "                encoding: String,\n"
    "            ) {\n"
    "                if (generation != captureGeneration || !captureActive || !meetingRecorder.active) return\n"
    "                meetingAudioRecorder.append(pcm, sampleRateHz, channels, encoding)\n"
    "            }\n\n",
    "            override fun onSpeechAudioPcm(\n"
    "                pcm: ByteArray,\n"
    "                sampleRateHz: Int,\n"
    "                channels: Int,\n"
    "                encoding: String,\n"
    "            ) = Unit\n\n",
)

# The ordinary six-second fallback is not the meeting recorder anymore.
replace_once(
    "                pcmBuffer.write(pcm)\n"
    "                if (meetingRecorder.active) {\n"
    "                    val format = audioFormat\n"
    "                    if (format != null) {\n"
    "                        meetingAudioRecorder.append(\n"
    "                            pcm,\n"
    "                            format.sampleRateHz,\n"
    "                            format.channels,\n"
    "                            \"pcm16le\",\n"
    "                        )\n"
    "                    }\n"
    "                }\n",
    "                pcmBuffer.write(pcm)\n",
)

old_rearm = r'''    private fun scheduleMeetingRearm() {
        if (!meetingRecorder.active) return
        meetingRearmPending = false
        meetingRearmJob?.cancel()
        meetingRearmJob = serviceScope.launch {
            delay(MEETING_REARM_DELAY_MS)
            meetingRearmJob = null
            if (!meetingRecorder.active || captureActive || !isNexusSessionOpen) return@launch
            automaticFollowUpCapture = false
            beginCapture()
        }
    }

'''
new_rearm = r'''    private fun scheduleMeetingRearm() {
        if (!meetingRecorder.active) return
        meetingRearmPending = true
        meetingRearmJob?.cancel()
        meetingRearmJob = serviceScope.launch {
            delay(MEETING_REARM_DELAY_MS)
            meetingRearmJob = null
            if (!meetingRecorder.active || !isNexusSessionOpen || meetingAudioSuspended) return@launch
            // Wait until the start-command SpeechSession (or raw fallback) has released the one mic lease.
            if (speechSession != null || audioSession != null) return@launch
            meetingRearmPending = false
            automaticFollowUpCapture = false
            startMeetingAudioCapture()
        }
    }

    private fun startMeetingAudioCapture() {
        if (!meetingRecorder.active || !isNexusSessionOpen || meetingAudioSuspended) return
        if (meetingAudioSession != null) return
        val meetingId = meetingRecorder.id ?: return
        if (!meetingAudioRecorder.resume(meetingId)) {
            scheduleMeetingAudioRetry()
            return
        }
        meetingAudioSegmenter.reset()
        meetingAudioFormat = null
        captureActive = true

        var createdAudio: NexusAudioSession? = null
        val callbacks = object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                if (meetingAudioSession !== createdAudio || !meetingRecorder.active) {
                    createdAudio?.stop()
                    return
                }
                if (format.sampleRate != AssistantMeetingAudioRecorder.SAMPLE_RATE_HZ ||
                    format.channels != AssistantMeetingAudioRecorder.CHANNELS ||
                    !format.encoding.equals("pcm16le", ignoreCase = true)
                ) {
                    Log.w(
                        TAG,
                        "Meeting audio format unsupported: ${format.sampleRate}/${format.channels}/${format.encoding}",
                    )
                    createdAudio?.stop()
                    return
                }
                meetingAudioFormat = format
                captureActive = true
                uiController.dismissTransient()
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                val format = meetingAudioFormat ?: return
                if (meetingAudioSession !== createdAudio || !meetingRecorder.active) return
                // Source audio is persisted first. STT below is an independent best-effort contour.
                if (!meetingAudioRecorder.append(pcm, format)) {
                    Log.w(TAG, "Meeting audio append rejected")
                }
                meetingAudioSegmenter.accept(pcm, elapsedRealtimeMs)?.let { chunk ->
                    enqueueMeetingTranscription(chunk, format)
                }
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                if (meetingAudioSession === createdAudio) meetingAudioSession = null
                meetingAudioFormat?.let { format ->
                    meetingAudioSegmenter.flush()?.let { chunk ->
                        enqueueMeetingTranscription(chunk, format)
                    }
                }
                meetingAudioFormat = null
                meetingAudioRecorder.flush()
                captureActive = false
                if (meetingStopPending) {
                    finalizeMeetingSession()
                    return
                }
                if (!meetingRecorder.active || meetingAudioSuspended) return
                Log.w(TAG, "Meeting microphone stopped: $reason")
                scheduleMeetingAudioRetry()
            }
        }

        val session = nexusAudioSession(callbacks)
        createdAudio = session
        meetingAudioSession = session
        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (result != NexusSdkResult.SENT) {
            if (meetingAudioSession === session) meetingAudioSession = null
            captureActive = false
            Log.w(TAG, "Meeting microphone start rejected: $result")
            scheduleMeetingAudioRetry()
        }
    }

    private fun scheduleMeetingAudioRetry() {
        if (!meetingRecorder.active || meetingAudioSuspended || meetingStopPending) return
        meetingAudioRetryJob?.cancel()
        meetingAudioRetryJob = serviceScope.launch {
            delay(MEETING_AUDIO_RETRY_MS)
            meetingAudioRetryJob = null
            if (!meetingRecorder.active || !isNexusSessionOpen || meetingAudioSuspended) return@launch
            startMeetingAudioCapture()
        }
    }

    private fun enqueueMeetingTranscription(pcm: ByteArray, format: NexusAudioFormat) {
        if (pcm.isEmpty() || !meetingRecorder.active || meetingStopPending) return
        if (meetingTranscriptionQueue.size >= MAX_MEETING_STT_QUEUE) {
            Log.w(TAG, "Meeting STT queue full; source audio remains preserved")
            return
        }
        meetingTranscriptionQueue.addLast(AssistantMeetingPcmChunk(pcm, format))
        if (meetingTranscriptionJob?.isActive == true) return
        meetingTranscriptionJob = serviceScope.launch {
            try {
                while (meetingTranscriptionQueue.isNotEmpty() &&
                    meetingRecorder.active &&
                    !meetingStopPending
                ) {
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
                    handleMeetingTranscript(normalized)
                    if (meetingStopPending) {
                        meetingTranscriptionQueue.clear()
                        break
                    }
                }
            } finally {
                meetingTranscriptionJob = null
            }
        }
    }

    private fun requestMeetingFinish() {
        if (!meetingRecorder.active || meetingStopPending) return
        followUpController.cancel()
        automaticFollowUpCapture = false
        meetingStopPending = true
        meetingRearmPending = false
        meetingRearmJob?.cancel()
        meetingRearmJob = null
        meetingAudioRetryJob?.cancel()
        meetingAudioRetryJob = null
        meetingTranscriptionQueue.clear()
        uiController.showTransient("Готовлю протокол…")
        val activeAudio = meetingAudioSession
        if (activeAudio != null) {
            activeAudio.stop()
        } else {
            finalizeMeetingSession()
        }
    }

    private fun finalizeMeetingSession() {
        if (!meetingStopPending) return
        val meeting = meetingRecorder.finish()
        meetingStopPending = false
        meetingAudioSuspended = false
        captureActive = false
        meetingAudioSession = null
        meetingAudioFormat = null
        meetingAudioSegmenter.reset()
        meetingTranscriptionQueue.clear()
        serviceScope.launch {
            val audio = meeting?.id?.let { meetingId ->
                withContext(Dispatchers.IO) { meetingAudioRecorder.finish(meetingId) }
            }
            if (meeting == null) {
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
        }
    }

    private fun scheduleMeetingResultDismiss() {
        serviceScope.launch {
            delay(MEETING_RESULT_CONFIRMATION_MS)
            if (!meetingRecorder.active) uiController.dismissTransient()
        }
    }

    private fun suspendMeetingAudioCapture() {
        meetingAudioSuspended = true
        meetingAudioRetryJob?.cancel()
        meetingAudioRetryJob = null
        meetingAudioRecorder.flush()
        val activeAudio = meetingAudioSession
        meetingAudioSession = null
        activeAudio?.stop()
        captureActive = false
    }

'''
replace_once(old_rearm, new_rearm)

replace_once(
    '                    uiController.showTransient("Протокол готов • сохранён на телефоне")\n',
    '                    uiController.showTransient(\"Протокол и запись сохранены\")\n',
)

replace_once(
    "        private const val MEETING_REARM_DELAY_MS = 350L\n",
    "        private const val MEETING_REARM_DELAY_MS = 350L\n"
    "        private const val MEETING_AUDIO_RETRY_MS = 1_000L\n"
    "        private const val MAX_MEETING_STT_QUEUE = 12\n",
)

text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "startMeetingAudioCapture()",
    "Source audio is persisted first",
    "meetingTranscriptionQueue",
    "requestMeetingFinish()",
    "Протокол и запись сохранены",
):
    if marker not in text:
        raise SystemExit(f"Missing unified Meeting Session marker: {marker}")
