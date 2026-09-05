#!/usr/bin/env python3
"""Make Meetings a stateless Start/Stop UI over the durable glasses-side recorder state."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"
GRADLE = ROOT / "plugins/assistant/build.gradle.kts"
MANIFEST = ROOT / "plugins/assistant/src/main/AndroidManifest.xml"

service = r'''package com.anezium.rokidbus.plugin.assistant

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import java.util.UUID

/**
 * Meetings 2.0.1 is deliberately stateless across HUD sessions.
 *
 * Every open asks NEXUS Glasses for the durable Rokid recorder command state, then exposes exactly
 * one legal action: Start when inactive, Stop when active. Rokid owns all audio and file handling.
 */
class AssistantPluginService : NexusPluginService() {
    private val handler = Handler(Looper.getMainLooper())

    private var noticeVisible = false
    private var recordingState: Boolean? = null
    private var pendingId: String? = null
    private var pendingAction: String? = null
    private var pendingTimeout: Runnable? = null
    private var transientMessage: String? = null

    override fun onNexusOpen() {
        noticeVisible = false
        clearPending()
        recordingState = null
        transientMessage = null
        request("status")
    }

    override fun onNexusClose() {
        // HUD lifecycle never owns the native recorder. Drop only transient UI/request state.
        noticeVisible = false
        clearPending()
        recordingState = null
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusNoticeAction(id: String) {
        when (id) {
            ACTION_START -> request("start")
            ACTION_STOP -> request("stop")
        }
    }

    override fun onNexusNoticeClosed(reason: NexusNoticeCloseReason) {
        noticeVisible = false
        clearPending()
        recordingState = null
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        if (path != RESULT_PATH || id != pendingId) return
        val action = pendingAction ?: return
        clearPending()

        if (!payload.optBoolean("accepted", false) || !payload.optBoolean("confirmed", false)) {
            transientMessage = when (action) {
                "status" -> "Не удалось получить состояние штатного рекордера Rokid"
                "start" -> "Rokid не принял команду запуска"
                else -> "Rokid не принял команду остановки"
            }
            showControl()
            return
        }

        recordingState = payload.optBoolean("recording", false)
        transientMessage = when (action) {
            "start" -> if (recordingState == true) "Запись запущена" else "Запуск не подтверждён состоянием Rokid"
            "stop" -> if (recordingState == false) "Запись остановлена" else "Остановка не подтверждена состоянием Rokid"
            else -> null
        }
        showControl()
    }

    override fun onDestroy() {
        clearPending()
        super.onDestroy()
    }

    private fun request(action: String) {
        if (pendingId != null) return

        // Never send an action that contradicts the state we just received from the glasses.
        if (action == "start" && recordingState == true) {
            showControl()
            return
        }
        if (action == "stop" && recordingState == false) {
            showControl()
            return
        }

        val id = UUID.randomUUID().toString()
        pendingId = id
        pendingAction = action
        transientMessage = null
        showControl()

        val sent = nexusClient?.send(
            REQUEST_PATH,
            id,
            JSONObject().put("action", action),
        ) == true
        if (!sent) {
            clearPending()
            transientMessage = "Нет связи с NEXUS Glasses"
            showControl()
            return
        }

        val timeout = Runnable {
            if (pendingId != id) return@Runnable
            clearPending()
            transientMessage = when (action) {
                "status" -> "Нет ответа состояния от NEXUS Glasses"
                "start" -> "Нет ответа на запуск"
                else -> "Нет ответа на остановку"
            }
            showControl()
        }
        pendingTimeout = timeout
        handler.postDelayed(timeout, REQUEST_TIMEOUT_MS)
    }

    private fun clearPending() {
        pendingTimeout?.let(handler::removeCallbacks)
        pendingTimeout = null
        pendingId = null
        pendingAction = null
    }

    private fun showControl() {
        val client = nexusClient ?: return
        val pending = pendingAction
        val message = transientMessage
        val lines: List<String>
        val actions: List<NexusNoticeAction>

        when {
            pending == "status" -> {
                lines = listOf("Проверяю состояние штатного рекордера Rokid…")
                actions = emptyList()
            }
            pending == "start" -> {
                lines = listOf("Запускаю штатную запись Rokid…")
                actions = emptyList()
            }
            pending == "stop" -> {
                lines = listOf("Останавливаю штатную запись Rokid…")
                actions = emptyList()
            }
            recordingState == true -> {
                lines = buildList {
                    add("● Штатная запись Rokid идёт")
                    message?.let { add(it) }
                }
                actions = listOf(
                    NexusNoticeAction(
                        id = ACTION_STOP,
                        glyph = "stop",
                        label = "Остановить",
                    ),
                )
            }
            recordingState == false -> {
                lines = buildList {
                    add("Штатная аудиозапись Rokid")
                    message?.let { add(it) }
                }
                actions = listOf(
                    NexusNoticeAction(
                        id = ACTION_START,
                        glyph = "record",
                        label = "Начать",
                    ),
                )
            }
            else -> {
                lines = listOf(message ?: "Состояние штатного рекордера Rokid неизвестно")
                // Unknown state is intentionally non-actionable. Reopen Meetings to query again;
                // never guess Start/Stop and risk toggling a live native recorder.
                actions = emptyList()
            }
        }

        val result = if (noticeVisible) {
            client.updateNotice(
                NexusNoticeUpdate(
                    title = "Meetings",
                    lines = lines,
                    actions = actions,
                    ttlMs = NOTICE_TTL_MS,
                ),
            )
        } else {
            client.showNotice(
                NexusNotice(
                    title = "Meetings",
                    lines = lines,
                    actions = actions,
                    ttlMs = NOTICE_TTL_MS,
                    wakeDisplay = false,
                ),
            )
        }
        noticeVisible = result == NexusSdkResult.SENT
        transientMessage = null
    }

    companion object {
        private const val REQUEST_PATH = "/plugin/assistant/rokid-recording/request"
        private const val RESULT_PATH = "/plugin/assistant/rokid-recording/result"
        private const val ACTION_START = "meetings_start"
        private const val ACTION_STOP = "meetings_stop"
        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val NOTICE_TTL_MS = 30_000L
    }
}
'''
SERVICE.write_text(service, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
if gradle.count("versionCode = 27") != 1 or gradle.count('versionName = "2.0.0"') != 1:
    raise SystemExit("Expected generated Meetings 2.0.0 version markers exactly once")
gradle = gradle.replace("versionCode = 27", "versionCode = 28", 1)
gradle = gradle.replace('versionName = "2.0.0"', 'versionName = "2.0.1"', 1)
GRADLE.write_text(gradle, encoding="utf-8")

service_check = SERVICE.read_text(encoding="utf-8")
manifest_check = MANIFEST.read_text(encoding="utf-8")
for required in (
    'request("status")',
    'recordingState: Boolean? = null',
    'JSONObject().put("action", action)',
    'ACTION_STOP',
    'label = "Остановить"',
    'REQUEST_TIMEOUT_MS = 5_000L',
):
    if required not in service_check:
        raise SystemExit(f"Missing Meetings 2.0.1 marker: {required}")

for forbidden in (
    "getSharedPreferences",
    "KEY_ACTIVE",
    "KEY_STARTED_AT",
    "NexusAudioSession",
    "NexusSpeechSession",
    "meetingRecorder",
    "transcriber",
):
    if forbidden in service_check:
        raise SystemExit(f"Forbidden phone-side recorder state/runtime remains: {forbidden}")

if 'android:value="surfaces"' not in manifest_check:
    raise SystemExit("Meetings 2.0.1 must remain surfaces-only")
if "microphone" in manifest_check or "stt" in manifest_check or "tts" in manifest_check:
    raise SystemExit("Meetings 2.0.1 manifest still requests audio/AI capabilities")

print("Applied Meetings 2.0.1 glasses-authoritative native recorder UI.")
