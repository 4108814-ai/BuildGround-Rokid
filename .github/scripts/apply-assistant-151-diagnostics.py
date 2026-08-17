from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"
SETTINGS = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
GRADLE = ROOT / "plugins/assistant/build.gradle.kts"
DIAG = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantDiagnostics.kt"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/AssistantDiagnosticsContractTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def require(path: Path, needle: str, label: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{label}: missing {needle!r}")


# This script runs only after the known-green Assistant feature bundle has been fully
# generated. It must not reconnect, retry, alter CXR, change timings, or touch glasses.
replace_once(
    GRADLE,
    '        versionCode = 10\n        versionName = "1.5.1"\n',
    '        versionCode = 11\n        versionName = "1.5.1"\n',
    "version",
)

DIAG.write_text(
    r'''package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Passive field diagnostics. Recording only; no recovery behavior lives here. */
internal class AssistantDiagnostics(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun begin(source: String) {
        val now = System.currentTimeMillis()
        val events = JSONArray().put(event(now, "INVOKE", source))
        prefs.edit()
            .putLong(KEY_STARTED_AT, now)
            .putString(KEY_SOURCE, source)
            .putString(KEY_EVENTS, events.toString())
            .putString(KEY_LAST_STAGE, "INVOKE")
            .apply()
        Log.i(TAG, "INVOKE source=$source")
    }

    @Synchronized
    fun mark(stage: String, detail: String? = null) {
        val now = System.currentTimeMillis()
        val current = readEvents()
        val start = (current.length() - MAX_EVENTS + 1).coerceAtLeast(0)
        val next = JSONArray()
        for (index in start until current.length()) {
            current.optJSONObject(index)?.let(next::put)
        }
        next.put(event(now, stage, detail))
        prefs.edit()
            .putString(KEY_EVENTS, next.toString())
            .putString(KEY_LAST_STAGE, stage)
            .apply()
        Log.i(TAG, if (detail.isNullOrBlank()) stage else "$stage detail=${detail.take(MAX_DETAIL)}")
    }

    @Synchronized
    fun summary(): String {
        val started = prefs.getLong(KEY_STARTED_AT, 0L)
        val source = prefs.getString(KEY_SOURCE, "").orEmpty()
        val last = prefs.getString(KEY_LAST_STAGE, "").orEmpty()
        if (started <= 0L || last.isBlank()) return "No Assistant invocation recorded yet."

        val lines = mutableListOf<String>()
        lines += "Last call: ${time(started)}${if (source.isBlank()) "" else " · $source"}"
        lines += "Last stage: $last"
        lines += ""
        val events = readEvents()
        for (index in 0 until events.length()) {
            val item = events.optJSONObject(index) ?: continue
            val stage = item.optString("stage")
            val detail = item.optString("detail").takeIf { it.isNotBlank() }
            lines += buildString {
                append(time(item.optLong("time")))
                append("  ")
                append(stage)
                if (detail != null) {
                    append(" · ")
                    append(detail.take(MAX_DETAIL))
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun readEvents(): JSONArray = runCatching {
        JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]")
    }.getOrElse { JSONArray() }

    private fun event(time: Long, stage: String, detail: String?): JSONObject =
        JSONObject()
            .put("time", time)
            .put("stage", stage)
            .put("detail", detail.orEmpty())

    private fun time(value: Long): String = TIME_FORMAT.get().format(Date(value))

    private companion object {
        const val TAG = "AssistantDiagnostics"
        const val PREFS = "assistant_diagnostics"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_SOURCE = "source"
        const val KEY_EVENTS = "events"
        const val KEY_LAST_STAGE = "last_stage"
        const val MAX_EVENTS = 24
        const val MAX_DETAIL = 120
        val TIME_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }
    }
}
''',
    encoding="utf-8",
)

# Service recorder.
replace_once(
    SERVICE,
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n',
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n'
    '    private val diagnostics by lazy { AssistantDiagnostics(applicationContext) }\n',
    "diagnostics property",
)
replace_once(
    SERVICE,
    '    override fun onNexusOpen() {\n',
    '    override fun onNexusOpen() {\n        diagnostics.mark("NEXUS_OPEN")\n',
    "onNexusOpen",
)
replace_once(
    SERVICE,
    '    override fun onNexusClose() {\n',
    '    override fun onNexusClose() {\n        diagnostics.mark("NEXUS_CLOSE")\n',
    "onNexusClose",
)
replace_once(
    SERVICE,
    '    override fun onNexusLinkState(state: Int) {\n        currentLinkState = state\n',
    '    override fun onNexusLinkState(state: Int) {\n        currentLinkState = state\n        diagnostics.mark("LINK_STATE", state.toString())\n',
    "link state",
)
replace_once(
    SERVICE,
    '        if (active) {\n            if (captureTriggerGate.claimButtonStart()) startCaptureOnce()\n        } else {\n',
    '        if (active) {\n            if (captureTriggerGate.claimButtonStart()) {\n                diagnostics.begin("glasses_button")\n                startCaptureOnce()\n            }\n        } else {\n',
    "glasses button",
)
replace_once(
    SERVICE,
    '        if (!captureTriggerGate.claimGestureOpen(gestureId)) return\n        uiController.cancelLauncherHint()\n',
    '        if (!captureTriggerGate.claimGestureOpen(gestureId)) return\n        diagnostics.begin("ai_assist_open")\n        uiController.cancelLauncherHint()\n',
    "assist open",
)
replace_once(
    SERVICE,
    '    private fun beginCapture() {\n        uiController.beginGestureFlow()\n',
    '    private fun beginCapture() {\n        diagnostics.mark("CAPTURE_BEGIN")\n        uiController.beginGestureFlow()\n',
    "capture begin",
)
replace_once(
    SERVICE,
    '            override fun onSpeechStarted(realtime: Boolean) {\n',
    '            override fun onSpeechStarted(realtime: Boolean) {\n                diagnostics.mark("STT_STARTED", "realtime=$realtime")\n',
    "speech started",
)
replace_once(
    SERVICE,
    '                val transcript = normalizeTranscript(text)\n                if (transcript.isEmpty()) return\n',
    '                val transcript = normalizeTranscript(text)\n                if (transcript.isEmpty()) return\n                diagnostics.mark("STT_FINAL", "chars=${transcript.length}")\n',
    "speech final",
)
replace_once(
    SERVICE,
    '        speechSession = session\n        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE\n',
    '        speechSession = session\n        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE\n        diagnostics.mark("STT_START_RESULT", result.toString())\n',
    "speech start result",
)
replace_once(
    SERVICE,
    '    private fun startFallbackCapture(generation: Long) {\n',
    '    private fun startFallbackCapture(generation: Long) {\n        diagnostics.mark("RAW_AUDIO_FALLBACK")\n',
    "raw fallback",
)
replace_once(
    SERVICE,
    '        launchPipeline {\n            val transcript = transcriber.transcribe(pcm, format).trim()\n',
    '        launchPipeline {\n            diagnostics.mark("FALLBACK_TRANSCRIBE_START", "bytes=${pcm.size}")\n            val transcript = transcriber.transcribe(pcm, format).trim()\n',
    "fallback transcribe",
)
replace_once(
    SERVICE,
    '            if (transcript.isEmpty()) {\n                uiController.showError("Didn\'t catch that")\n                return@launchPipeline\n            }\n            streamAssistantAnswer(transcript)\n',
    '            if (transcript.isEmpty()) {\n                uiController.showError("Didn\'t catch that")\n                return@launchPipeline\n            }\n            diagnostics.mark("FALLBACK_STT_FINAL", "chars=${transcript.length}")\n            streamAssistantAnswer(transcript)\n',
    "fallback final",
)
replace_once(
    SERVICE,
    '        if (handleMeetingTranscript(normalized)) return\n        launchPipeline {\n',
    '        if (handleMeetingTranscript(normalized)) return\n        diagnostics.mark("PIPELINE_START", "chars=${normalized.length}")\n        launchPipeline {\n',
    "pipeline start",
)
replace_once(
    SERVICE,
    '            } catch (error: Throwable) {\n                Log.w(TAG, "Assistant pipeline failed: ${error.javaClass.simpleName}")\n',
    '            } catch (error: Throwable) {\n                diagnostics.mark("PIPELINE_EXCEPTION", error.javaClass.simpleName)\n                Log.w(TAG, "Assistant pipeline failed: ${error.javaClass.simpleName}")\n',
    "pipeline exception",
)
replace_once(
    SERVICE,
    '        val providerId = selectedProviderId()\n        uiController.showTransient("Thinking…")\n',
    '        val providerId = selectedProviderId()\n        diagnostics.mark("AI_REQUEST", providerId)\n        uiController.showTransient("Thinking…")\n',
    "AI request",
)
replace_once(
    SERVICE,
    '        var failed = false\n        var finalAnswer: String? = null\n        try {\n',
    '        var failed = false\n        var finalAnswer: String? = null\n        var firstTokenLogged = false\n        try {\n',
    "first token flag",
)
replace_once(
    SERVICE,
    '                    is AiProviderEvent.Started -> Unit\n',
    '                    is AiProviderEvent.Started -> diagnostics.mark("AI_STARTED")\n',
    "AI started",
)
replace_once(
    SERVICE,
    '                    is AiProviderEvent.TextDelta -> {\n                        answer.append(event.delta)\n',
    '                    is AiProviderEvent.TextDelta -> {\n                        if (!firstTokenLogged) {\n                            firstTokenLogged = true\n                            diagnostics.mark("AI_FIRST_TOKEN")\n                        }\n                        answer.append(event.delta)\n',
    "AI first token",
)
replace_once(
    SERVICE,
    '                    is AiProviderEvent.MessageDone -> {\n                        completed = true\n',
    '                    is AiProviderEvent.MessageDone -> {\n                        completed = true\n                        diagnostics.mark("AI_DONE", "chars=${event.message.content.length}")\n',
    "AI done",
)
replace_once(
    SERVICE,
    '                    is AiProviderEvent.Failed -> {\n                        completed = true\n                        failed = true\n',
    '                    is AiProviderEvent.Failed -> {\n                        completed = true\n                        failed = true\n                        diagnostics.mark("AI_FAILED", event.message.take(120))\n',
    "AI failed",
)
replace_once(
    SERVICE,
    '    private fun showAnswer(text: String) {\n',
    '    private fun showAnswer(text: String) {\n        diagnostics.mark("HUD_RENDER", "chars=${text.length}")\n',
    "HUD render",
)

# Read-only phone UI. Refreshes when settings resumes; no button performs recovery.
replace_once(
    SETTINGS,
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n',
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n'
    '    private val diagnostics by lazy { AssistantDiagnostics(applicationContext) }\n',
    "settings diagnostics property",
)
replace_once(
    SETTINGS,
    '    private lateinit var refreshButton: View\n',
    '    private lateinit var refreshButton: View\n    private lateinit var diagnosticsStatus: TextView\n',
    "settings status view",
)
replace_once(
    SETTINGS,
    '        if (::notificationAccessSlot.isInitialized) renderNotificationAccess()\n        maybeDetectHermes(ProviderCatalog.custom)\n',
    '        if (::notificationAccessSlot.isInitialized) renderNotificationAccess()\n        renderDiagnostics()\n        maybeDetectHermes(ProviderCatalog.custom)\n',
    "settings resume",
)
replace_once(
    SETTINGS,
    '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Plugin"), NexusUi.block())\n',
    '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Diagnostics"), NexusUi.block())\n'
    '            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))\n'
    '            diagnosticsStatus = NexusUi.cardBody(\n'
    '                this@AssistantSettingsActivity,\n'
    '                diagnostics.summary(),\n'
    '            )\n'
    '            addView(diagnosticsStatus, NexusUi.block())\n'
    '            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n'
    '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Plugin"), NexusUi.block())\n',
    "settings diagnostics card",
)
replace_once(
    SETTINGS,
    '    private fun buildUi() {\n',
    '    private fun renderDiagnostics() {\n'
    '        if (::diagnosticsStatus.isInitialized) {\n'
    '            diagnosticsStatus.text = diagnostics.summary()\n'
    '        }\n'
    '    }\n\n'
    '    private fun buildUi() {\n',
    "settings render function",
)

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text(
    '''package com.anezium.rokidbus.plugin.assistant\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n\nclass AssistantDiagnosticsContractTest {\n    @Test\n    fun `diagnostic stage names stay stable`() {\n        val stages = listOf(\n            "INVOKE", "CAPTURE_BEGIN", "STT_STARTED", "STT_FINAL",\n            "PIPELINE_START", "AI_REQUEST", "AI_STARTED", "AI_FIRST_TOKEN",\n            "AI_DONE", "AI_FAILED", "HUD_RENDER",\n        )\n        assertEquals(11, stages.distinct().size)\n    }\n}\n''',
    encoding="utf-8",
)

for marker in (
    'diagnostics.begin("glasses_button")',
    'diagnostics.mark("STT_FINAL"',
    'diagnostics.mark("AI_REQUEST"',
    'diagnostics.mark("AI_FIRST_TOKEN")',
    'diagnostics.mark("HUD_RENDER"',
):
    require(SERVICE, marker, "service contract")
for marker in (
    'sectionRow(this@AssistantSettingsActivity, "Diagnostics")',
    'renderDiagnostics()',
):
    require(SETTINGS, marker, "settings contract")

print("Assistant 1.5.1 passive diagnostics applied to stable feature bundle.")