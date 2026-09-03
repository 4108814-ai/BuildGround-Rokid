from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"


def replace_once(old: str, new: str) -> None:
    text = SERVICE.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one AssistantPluginService match, found {count}: {old[:140]!r}")
    SERVICE.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_count(old: str, new: str, expected: int) -> None:
    text = SERVICE.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise SystemExit(
            f"Expected {expected} AssistantPluginService matches, found {count}: {old[:140]!r}"
        )
    SERVICE.write_text(text.replace(old, new), encoding="utf-8")


# Silent Meeting Session: after the explicit start command, keep recognition alive but retire the
# glasses UI. The first user invocation remains visible long enough to establish the command; all
# automatically re-armed meeting capture is intentionally headless.
replace_once(
    '        uiController.showTransient("Listening…", legacyForceShow = true)\n',
    '        if (!meetingRecorder.active) {\n'
    '            uiController.showTransient("Listening…", legacyForceShow = true)\n'
    '        }\n',
)
replace_count(
    '                uiController.showTransient("Listening…")\n',
    '                if (!meetingRecorder.active) uiController.showTransient("Listening…")\n',
    expected=1,
)
replace_once(
    '                uiController.showTranscript(text)\n',
    '                if (!meetingRecorder.active) uiController.showTranscript(text)\n',
)
replace_once(
    '                    uiController.showTransient("Transcribing…")\n',
    '                    if (!meetingRecorder.active) uiController.showTransient("Transcribing…")\n',
)

# Starting, restoring, and accumulating a meeting must not pin the Assistant notice/card on the
# glasses. dismissTransient() retires the notice band without tearing down the Nexus session.
replace_once(
    '            uiController.showTransient("Совещание • запись")\n',
    '            uiController.dismissTransient()\n',
)
replace_once(
    '        uiController.showTransient("Совещание • ${meetingRecorder.segmentCount} фрагментов")\n',
    '        uiController.dismissTransient()\n',
)
replace_once(
    '            uiController.showTransient("Совещание • восстановлено")\n',
    '            uiController.dismissTransient()\n',
)

# Raw-audio fallback previously bypassed the local meeting command/segment interceptor. Keep it
# meeting-aware and re-arm the silent rolling capture after a successfully transcribed chunk.
replace_once(
    '            streamAssistantAnswer(transcript)\n',
    '            val normalized = normalizeTranscript(transcript)\n'
    '            if (handleMeetingTranscript(normalized)) {\n'
    '                if (meetingRecorder.active && meetingRearmPending) scheduleMeetingRearm()\n'
    '                return@launchPipeline\n'
    '            }\n'
    '            streamAssistantAnswer(normalized)\n',
)

# The stop command wakes the Assistant with "Готовлю протокол…", but the generated protocol itself
# belongs on the phone archive rather than streaming line-by-line onto the glasses.
replace_once(
    '                            showAnswer(answer.toString())\n',
    '                            if (meetingProtocolId == null) showAnswer(answer.toString())\n',
)
replace_once(
    '                            showAnswer(finalText)\n',
    '                            if (meetingProtocolId == null) showAnswer(finalText)\n',
)
replace_once(
    '                    showAnswer(finalAnswer.orEmpty())\n',
    '                    if (meetingProtocolId == null) showAnswer(finalAnswer.orEmpty())\n',
)

# Meeting summaries are persisted by the 1.5.3/1.5.4 archive integration. Do not read the whole
# protocol aloud or enter follow-up conversation mode; show only a short completion confirmation
# and dismiss it automatically. Normal Assistant answers retain the existing TTS/follow-up path.
replace_once(
    '                val completedAnswer = stripHudMarkdown(finalAnswer.orEmpty())\n'
    '                val waitForTts = authStore.speakAnswers()\n'
    '                followUpController.answerCompleted(waitForTts)\n'
    '                val speakResult = answerSpeaker.speakCompletedAnswer(completedAnswer)\n'
    '                if (waitForTts && speakResult != NexusSdkResult.SENT) {\n'
    '                    followUpController.ttsUnavailable()\n'
    '                }\n',
    '                val completedAnswer = stripHudMarkdown(finalAnswer.orEmpty())\n'
    '                if (meetingProtocolId != null) {\n'
    '                    followUpController.cancel()\n'
    '                    automaticFollowUpCapture = false\n'
    '                    uiController.showTransient("Протокол готов • сохранён на телефоне")\n'
    '                    serviceScope.launch {\n'
    '                        delay(MEETING_RESULT_CONFIRMATION_MS)\n'
    '                        if (!meetingRecorder.active) uiController.dismissTransient()\n'
    '                    }\n'
    '                } else {\n'
    '                    val waitForTts = authStore.speakAnswers()\n'
    '                    followUpController.answerCompleted(waitForTts)\n'
    '                    val speakResult = answerSpeaker.speakCompletedAnswer(completedAnswer)\n'
    '                    if (waitForTts && speakResult != NexusSdkResult.SENT) {\n'
    '                        followUpController.ttsUnavailable()\n'
    '                    }\n'
    '                }\n',
)

replace_once(
    '        private const val MEETING_REARM_DELAY_MS = 350L\n',
    '        private const val MEETING_REARM_DELAY_MS = 350L\n'
    '        private const val MEETING_RESULT_CONFIRMATION_MS = 2_500L\n',
)

text = SERVICE.read_text(encoding="utf-8")
required = (
    'if (!meetingRecorder.active) uiController.showTranscript(text)',
    'if (!meetingRecorder.active) uiController.showTransient("Listening…")',
    'uiController.dismissTransient()',
    'if (meetingProtocolId == null) showAnswer(answer.toString())',
    'Протокол готов • сохранён на телефоне',
    'MEETING_RESULT_CONFIRMATION_MS = 2_500L',
    'if (handleMeetingTranscript(normalized))',
)
for marker in required:
    if marker not in text:
        raise SystemExit(f"Missing silent meeting marker after integration: {marker}")

for forbidden in (
    'uiController.showTransient("Совещание • запись")',
    'uiController.showTransient("Совещание • ${meetingRecorder.segmentCount} фрагментов")',
    'uiController.showTransient("Совещание • восстановлено")',
):
    if forbidden in text:
        raise SystemExit(f"Meeting HUD marker still present: {forbidden}")
