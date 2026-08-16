from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected one match in {relative_path}, found {count}: {old[:160]!r}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def write(relative_path: str, content: str) -> None:
    path = ROOT / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


# ---------------------------------------------------------------------------
# 1.5.1: diagnostics only. Do not alter transport, retries, timing or glasses-side.

replace_once(
    "plugins/assistant/build.gradle.kts",
    '        versionCode = 10\n        versionName = "1.5.0"\n',
    '        versionCode = 11\n        versionName = "1.5.1"\n',
)

write(
    "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantDiagnostics.kt",
    r'''package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Passive diagnostics for the physical RV101 -> Nexus -> Assistant pipeline.
 *
 * This class intentionally does not reconnect, retry, change timers, touch CXR state,
 * or affect provider selection. It only records what the existing pipeline observed.
 */
internal class AssistantDiagnostics(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun begin(source: String) {
        val now = System.currentTimeMillis()
        val event = eventJson(now, "INVOKE", source)
        prefs.edit()
            .putLong(KEY_STARTED_AT, now)
            .putString(KEY_SOURCE, source)
            .putString(KEY_EVENTS, JSONArray().put(event).toString())
            .putString(KEY_LAST_STAGE, "INVOKE")
            .putString(KEY_LAST_DETAIL, source)
            .commit()
        Log.i(TAG, "INVOKE source=$source")
    }

    @Synchronized
    fun mark(stage: String, detail: String? = null) {
        val now = System.currentTimeMillis()
        val events = readEvents()
        events.put(eventJson(now, stage, detail))
        while (events.length() > MAX_EVENTS) {
            val trimmed = JSONArray()
            for (index in 1 until events.length()) trimmed.put(events.get(index))
            prefs.edit().putString(KEY_EVENTS, trimmed.toString()).commit()
            return markAfterTrim(stage, detail, now, trimmed)
        }
        prefs.edit()
            .putString(KEY_EVENTS, events.toString())
            .putString(KEY_LAST_STAGE, stage)
            .putString(KEY_LAST_DETAIL, detail.orEmpty())
            .commit()
        Log.i(TAG, buildLog(stage, detail))
    }

    @Synchronized
    private fun markAfterTrim(
        stage: String,
        detail: String?,
        now: Long,
        trimmed: JSONArray,
    ) {
        prefs.edit()
            .putString(KEY_EVENTS, trimmed.toString())
            .putString(KEY_LAST_STAGE, stage)
            .putString(KEY_LAST_DETAIL, detail.orEmpty())
            .commit()
        Log.i(TAG, buildLog(stage, detail))
    }

    @Synchronized
    fun summary(): String {
        val started = prefs.getLong(KEY_STARTED_AT, 0L)
        val source = prefs.getString(KEY_SOURCE, null)
        val lastStage = prefs.getString(KEY_LAST_STAGE, null)
        val events = readEvents()
        if (started <= 0L || lastStage.isNullOrBlank()) {
            return "No Assistant invocation recorded yet."
        }

        val lines = mutableListOf<String>()
        lines += "Last call: ${formatTime(started)}${source?.let { " · $it" }.orEmpty()}"
        lines += "Last stage: $lastStage"
        lines += ""
        for (index in 0 until events.length()) {
            val item = events.optJSONObject(index) ?: continue
            val time = item.optLong("time")
            val stage = item.optString("stage")
            val detail = item.optString("detail").takeIf(String::isNotBlank)
            lines += buildString {
                append(formatTime(time))
                append("  ")
                append(stage)
                if (detail != null) {
                    append(" · ")
                    append(detail.take(MAX_DETAIL_CHARS))
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun readEvents(): JSONArray = runCatching {
        JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun eventJson(time: Long, stage: String, detail: String?): JSONObject =
        JSONObject()
            .put("time", time)
            .put("stage", stage)
            .put("detail", detail.orEmpty())

    private fun formatTime(epochMs: Long): String =
        TIME_FORMAT.get().format(Date(epochMs))

    private fun buildLog(stage: String, detail: String?): String =
        if (detail.isNullOrBlank()) stage else "$stage detail=${detail.take(MAX_DETAIL_CHARS)}"

    private companion object {
        const val TAG = "AssistantDiagnostics"
        const val PREFS = "assistant_diagnostics"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_SOURCE = "source"
        const val KEY_EVENTS = "events"
        const val KEY_LAST_STAGE = "last_stage"
        const val KEY_LAST_DETAIL = "last_detail"
        const val MAX_EVENTS = 24
        const val MAX_DETAIL_CHARS = 120
        val TIME_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }
    }
}
''',
)

service = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"

replace_once(
    service,
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n',
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n'
    '    private val diagnostics by lazy { AssistantDiagnostics(applicationContext) }\n',
)

replace_once(
    service,
    '    override fun onNexusOpen() {\n'
    '        surface = nexusSurfaceSession(SURFACE_ID)\n',
    '    override fun onNexusOpen() {\n'
    '        diagnostics.mark("NEXUS_OPEN")\n'
    '        surface = nexusSurfaceSession(SURFACE_ID)\n',
)

replace_once(
    service,
    '    override fun onNexusClose() {\n'
    '        uiController.onClose()\n',
    '    override fun onNexusClose() {\n'
    '        diagnostics.mark("NEXUS_CLOSE")\n'
    '        uiController.onClose()\n',
)

replace_once(
    service,
    '    override fun onNexusLinkState(state: Int) {\n'
    '        currentLinkState = state\n'
    '    }\n',
    '    override fun onNexusLinkState(state: Int) {\n'
    '        currentLinkState = state\n'
    '        diagnostics.mark("LINK_STATE", state.toString())\n'
    '    }\n',
)

replace_once(
    service,
    '        if (active) {\n'
    '            if (captureTriggerGate.claimButtonStart()) startCaptureOnce()\n'
    '        } else {\n',
    '        if (active) {\n'
    '            if (captureTriggerGate.claimButtonStart()) {\n'
    '                diagnostics.begin("glasses_button")\n'
    '                startCaptureOnce()\n'
    '            }\n'
    '        } else {\n',
)

replace_once(
    service,
    '        val gestureId = payload.optString("gestureId")\n'
    '        if (!captureTriggerGate.claimGestureOpen(gestureId)) return\n'
    '        uiController.cancelLauncherHint()\n',
    '        val gestureId = payload.optString("gestureId")\n'
    '        if (!captureTriggerGate.claimGestureOpen(gestureId)) return\n'
    '        diagnostics.begin("ai_assist_open")\n'
    '        uiController.cancelLauncherHint()\n',
)

replace_once(
    service,
    '    private fun beginCapture() {\n'
    '        uiController.beginGestureFlow()\n',
    '    private fun beginCapture() {\n'
    '        diagnostics.mark("CAPTURE_BEGIN")\n'
    '        uiController.beginGestureFlow()\n',
)

replace_once(
    service,
    '            override fun onSpeechStarted(realtime: Boolean) {\n'
    '                if (generation != captureGeneration || !captureActive) {\n',
    '            override fun onSpeechStarted(realtime: Boolean) {\n'
    '                diagnostics.mark("STT_STARTED", "realtime=$realtime")\n'
    '                if (generation != captureGeneration || !captureActive) {\n',
)

replace_once(
    service,
    '                val transcript = normalizeTranscript(text)\n'
    '                if (transcript.isEmpty()) return\n'
    '                finalDelivered = true\n',
    '                val transcript = normalizeTranscript(text)\n'
    '                if (transcript.isEmpty()) return\n'
    '                diagnostics.mark("STT_FINAL", "chars=${transcript.length}")\n'
    '                finalDelivered = true\n',
)

replace_once(
    service,
    '                if (generation != captureGeneration) return\n'
    '                captureActive = false\n'
    '                if (finalDelivered) return\n',
    '                if (generation != captureGeneration) return\n'
    '                captureActive = false\n'
    '                diagnostics.mark("STT_STOP", "reason=$reason error=${error?.kind ?: "none"}")\n'
    '                if (finalDelivered) return\n',
)

replace_once(
    service,
    '        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE\n'
    '        if (result != NexusSdkResult.SENT) {\n',
    '        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE\n'
    '        diagnostics.mark("STT_START_RESULT", result.toString())\n'
    '        if (result != NexusSdkResult.SENT) {\n',
)

replace_once(
    service,
    '    private fun startFallbackCapture(generation: Long) {\n'
    '        if (generation != captureGeneration || captureActive) return\n',
    '    private fun startFallbackCapture(generation: Long) {\n'
    '        diagnostics.mark("RAW_AUDIO_FALLBACK")\n'
    '        if (generation != captureGeneration || captureActive) return\n',
)

replace_once(
    service,
    '            val transcript = transcriber.transcribe(pcm, format).trim()\n'
    '            if (transcript.isEmpty()) {\n',
    '            diagnostics.mark("FALLBACK_TRANSCRIBE_START", "bytes=${pcm.size}")\n'
    '            val transcript = transcriber.transcribe(pcm, format).trim()\n'
    '            if (transcript.isEmpty()) {\n',
)

replace_once(
    service,
    '            streamAssistantAnswer(transcript)\n'
    '        }\n'
    '    }\n\n'
    '    private fun launchAssistantPipeline(transcript: String) {\n',
    '            diagnostics.mark("FALLBACK_STT_FINAL", "chars=${transcript.length}")\n'
    '            streamAssistantAnswer(transcript)\n'
    '        }\n'
    '    }\n\n'
    '    private fun launchAssistantPipeline(transcript: String) {\n',
)

replace_once(
    service,
    '        launchPipeline {\n'
    '            streamAssistantAnswer(normalized)\n'
    '        }\n'
    '    }\n\n'
    '    private fun launchPipeline(block: suspend () -> Unit) {\n',
    '        diagnostics.mark("PIPELINE_START", "chars=${normalized.length}")\n'
    '        launchPipeline {\n'
    '            streamAssistantAnswer(normalized)\n'
    '        }\n'
    '    }\n\n'
    '    private fun launchPipeline(block: suspend () -> Unit) {\n',
)

replace_once(
    service,
    '            } catch (error: Throwable) {\n'
    '                Log.w(TAG, "Assistant pipeline failed: ${error.javaClass.simpleName}")\n'
    '                showError(error.conciseProviderMessage("Request failed. Try again."))\n',
    '            } catch (error: Throwable) {\n'
    '                diagnostics.mark("PIPELINE_EXCEPTION", error.javaClass.simpleName)\n'
    '                Log.w(TAG, "Assistant pipeline failed: ${error.javaClass.simpleName}")\n'
    '                showError(error.conciseProviderMessage("Request failed. Try again."))\n',
)

replace_once(
    service,
    '        val providerId = selectedProviderId()\n'
    '        uiController.showTransient("Thinking…")\n',
    '        val providerId = selectedProviderId()\n'
    '        diagnostics.mark("AI_REQUEST", providerId)\n'
    '        uiController.showTransient("Thinking…")\n',
)

replace_once(
    service,
    '        var failed = false\n'
    '        var finalAnswer: String? = null\n'
    '        try {\n',
    '        var failed = false\n'
    '        var finalAnswer: String? = null\n'
    '        var firstTokenLogged = false\n'
    '        try {\n',
)

replace_once(
    service,
    '                    is AiProviderEvent.Started -> Unit\n',
    '                    is AiProviderEvent.Started -> diagnostics.mark("AI_STARTED")\n',
)

replace_once(
    service,
    '                    is AiProviderEvent.TextDelta -> {\n'
    '                        answer.append(event.delta)\n',
    '                    is AiProviderEvent.TextDelta -> {\n'
    '                        if (!firstTokenLogged) {\n'
    '                            firstTokenLogged = true\n'
    '                            diagnostics.mark("AI_FIRST_TOKEN")\n'
    '                        }\n'
    '                        answer.append(event.delta)\n',
)

replace_once(
    service,
    '                    is AiProviderEvent.MessageDone -> {\n'
    '                        completed = true\n',
    '                    is AiProviderEvent.MessageDone -> {\n'
    '                        completed = true\n'
    '                        diagnostics.mark("AI_DONE", "chars=${event.message.content.length}")\n',
)

replace_once(
    service,
    '                    is AiProviderEvent.Failed -> {\n'
    '                        completed = true\n'
    '                        failed = true\n',
    '                    is AiProviderEvent.Failed -> {\n'
    '                        completed = true\n'
    '                        failed = true\n'
    '                        diagnostics.mark("AI_FAILED", event.message.take(120))\n',
)

replace_once(
    service,
    '    private fun showAnswer(text: String) {\n'
    '        val plain = stripHudMarkdown(text)\n',
    '    private fun showAnswer(text: String) {\n'
    '        diagnostics.mark("HUD_RENDER", "chars=${text.length}")\n'
    '        val plain = stripHudMarkdown(text)\n',
)

# ---------------------------------------------------------------------------
# Read-only diagnostics card in Assistant settings.

settings = "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"

replace_once(
    settings,
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n',
    '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n'
    '    private val diagnostics by lazy { AssistantDiagnostics(applicationContext) }\n',
)

replace_once(
    settings,
    '    private lateinit var refreshButton: View\n',
    '    private lateinit var refreshButton: View\n'
    '    private lateinit var diagnosticsStatus: TextView\n',
)

replace_once(
    settings,
    '        renderCalendarAccess()\n'
    '        maybeDetectHermes(ProviderCatalog.custom)\n',
    '        renderCalendarAccess()\n'
    '        renderDiagnostics()\n'
    '        maybeDetectHermes(ProviderCatalog.custom)\n',
)

replace_once(
    settings,
    '            addView(calendarAccessSlot, NexusUi.block())\n'
    '            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n'
    '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Plugin"), NexusUi.block())\n',
    '            addView(calendarAccessSlot, NexusUi.block())\n'
    '            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n'
    '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Diagnostics"), NexusUi.block())\n'
    '            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))\n'
    '            diagnosticsStatus = NexusUi.cardBody(\n'
    '                this@AssistantSettingsActivity,\n'
    '                diagnostics.summary(),\n'
    '            )\n'
    '            addView(diagnosticsStatus, NexusUi.block())\n'
    '            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n'
    '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Plugin"), NexusUi.block())\n',
)

replace_once(
    settings,
    '    private fun buildUi() {\n',
    '    private fun renderDiagnostics() {\n'
    '        if (::diagnosticsStatus.isInitialized) {\n'
    '            diagnosticsStatus.text = diagnostics.summary()\n'
    '        }\n'
    '    }\n\n'
    '    private fun buildUi() {\n',
)

# A tiny JVM-safe shape check so CI catches accidental removal of the recorder.
write(
    "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/AssistantDiagnosticsContractTest.kt",
    r'''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantDiagnosticsContractTest {
    @Test
    fun `diagnostic stage names stay stable for field testing`() {
        val stages = listOf(
            "INVOKE",
            "CAPTURE_BEGIN",
            "STT_STARTED",
            "STT_FINAL",
            "PIPELINE_START",
            "AI_REQUEST",
            "AI_STARTED",
            "AI_FIRST_TOKEN",
            "AI_DONE",
            "AI_FAILED",
            "HUD_RENDER",
        )
        assertEquals(11, stages.distinct().size)
    }
}
''',
)
