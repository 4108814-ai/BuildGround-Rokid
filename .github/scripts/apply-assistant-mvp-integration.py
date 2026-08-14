from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {relative_path}, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Version this bundle so Android and the Nexus settings UI make the update obvious.
build_gradle = "plugins/assistant/build.gradle.kts"
replace_once(build_gradle, '        versionCode = 9\n', '        versionCode = 10\n')
replace_once(build_gradle, '        versionName = "1.4.2"\n', '        versionName = "1.5.1"\n')

# Notification listener is Assistant-owned and deliberately does not modify Relay.
manifest = "plugins/assistant/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '        <service\n            android:name=".AssistantPluginService"\n',
    '        <service\n'
    '            android:name=".AssistantNotificationListenerService"\n'
    '            android:exported="true"\n'
    '            android:label="Assistant notification access"\n'
    '            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.service.notification.NotificationListenerService" />\n'
    '            </intent-filter>\n'
    '        </service>\n\n'
    '        <service\n'
    '            android:name=".AssistantPluginService"\n',
)

# Make notification access discoverable in Assistant settings.
settings = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
replace_once(
    settings,
    "import android.os.Bundle\n",
    "import android.os.Bundle\nimport android.provider.Settings\n",
)
replace_once(
    settings,
    "    private lateinit var phoneAccessSlot: LinearLayout\n",
    "    private lateinit var phoneAccessSlot: LinearLayout\n"
    "    private lateinit var notificationAccessSlot: LinearLayout\n",
)
replace_once(
    settings,
    "            addView(phoneAccessSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, \"Plugin\"), NexusUi.block())\n",
    "            addView(phoneAccessSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(\n"
    "                NexusUi.sectionRow(this@AssistantSettingsActivity, \"Notifications\"),\n"
    "                NexusUi.block(),\n"
    "            )\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))\n"
    "            notificationAccessSlot = LinearLayout(this@AssistantSettingsActivity).apply {\n"
    "                orientation = LinearLayout.VERTICAL\n"
    "            }\n"
    "            addView(notificationAccessSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, \"Plugin\"), NexusUi.block())\n",
)
replace_once(
    settings,
    "        renderCalendarAccess()\n        renderPhoneAccess()\n    }\n\n    // ------------------------------------------------------------------ providers\n",
    "        renderCalendarAccess()\n"
    "        renderPhoneAccess()\n"
    "        renderNotificationAccess()\n"
    "    }\n\n"
    "    override fun onResume() {\n"
    "        super.onResume()\n"
    "        if (::notificationAccessSlot.isInitialized) renderNotificationAccess()\n"
    "    }\n\n"
    "    // ------------------------------------------------------------------ providers\n",
)
replace_once(
    settings,
    "    private fun requestPhoneAccess() {\n"
    "        requestPermissions(PHONE_PERMISSIONS, REQUEST_PHONE_ACCESS)\n"
    "    }\n\n"
    "    // ------------------------------------------------------------------ memory\n",
    "    private fun requestPhoneAccess() {\n"
    "        requestPermissions(PHONE_PERMISSIONS, REQUEST_PHONE_ACCESS)\n"
    "    }\n\n"
    "    private fun renderNotificationAccess() {\n"
    "        val granted = hasAssistantNotificationAccess(this)\n"
    "        notificationAccessSlot.removeAllViews()\n"
    "        notificationAccessSlot.addView(\n"
    "            NexusUi.card(this).apply {\n"
    "                addView(\n"
    "                    LinearLayout(this@AssistantSettingsActivity).apply {\n"
    "                        orientation = LinearLayout.HORIZONTAL\n"
    "                        gravity = Gravity.CENTER_VERTICAL\n"
    "                        addView(\n"
    "                            LinearLayout(this@AssistantSettingsActivity).apply {\n"
    "                                orientation = LinearLayout.VERTICAL\n"
    "                                addView(\n"
    "                                    NexusUi.rowTitle(\n"
    "                                        this@AssistantSettingsActivity,\n"
    "                                        \"Smart notifications\",\n"
    "                                    ),\n"
    "                                    NexusUi.block(),\n"
    "                                )\n"
    "                                addView(\n"
    "                                    NexusUi.rowSub(\n"
    "                                        this@AssistantSettingsActivity,\n"
    "                                        if (granted) {\n"
    "                                            \"Assistant can read recent notifications and use supported reply actions\"\n"
    "                                        } else {\n"
    "                                            \"Grant Android notification access; Relay is not changed\"\n"
    "                                        },\n"
    "                                    ),\n"
    "                                    NexusUi.block(),\n"
    "                                )\n"
    "                            },\n"
    "                            LinearLayout.LayoutParams(\n"
    "                                0,\n"
    "                                ViewGroup.LayoutParams.WRAP_CONTENT,\n"
    "                                1f,\n"
    "                            ).apply {\n"
    "                                marginEnd = NexusUi.dp(this@AssistantSettingsActivity, 12)\n"
    "                            },\n"
    "                        )\n"
    "                        if (granted) {\n"
    "                            addView(\n"
    "                                NexusUi.metaLabel(\n"
    "                                    this@AssistantSettingsActivity,\n"
    "                                    \"Granted\",\n"
    "                                    NexusUi.GREEN,\n"
    "                                ),\n"
    "                            )\n"
    "                        } else {\n"
    "                            addView(\n"
    "                                NexusUi.textButton(\n"
    "                                    this@AssistantSettingsActivity,\n"
    "                                    \"Open settings\",\n"
    "                                ).apply {\n"
    "                                    setOnClickListener {\n"
    "                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))\n"
    "                                    }\n"
    "                                },\n"
    "                            )\n"
    "                        }\n"
    "                    },\n"
    "                    NexusUi.block(),\n"
    "                )\n"
    "            },\n"
    "            NexusUi.block(),\n"
    "        )\n"
    "    }\n\n"
    "    // ------------------------------------------------------------------ memory\n",
)

# Register deterministic tools and smart-notification actions.
service = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"
replace_once(
    service,
    "                ) + assistantCalendarTools(calendarGateway) +\n"
    "                assistantPhoneTools(phoneGateway),\n",
    "                ) + assistantCalendarTools(calendarGateway) +\n"
    "                assistantPhoneTools(phoneGateway) +\n"
    "                assistantMvpTools(calendarGateway, reminderStore) +\n"
    "                assistantNotificationTools(applicationContext),\n",
)
replace_once(
    service,
    "    private var automaticFollowUpCapture = false\n",
    "    private var automaticFollowUpCapture = false\n"
    "    private val meetingRecorder = AssistantMeetingRecorder()\n"
    "    private var meetingRearmJob: Job? = null\n"
    "    private var meetingRearmPending = false\n",
)

# Back explicitly exits meeting mode without summarizing.
replace_once(
    service,
    "            cancelPipeline()\n"
    "            resetCapture()\n"
    "            surface?.hide()\n",
    "            cancelPipeline()\n"
    "            meetingRearmJob?.cancel()\n"
    "            meetingRearmJob = null\n"
    "            meetingRearmPending = false\n"
    "            meetingRecorder.cancel()\n"
    "            resetCapture()\n"
    "            surface?.hide()\n",
)

# Close/destroy also terminate a live meeting locally and quietly.
replace_once(
    service,
    "        captureTriggerGate.resetSession()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
    "        captureTriggerGate.resetSession()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        meetingRecorder.cancel()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n",
)
replace_once(
    service,
    "        uiController.onClose()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n"
    "        clearInkSurface(hide = true)\n"
    "        closeAnswerSpeechSession()\n"
    "        serviceScope.cancel()\n",
    "        uiController.onClose()\n"
    "        meetingRearmJob?.cancel()\n"
    "        meetingRearmJob = null\n"
    "        meetingRearmPending = false\n"
    "        meetingRecorder.cancel()\n"
    "        resetCapture()\n"
    "        cancelPipeline()\n"
    "        clearInkSurface(hide = true)\n"
    "        closeAnswerSpeechSession()\n"
    "        serviceScope.cancel()\n",
)

# Meeting capture re-arms after each final transcript and treats no-speech as a quiet gap.
replace_once(
    service,
    "                captureActive = false\n"
    "                if (finalDelivered) return\n"
    "                if (automaticFollowUpCapture &&\n",
    "                captureActive = false\n"
    "                if (finalDelivered) {\n"
    "                    if (meetingRecorder.active && meetingRearmPending) scheduleMeetingRearm()\n"
    "                    return\n"
    "                }\n"
    "                if (meetingRecorder.active &&\n"
    "                    (reason == NexusSpeechStopReason.NO_SPEECH ||\n"
    "                        reason == NexusSpeechStopReason.COMPLETED)\n"
    "                ) {\n"
    "                    uiController.dismissTransient()\n"
    "                    scheduleMeetingRearm()\n"
    "                    return\n"
    "                }\n"
    "                if (automaticFollowUpCapture &&\n",
)

# Intercept the local meeting commands before any LLM call.
replace_once(
    service,
    "    private fun launchAssistantPipeline(transcript: String) {\n"
    "        val normalized = normalizeTranscript(transcript)\n"
    "        if (normalized.isEmpty()) {\n"
    "            uiController.showError(\"Didn't catch that\")\n"
    "            return\n"
    "        }\n"
    "        launchPipeline {\n"
    "            streamAssistantAnswer(normalized)\n"
    "        }\n"
    "    }\n",
    "    private fun launchAssistantPipeline(transcript: String) {\n"
    "        val normalized = normalizeTranscript(transcript)\n"
    "        if (normalized.isEmpty()) {\n"
    "            uiController.showError(\"Didn't catch that\")\n"
    "            return\n"
    "        }\n"
    "        if (handleMeetingTranscript(normalized)) return\n"
    "        launchPipeline {\n"
    "            streamAssistantAnswer(normalized)\n"
    "        }\n"
    "    }\n",
)

marker = "    private fun launchAssistantPipeline(transcript: String) {\n"
path = ROOT / service
text = path.read_text(encoding="utf-8")
if text.count(marker) != 1:
    raise SystemExit("Could not locate launchAssistantPipeline for meeting helpers")
meeting_helpers = r'''    private fun handleMeetingTranscript(transcript: String): Boolean {
        if (!meetingRecorder.active && isMeetingStartCommand(transcript)) {
            followUpController.cancel()
            automaticFollowUpCapture = false
            meetingRearmJob?.cancel()
            meetingRearmJob = null
            meetingRecorder.start()
            meetingRearmPending = true
            uiController.showTransient("Совещание • запись")
            return true
        }
        if (!meetingRecorder.active) return false

        if (isMeetingStopCommand(transcript)) {
            followUpController.cancel()
            automaticFollowUpCapture = false
            meetingRearmPending = false
            meetingRearmJob?.cancel()
            meetingRearmJob = null
            val meeting = meetingRecorder.finish()
            if (meeting == null || meeting.segments.isEmpty()) {
                uiController.showTransient("Совещание завершено • записей нет")
                return true
            }
            uiController.showTransient("Готовлю протокол…")
            launchPipeline {
                streamAssistantAnswer(meeting.summaryPrompt())
            }
            return true
        }

        meetingRecorder.append(transcript)
        meetingRearmPending = true
        uiController.showTransient("Совещание • ${meetingRecorder.segmentCount} фрагментов")
        return true
    }

    private fun scheduleMeetingRearm() {
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
path.write_text(text.replace(marker, meeting_helpers + marker, 1), encoding="utf-8")

# Add meeting delay constant beside the existing service constants.
text = path.read_text(encoding="utf-8")
constant_marker = "        private const val FALLBACK_CAPTURE_DURATION_MS = "
idx = text.find(constant_marker)
if idx < 0:
    raise SystemExit("Could not locate Assistant service companion constants")
line_end = text.find("\n", idx)
text = text[: line_end + 1] + "        private const val MEETING_REARM_DELAY_MS = 350L\n" + text[line_end + 1 :]
path.write_text(text, encoding="utf-8")

# Explicit tool policy: visual follow-ups should look again, today brief/calculator/actions should use tools.
policy = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/NexusAgentPolicy.kt"
replace_once(
    policy,
    "                        \"about what was seen, say so plainly and offer to look again.\\n\",\n",
    "                        \"about what was seen, say so plainly and offer to look again. In an ongoing conversation, \" +\n"
    "                        \"deictic follow-ups such as 'what is this', 'and this part', 'а вот это' or 'эта деталь' \" +\n"
    "                        \"refer to the wearer's current view unless they explicitly refer to the previous photo; \" +\n"
    "                        \"capture a fresh photo for those follow-ups.\\n\",\n",
)
replace_once(
    policy,
    "            if (calendarToolsAvailable) {\n",
    "            if (TODAY_BRIEF_TOOL_NAME in availableToolNames) {\n"
    "                append(\"- For 'what do I have today' / 'что у меня сегодня', call today_brief and summarize only returned events/reminders.\\n\")\n"
    "            }\n"
    "            if (ENGINEERING_CALCULATOR_TOOL_NAME in availableToolNames) {\n"
    "                append(\"- Use engineering_calculator for supported construction calculations instead of mental arithmetic; preserve returned units.\\n\")\n"
    "            }\n"
    "            if (SEND_SMS_CONTACT_TOOL_NAME in availableToolNames) {\n"
    "                append(\"- Send SMS only on an explicit user request and never claim it was sent unless the tool says sent=true.\\n\")\n"
    "            }\n"
    "            if (LIST_NOTIFICATIONS_TOOL_NAME in availableToolNames) {\n"
    "                append(\"- Use list_recent_notifications when the user asks what arrived, asks about the latest notification, or wants it explained/triaged.\\n\")\n"
    "            }\n"
    "            if (REPLY_NOTIFICATION_TOOL_NAME in availableToolNames) {\n"
    "                append(\"- Reply to a notification only after an explicit user request; use the exact notification key returned by list_recent_notifications.\\n\")\n"
    "            }\n"
    "            if (calendarToolsAvailable) {\n",
)

# Expose all non-meeting phone tools to Hermes text fallback too.
assistant_tool = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantTool.kt"
replace_once(
    assistant_tool,
    "    SEND_SMS_CONTACT_TOOL_NAME,\n)",
    "    SEND_SMS_CONTACT_TOOL_NAME,\n"
    "    TODAY_BRIEF_TOOL_NAME,\n"
    "    ENGINEERING_CALCULATOR_TOOL_NAME,\n"
    "    LIST_NOTIFICATIONS_TOOL_NAME,\n"
    "    REPLY_NOTIFICATION_TOOL_NAME,\n"
    ")",
)

policy_test = "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/NexusAgentPolicyTest.kt"
replace_once(
    policy_test,
    "                CALL_CONTACT_TOOL_NAME,\n"
    "            ),\n"
    "            HERMES_TEXT_TOOL_NAMES,\n",
    "                CALL_CONTACT_TOOL_NAME,\n"
    "                REDIAL_LAST_ASSISTANT_CALL_TOOL_NAME,\n"
    "                SEND_SMS_CONTACT_TOOL_NAME,\n"
    "                TODAY_BRIEF_TOOL_NAME,\n"
    "                ENGINEERING_CALCULATOR_TOOL_NAME,\n"
    "                LIST_NOTIFICATIONS_TOOL_NAME,\n"
    "                REPLY_NOTIFICATION_TOOL_NAME,\n"
    "            ),\n"
    "            HERMES_TEXT_TOOL_NAMES,\n",
)
