package com.anezium.rokidbus.glasses

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
 * Thin control bridge to Rokid's stock audio recorder.
 *
 * NEXUS never captures or stores audio here. It only asks the persistent Rokid
 * MasterAssistService to start/stop its own audio recording pipeline. Rokid remains
 * responsible for microphones, finalisation and its existing phone delivery/storage path.
 */
object RokidNativeRecordingController {
    const val REQUEST_PATH = "/plugin/assistant/rokid-recording/request"
    const val RESULT_PATH = "/plugin/assistant/rokid-recording/result"

    private const val TAG = "RokidNativeRecording"
    private const val SERVICE_PACKAGE = "com.rokid.os.sprite.assistserver"
    private const val SERVICE_CLASS = "com.rokid.os.sprite.assist.MasterAssistService"
    private const val CMD_START = "cmd_start_audio_record"
    private const val CMD_STOP = "cmd_stop_audio_record"
    private const val NATIVE_RESULT_AUDIO = "result_audio_record"
    private const val AUDIO_OPEN_TYPE = "audio_no_ui"
    private const val NATIVE_RESULT_TIMEOUT_MS = 10_000L

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
    private var inFlight: Request? = null
    private var timeoutRunnable: Runnable? = null
    @Volatile private var confirmedRecording: Boolean? = null

    private val client = object : IAssistClient.Stub() {
        override fun onRegisterResult(resultJson: String?) {
            Log.i(TAG, "native register result=${resultJson?.take(240)}")
        }

        override fun onMessageReceive(messageJson: String?): Boolean {
            val raw = messageJson.orEmpty()
            val message = runCatching { JSONObject(raw) }.getOrNull()
            val type = message?.optString("type").orEmpty()
            if (type != NATIVE_RESULT_AUDIO) {
                // Do not consume unrelated Rokid events: native OS behaviour remains untouched.
                return false
            }

            synchronized(lock) {
                val request = inFlight
                if (request == null) {
                    Log.i(TAG, "unsolicited native audio result=${raw.take(240)}")
                    return false
                }
                cancelTimeoutLocked()
                inFlight = null

                // result_audio_record is the stock service's acknowledgement for both native
                // start/stop recorder commands. We deliberately do not infer file paths or audio
                // ownership from its payload: Rokid keeps that entire workflow.
                confirmedRecording = request.action == "start"
                request.reply(
                    JSONObject()
                        .put("action", request.action)
                        .put("accepted", true)
                        .put("confirmed", true)
                        .put("recording", confirmedRecording)
                        .put("phase", "native_result")
                        .put("owner", "rokid")
                        .put("native", message ?: JSONObject().put("raw", raw.take(2_000))),
                )
                dispatchNextLocked()
            }
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
                failInFlightLocked("ROKID_RECORDING_SERVICE_DISCONNECTED")
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) {
                server = null
                registered = false
                binding = false
                failInFlightLocked("ROKID_RECORDING_SERVICE_DIED")
                if (queued.isNotEmpty()) bindLocked()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(lock) {
                server = null
                registered = false
                binding = false
                failInFlightLocked("ROKID_RECORDING_NULL_BINDING")
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
        if (action != "start" && action != "stop") return false

        synchronized(lock) {
            applicationContext = context.applicationContext
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
        if (inFlight != null) return
        val current = server ?: return
        if (!registered) {
            registerLocked(current)
            if (!registered) return
        }
        val context = applicationContext ?: return
        if (queued.isEmpty()) return
        val request = queued.removeFirst()
        val command = if (request.action == "start") CMD_START else CMD_STOP
        val json = JSONObject()
            .put("type", command)
            .put(
                "data",
                JSONObject().put("audioOpenType", AUDIO_OPEN_TYPE),
            )
            .toString()

        // Arm correlation before the Binder call. Some firmware builds may emit the result from
        // inside controlMsgJson(), so setting inFlight afterwards would lose a synchronous ack.
        inFlight = request
        request.reply(
            JSONObject()
                .put("action", request.action)
                .put("accepted", true)
                .put("confirmed", false)
                .put("phase", "dispatched")
                .put("owner", "rokid"),
        )
        scheduleTimeoutLocked(request)

        val sent = runCatching {
            current.controlMsgJson(context.packageName, json)
        }
        if (sent.isFailure) {
            // If a synchronous native callback already completed this request, do not overwrite it.
            if (inFlight !== request) return
            cancelTimeoutLocked()
            inFlight = null
            val error = sent.exceptionOrNull()
            Log.w(TAG, "native command failed action=${request.action}", error)
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", false)
                    .put("confirmed", false)
                    .put("phase", "error")
                    .put("error", "ROKID_RECORDING_COMMAND_FAILED")
                    .put("detail", error?.javaClass?.simpleName ?: "unknown"),
            )
            dispatchNextLocked()
        }
    }

    private fun scheduleTimeoutLocked(request: Request) {
        cancelTimeoutLocked()
        val timeout = Runnable {
            synchronized(lock) {
                if (inFlight !== request) return@synchronized
                inFlight = null
                request.reply(
                    JSONObject()
                        .put("action", request.action)
                        .put("accepted", false)
                        .put("confirmed", false)
                        .put("phase", "timeout")
                        .put("error", "ROKID_RECORDING_RESULT_TIMEOUT"),
                )
                dispatchNextLocked()
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, NATIVE_RESULT_TIMEOUT_MS)
    }

    private fun cancelTimeoutLocked() {
        timeoutRunnable?.let(mainHandler::removeCallbacks)
        timeoutRunnable = null
    }

    private fun failInFlightLocked(error: String) {
        cancelTimeoutLocked()
        val request = inFlight ?: return
        inFlight = null
        request.reply(
            JSONObject()
                .put("action", request.action)
                .put("accepted", false)
                .put("confirmed", false)
                .put("phase", "error")
                .put("error", error),
        )
    }

    private fun failQueuedLocked(error: String) {
        while (queued.isNotEmpty()) {
            val request = queued.removeFirst()
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", false)
                    .put("confirmed", false)
                    .put("phase", "error")
                    .put("error", error),
            )
        }
    }
}
