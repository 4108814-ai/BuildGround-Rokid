from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"


def replace_once(old: str, new: str) -> None:
    text = SERVICE.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one AssistantPluginService match, found {count}: {old[:140]!r}")
    SERVICE.write_text(text.replace(old, new, 1), encoding="utf-8")


# Keep the active meeting in app-private storage and restore it after process/service recreation.
replace_once(
    "    private val meetingRecorder = AssistantMeetingRecorder()\n",
    "    private val meetingStore by lazy { AssistantMeetingStore(applicationContext) }\n"
    "    private val meetingRecorder by lazy {\n"
    "        AssistantMeetingRecorder(persistence = meetingStore)\n"
    "    }\n",
)

# Closing Nexus or the service must not destroy a meeting that has already been persisted. Back is
# still the explicit local cancel path and intentionally clears the active meeting.
replace_once(
    "        captureTriggerGate.resetSession()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        meetingRecorder.cancel()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
    "        captureTriggerGate.resetSession()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
)
replace_once(
    "        uiController.onClose()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        meetingRecorder.cancel()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
    "        uiController.onClose()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
)

# If Android recreated Assistant while a meeting was active, make that state visible and resume the
# rolling capture loop once Nexus opens again.
replace_once(
    "        uiController.onOpen()\n"
    "        scheduleAccountContextSyncIfStale()\n"
    "    }\n\n"
    "    override fun onNexusClose() {\n",
    "        uiController.onOpen()\n"
    "        scheduleAccountContextSyncIfStale()\n"
    "        if (meetingRecorder.active) {\n"
    "            meetingRearmPending = true\n"
    "            uiController.showTransient(\"Совещание • восстановлено\")\n"
    "            scheduleMeetingRearm()\n"
    "        }\n"
    "    }\n\n"
    "    override fun onNexusClose() {\n",
)

# The recorder archives transcript.json/transcript.txt before AI summarization. Pass the stable
# meeting id into the summarization request so the completed answer can become protocol.md.
replace_once(
    "            launchPipeline {\n"
    "                streamAssistantAnswer(meeting.summaryPrompt())\n"
    "            }\n",
    "            launchPipeline {\n"
    "                streamAssistantAnswer(meeting.summaryPrompt(), meeting.id)\n"
    "            }\n",
)

replace_once(
    "    private suspend fun streamAssistantAnswer(transcript: String) {\n",
    "    private suspend fun streamAssistantAnswer(\n"
    "        transcript: String,\n"
    "        meetingProtocolId: String? = null,\n"
    "    ) {\n",
)

replace_once(
    "            if (!failed) {\n"
    "                currentCoroutineContext().ensureActive()\n"
    "                answerSpeaker.speakCompletedAnswer(stripHudMarkdown(finalAnswer.orEmpty()))\n"
    "                try {\n",
    "            if (!failed) {\n"
    "                currentCoroutineContext().ensureActive()\n"
    "                if (meetingProtocolId != null && !finalAnswer.isNullOrBlank()) {\n"
    "                    try {\n"
    "                        withContext(Dispatchers.IO) {\n"
    "                            meetingStore.saveProtocol(meetingProtocolId, finalAnswer.orEmpty())\n"
    "                        }\n"
    "                    } catch (cancelled: CancellationException) {\n"
    "                        throw cancelled\n"
    "                    } catch (error: Throwable) {\n"
    "                        Log.w(\n"
    "                            TAG,\n"
    "                            \"Meeting protocol persistence failed: ${error.javaClass.simpleName}\",\n"
    "                        )\n"
    "                    }\n"
    "                }\n"
    "                answerSpeaker.speakCompletedAnswer(stripHudMarkdown(finalAnswer.orEmpty()))\n"
    "                try {\n",
)

text = SERVICE.read_text(encoding="utf-8")
required = (
    "AssistantMeetingStore(applicationContext)",
    "Совещание • восстановлено",
    "streamAssistantAnswer(meeting.summaryPrompt(), meeting.id)",
    "meetingStore.saveProtocol(meetingProtocolId, finalAnswer.orEmpty())",
)
for marker in required:
    if marker not in text:
        raise SystemExit(f"Missing meeting persistence marker after integration: {marker}")

# Guardrail: the persistence integration is Assistant-only.
changed = [line.strip() for line in text.splitlines() if "Relay" in line]
if changed:
    # Existing Assistant source should not acquire any Relay-specific dependency here.
    raise SystemExit("Meeting persistence integration unexpectedly references Relay")
