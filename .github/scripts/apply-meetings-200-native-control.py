#!/usr/bin/env python3
"""Collapse Meetings to a thin Start/Stop UI over Rokid's stock audio recorder."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
SERVICE = SRC / "AssistantPluginService.kt"
MANIFEST = ROOT / "plugins/assistant/src/main/AndroidManifest.xml"
GRADLE = ROOT / "plugins/assistant/build.gradle.kts"

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
 * Meetings 2.0 is intentionally only a remote control for Rokid's stock recorder.
 *
 * Rokid owns microphones, recording, file finalisation, phone sync and storage. Meetings does not
 * request audio, STT, transcription, protocol generation, archive storage or file export.
 */
class AssistantPluginService : NexusPluginService() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var noticeVisible = false
    private var pendingId: String? = null
    private var pendingAction: String? = null
    private var pendingTimeout: Runnable? = null
    private var timerTick: Runnable? = null
    private var transientMessage: String? = null

    override fun onNexusOpen() {
        noticeVisible = false
        transientMessage = null
        showControl()
        scheduleTimerIfNeeded()
    }

    override fun onNexusClose() {
        // Closing the HUD never changes Rokid's recording state.
        noticeVisible = false
        cancelTimer()
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusNoticeAction(id: String) {
        when (id) {
            ACTION_START -> requestNativeRecording("start")
            ACTION_STOP -> requestNativeRecording("stop")
        }
    }

    override fun onNexusNoticeClosed(reason: NexusNoticeCloseReason) {
        noticeVisible = false
        cancelTimer()
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        if (path != RESULT_PATH || id != pendingId) return
        val action = pendingAction ?: return

        // Nexus Glasses reports two phases: dispatch means only that the command reached the
        // stock service. The UI must NOT claim recording until Rokid itself returns
        // result_audio_record and the bridge marks the command confirmed.
        if (payload.optBoolean("accepted", false) && !payload.optBoolean("confirmed", false)) {
            return
        }

        clearPending()
        if (!payload.optBoolean("accepted", false) || !payload.optBoolean("confirmed", false)) {
            transientMessage = when (action) {
                "start" -> "Rokid не подтвердил запуск записи"
                else -> "Rokid не подтвердил остановку записи"
            }
            showControl()
            return
        }

        val recording = payload.optBoolean("recording", action == "start")
        if (recording) {
            prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .apply()
            transientMessage = "Штатная запись Rokid запущена"
        } else {
            prefs.edit()
                .putBoolean(KEY_ACTIVE, false)
                .remove(KEY_STARTED_AT)
                .apply()
            transientMessage = "Запись остановлена — дальше работает Rokid"
        }
        showControl()
        scheduleTimerIfNeeded()
    }

    override fun onDestroy() {
        clearPending()
        cancelTimer()
        super.onDestroy()
    }

    private fun requestNativeRecording(action: String) {
        if (pendingId != null) return
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        if (action == "start" && active) {
            showControl()
            return
        }
        if (action == "stop" && !active) {
            showControl()
            return
        }

        val id = UUID.randomUUID().toString()
        pendingId = id
        pendingAction = action
        transientMessage = if (action == "start") {
            "Запускаю штатную запись Rokid…"
        } else {
            "Останавливаю запись Rokid…"
        }
        cancelTimer()
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
            transientMessage = "Нет подтверждения от Rokid / NEXUS Glasses 1.4.20"
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
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        val pending = pendingAction
        val message = transientMessage
        val lines: List<String>
        val actions: List<NexusNoticeAction>

        when {
            pending == "start" -> {
                lines = listOf(message ?: "Запускаю штатную запись Rokid…")
                actions = emptyList()
            }
            pending == "stop" -> {
                lines = listOf(message ?: "Останавливаю запись Rokid…")
                actions = emptyList()
            }
            active -> {
                lines = buildList {
                    add("● Штатная запись Rokid активна")
                    elapsedLabel()?.let { add("Длительность: $it") }
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
            else -> {
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

    private fun scheduleTimerIfNeeded() {
        cancelTimer()
        if (!noticeVisible || pendingId != null || !prefs.getBoolean(KEY_ACTIVE, false)) return
        val tick = object : Runnable {
            override fun run() {
                if (!noticeVisible || pendingId != null || !prefs.getBoolean(KEY_ACTIVE, false)) {
                    timerTick = null
                    return
                }
                showControl()
                handler.postDelayed(this, TIMER_INTERVAL_MS)
            }
        }
        timerTick = tick
        handler.postDelayed(tick, TIMER_INTERVAL_MS)
    }

    private fun cancelTimer() {
        timerTick?.let(handler::removeCallbacks)
        timerTick = null
    }

    private fun elapsedLabel(): String? {
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        if (startedAt <= 0L) return null
        val totalSeconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    companion object {
        private const val REQUEST_PATH = "/plugin/assistant/rokid-recording/request"
        private const val RESULT_PATH = "/plugin/assistant/rokid-recording/result"
        private const val ACTION_START = "meetings_start"
        private const val ACTION_STOP = "meetings_stop"
        private const val PREFS = "meetings_native_rokid"
        private const val KEY_ACTIVE = "active"
        private const val KEY_STARTED_AT = "started_at"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val TIMER_INTERVAL_MS = 1_000L
        private const val NOTICE_TTL_MS = 30_000L
    }
}
'''
SERVICE.write_text(service, encoding="utf-8")

manifest = r'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <queries>
        <intent>
            <action android:name="com.anezium.rokidbus.action.HUB" />
        </intent>
    </queries>

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="Meetings"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Assistant">

        <service
            android:name=".AssistantPluginService"
            android:exported="true"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="glasses-session" />
            <intent-filter>
                <action android:name="com.anezium.rokidbus.action.PLUGIN" />
            </intent-filter>
            <meta-data
                android:name="com.anezium.rokidbus.plugin.ID"
                android:value="assistant" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.DISPLAY_NAME"
                android:value="Meetings" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.ICON"
                android:value="meetings" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.ICON_DRAWABLE"
                android:resource="@drawable/nexus_glyph_assistant" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.GLYPHS"
                android:resource="@array/nexus_glyphs" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.API_VERSION"
                android:value="3" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.CAPABILITIES"
                android:value="surfaces" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
                android:value="/plugin/assistant/rokid-recording" />
            <meta-data
                android:name="com.anezium.rokidbus.plugin.LAUNCHABLE"
                android:value="true" />
        </service>
    </application>
</manifest>
'''
MANIFEST.write_text(manifest, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
if gradle.count("versionCode = 11") != 1 or gradle.count('versionName = "1.5.1"') != 1:
    raise SystemExit("Expected generated Assistant 1.5.1 version markers exactly once")
gradle = gradle.replace("versionCode = 11", "versionCode = 27", 1)
gradle = gradle.replace('versionName = "1.5.1"', 'versionName = "2.0.0"', 1)
GRADLE.write_text(gradle, encoding="utf-8")

service_check = SERVICE.read_text(encoding="utf-8")
manifest_check = MANIFEST.read_text(encoding="utf-8")
for required in (
    'REQUEST_PATH = "/plugin/assistant/rokid-recording/request"',
    'RESULT_PATH = "/plugin/assistant/rokid-recording/result"',
    'payload.optBoolean("confirmed", false)',
    'REQUEST_TIMEOUT_MS = 15_000L',
    'JSONObject().put("action", action)',
    'android:value="surfaces"',
    'android:value="/plugin/assistant/rokid-recording"',
):
    if required not in service_check and required not in manifest_check:
        raise SystemExit(f"Missing Meetings 2.0 marker: {required}")

for forbidden in (
    "NexusAudioSession",
    "NexusSpeechSession",
    "OpenAiTranscriber",
    "AssistantMeetingStore",
    "meetingRecorder",
    "transcriber",
):
    if forbidden in service_check:
        raise SystemExit(f"Forbidden legacy Meetings runtime remains active: {forbidden}")

if "microphone" in manifest_check or "stt" in manifest_check or "tts" in manifest_check:
    raise SystemExit("Meetings 2.0 manifest still requests audio/AI capabilities")

print("Applied Meetings 2.0.0 thin Rokid native recording control runtime.")
