#!/usr/bin/env python3
"""Make the glasses bridge the single command-state authority for Rokid native recording."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"
CONTROLLER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidNativeRecordingController.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Runs after 1.4.18 + 1.4.20 + 1.4.21 patches.
replace_once(GLASSES_GRADLE, "versionCode = 10421", "versionCode = 10422", "versionCode")
replace_once(GLASSES_GRADLE, 'versionName = "1.4.21"', 'versionName = "1.4.22"', "versionName")

controller = r'''package com.anezium.rokidbus.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.rokid.os.sprite.assist.client.IAssistClient
import com.rokid.os.sprite.assist.server.IAssistServer
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Thin, deterministic control bridge to Rokid's stock recorder.
 *
 * Rokid still owns microphones, recording, finalisation and phone delivery. NEXUS owns only the
 * command state that Meetings needs in order to render Start versus Stop. That state lives on the
 * glasses, not in the transient phone plugin/HUD lifecycle.
 */
object RokidNativeRecordingController {
    const val REQUEST_PATH = "/plugin/assistant/rokid-recording/request"
    const val RESULT_PATH = "/plugin/assistant/rokid-recording/result"

    private const val TAG = "RokidNativeRecording"
    private const val SERVICE_PACKAGE = "com.rokid.os.sprite.assistserver"
    private const val SERVICE_CLASS = "com.rokid.os.sprite.assist.MasterAssistService"
    private const val CMD_START = "cmd_start_audio_record"
    private const val CMD_STOP = "cmd_stop_audio_record"
    private const val AUDIO_OPEN_TYPE = "audio_no_ui"
    private const val DISPLAY_SLEEP_DELAY_MS = 1_200L
    private const val PREFS = "nexus_rokid_native_recorder"
    private const val KEY_ACTIVE = "active"
    private const val KEY_CHANGED_AT = "changed_at"

    private data class Request(
        val action: String,
        val reply: (JSONObject) -> Unit,
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queued = ArrayDeque<Request>()
    private var applicationContext: Context? = null
    private var server: IAssistServer? = null
    private var binding = false
    private var registered = false
    @Volatile private var confirmedRecording: Boolean? = null

    /**
     * Native callbacks are intentionally observational only. Physical RV101 testing proved that
     * result_audio_record is not a reliable acknowledgement channel for our registered client.
     * Late callbacks must never complete a newer command or flip Meetings state.
     */
    private val client = object : IAssistClient.Stub() {
        override fun onRegisterResult(resultJson: String?) {
            Log.i(TAG, "native register result=${resultJson?.take(240)}")
        }

        override fun onMessageReceive(messageJson: String?): Boolean {
            Log.i(TAG, "native message observed=${messageJson?.take(240)}")
            return false
        }

        override fun onDataReceive(key: String?, param: String?, data: ByteArray?) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(lock) {
                binding = false
                server = IAssistServer.Stub.asInterface(binder)
                registered = false
                val current = server
                if (current == null) {
                    failQueuedLocked("ROKID_RECORDING_NULL_BINDER")
                    return
                }
                registerLocked(current)
                dispatchNextLocked()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                server = null
                registered = false
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) {
                server = null
                registered = false
                binding = false
                if (queued.isNotEmpty()) bindLocked()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(lock) {
                server = null
                registered = false
                binding = false
                failQueuedLocked("ROKID_RECORDING_NULL_BINDING")
            }
        }
    }

    fun handle(
        context: Context,
        payload: JSONObject,
        reply: (JSONObject) -> Unit,
    ): Boolean {
        val action = payload.optString("action").trim().lowercase()
        if (action != "start" && action != "stop" && action != "status") return false

        synchronized(lock) {
            applicationContext = context.applicationContext

            // Status never touches the stock recorder. It is a pure query of the durable glasses
            // command state, so every Meetings open can reconstruct Start/Stop without relying on
            // any previous phone-plugin process or HUD session.
            if (action == "status") {
                val active = readRecordingStateLocked()
                confirmedRecording = active
                reply(successPayload(action = action, recording = active, phase = "state"))
                return true
            }

            queued.addLast(Request(action, reply))
            if (server == null) {
                if (!binding) bindLocked()
            } else {
                dispatchNextLocked()
            }
        }
        return true
    }

    fun lastConfirmedRecordingState(): Boolean? = confirmedRecording

    private fun bindLocked() {
        val context = applicationContext ?: run {
            failQueuedLocked("ROKID_RECORDING_NO_CONTEXT")
            return
        }
        binding = true
        val intent = Intent().apply {
            component = ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)
        }
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            Log.w(TAG, "bind failed", error)
            false
        }
        if (!bound) {
            binding = false
            failQueuedLocked("ROKID_RECORDING_SERVICE_UNAVAILABLE")
        }
    }

    private fun registerLocked(current: IAssistServer) {
        if (registered) return
        val context = applicationContext ?: return
        runCatching {
            current.registerClient(context.packageName, client)
        }.onSuccess {
            registered = true
            Log.i(TAG, "registered with stock Rokid MasterAssistService")
        }.onFailure { error ->
            Log.w(TAG, "native client registration failed", error)
            server = null
            registered = false
            failQueuedLocked("ROKID_RECORDING_REGISTER_FAILED")
        }
    }

    private fun dispatchNextLocked() {
        val current = server ?: return
        if (!registered) {
            registerLocked(current)
            if (!registered) return
        }
        val context = applicationContext ?: return
        if (queued.isEmpty()) return

        val request = queued.removeFirst()
        val before = readRecordingStateLocked()

        // Make Start/Stop idempotent. A duplicate Start while Rokid is already recording must not
        // reach firmware: physical testing showed that command/UI drift can otherwise turn a stale
        // "Start" tap into an unexpected recorder transition.
        if (request.action == "start" && before) {
            confirmedRecording = true
            request.reply(successPayload(request.action, recording = true, phase = "idempotent"))
            dispatchNextLocked()
            return
        }
        if (request.action == "stop" && !before) {
            confirmedRecording = false
            request.reply(successPayload(request.action, recording = false, phase = "idempotent"))
            dispatchNextLocked()
            return
        }

        val command = if (request.action == "start") CMD_START else CMD_STOP
        val json = JSONObject()
            .put("type", command)
            .put("data", JSONObject().put("audioOpenType", AUDIO_OPEN_TYPE))
            .toString()

        val sent = runCatching {
            current.controlMsgJson(context.packageName, json)
        }
        if (sent.isFailure) {
            val error = sent.exceptionOrNull()
            Log.w(TAG, "native command failed action=${request.action}", error)
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", false)
                    .put("confirmed", false)
                    .put("recording", before)
                    .put("phase", "error")
                    .put("owner", "rokid")
                    .put("error", "ROKID_RECORDING_COMMAND_FAILED")
                    .put("detail", error?.javaClass?.simpleName ?: "unknown"),
            )
            dispatchNextLocked()
            return
        }

        val after = request.action == "start"
        writeRecordingStateLocked(after)
        confirmedRecording = after
        request.reply(successPayload(request.action, recording = after, phase = "binder_dispatch"))
        if (after) scheduleDisplaySleep()
        dispatchNextLocked()
    }

    private fun successPayload(action: String, recording: Boolean, phase: String): JSONObject =
        JSONObject()
            .put("action", action)
            .put("accepted", true)
            .put("confirmed", true)
            .put("recording", recording)
            .put("phase", phase)
            .put("owner", "rokid")

    private fun readRecordingStateLocked(): Boolean {
        val context = applicationContext ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)
    }

    private fun writeRecordingStateLocked(active: Boolean) {
        val context = applicationContext ?: return
        // commit() is deliberate: status queried immediately after a HUD reopen must observe the
        // state before we tell the phone that the command completed.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()
    }

    private fun scheduleDisplaySleep() {
        mainHandler.postDelayed(
            {
                if (confirmedRecording != true) return@postDelayed
                val requested = RokidBusAccessibilityService.requestDisplaySleep()
                Log.i(TAG, "native recorder post-start display sleep requested=$requested")
            },
            DISPLAY_SLEEP_DELAY_MS,
        )
    }

    private fun failQueuedLocked(error: String) {
        val state = readRecordingStateLocked()
        while (queued.isNotEmpty()) {
            val request = queued.removeFirst()
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", false)
                    .put("confirmed", false)
                    .put("recording", state)
                    .put("phase", "error")
                    .put("owner", "rokid")
                    .put("error", error),
            )
        }
    }
}
'''
CONTROLLER.write_text(controller, encoding="utf-8")

text = CONTROLLER.read_text(encoding="utf-8")
gradle = GLASSES_GRADLE.read_text(encoding="utf-8")
for required in (
    'versionCode = 10422',
    'versionName = "1.4.22"',
):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.22 version marker: {required}")

for required in (
    'action != "start" && action != "stop" && action != "status"',
    'phase = "state"',
    'phase = "idempotent"',
    'PREFS = "nexus_rokid_native_recorder"',
    'current.controlMsgJson(context.packageName, json)',
    'RokidBusAccessibilityService.requestDisplaySleep()',
):
    if required not in text:
        raise SystemExit(f"Missing 1.4.22 controller marker: {required}")

for forbidden in (
    'scheduleTimeoutLocked',
    'inFlight',
    'NATIVE_RESULT_TIMEOUT_MS',
    'NATIVE_RESULT_AUDIO',
):
    if forbidden in text:
        raise SystemExit(f"Legacy callback-correlated state remains in 1.4.22: {forbidden}")

print("Applied BuildGround Nexus Glasses 1.4.22 recorder-state authority.")
