package com.anezium.rokidbus.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Thin bridge to Rokid's own persistent MasterAssistService.
 *
 * NEXUS never captures, copies, converts or stores audio here. It only asks the stock Rokid
 * media subsystem to start/stop its normal audio recorder. Rokid remains the sole owner of
 * microphones, recording finalisation and the existing phone delivery/storage path.
 */
object RokidNativeRecordingController {
    const val REQUEST_PATH = "/plugin/assistant/rokid-recording/request"
    const val RESULT_PATH = "/plugin/assistant/rokid-recording/result"

    private const val TAG = "RokidNativeRecording"
    private const val ROKID_PACKAGE = "com.rokid.os.sprite.assistserver"
    private const val ROKID_SERVICE_CLASS = "com.rokid.os.sprite.assist.MasterAssistService"
    private const val ROKID_SERVICE_ACTION = "com.rokid.os.sprite.assist.MasterAssistService"
    private const val SERVER_DESCRIPTOR = "com.rokid.os.sprite.assist.server.IAssistServer"
    private const val CLIENT_DESCRIPTOR = "com.rokid.os.sprite.assist.client.IAssistClient"

    // IAssistServer declaration order in Rokid's stock service:
    // 1 registerClient, 2 unRegisterClient, 3 controlMsgJson.
    private const val TRANSACTION_REGISTER_CLIENT = IBinder.FIRST_CALL_TRANSACTION
    private const val TRANSACTION_CONTROL_MSG_JSON = IBinder.FIRST_CALL_TRANSACTION + 2

    private const val CMD_START = "cmd_start_audio_record"
    private const val CMD_STOP = "cmd_stop_audio_record"

    private data class Pending(
        val action: String,
        val reply: (JSONObject) -> Unit,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private var applicationContext: Context? = null
    private var server: IBinder? = null
    private var binding = false
    private var registered = false

    /**
     * Minimal callback binder required by MasterAssistService registration. We deliberately do not
     * decode Rokid's proprietary parcelables: stock recording owns its own result/UI/storage flow.
     */
    private val clientBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CLIENT_DESCRIPTOR)
                return true
            }
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CLIENT_DESCRIPTOR)
            reply?.writeNoException()
            // IAssistClient.onMessageReceive(...) is transaction #2 and returns boolean.
            if (code == IBinder.FIRST_CALL_TRANSACTION + 1) reply?.writeInt(1)
            return true
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
            pending.addLast(Pending(action, reply))
            server?.let { binder ->
                drainLocked(context.applicationContext, binder)
                return true
            }
            if (!binding) bindLocked(context.applicationContext)
        }
        return true
    }

    private fun bindLocked(context: Context) {
        binding = true
        val intent = Intent(ROKID_SERVICE_ACTION).setClassName(ROKID_PACKAGE, ROKID_SERVICE_CLASS)
        val bound = runCatching {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            Log.w(TAG, "bind failed: ${error.javaClass.simpleName}")
            false
        }
        if (!bound) {
            binding = false
            failAllLocked("ROKID_RECORDING_SERVICE_UNAVAILABLE")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) {
                binding = false
                server = binder
                val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
                if (descriptor != SERVER_DESCRIPTOR) {
                    Log.w(TAG, "unexpected service descriptor=$descriptor")
                    server = null
                    failAllLocked("ROKID_RECORDING_INTERFACE_MISMATCH")
                    return
                }
                drainLocked(applicationContext ?: return, binder)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
                server = null
                registered = false
            }
        }

        override fun onBindingDied(name: ComponentName) {
            synchronized(lock) {
                server = null
                registered = false
                binding = false
                if (pending.isNotEmpty()) {
                    applicationContext?.let(::bindLocked)
                        ?: failAllLocked("ROKID_RECORDING_SERVICE_DIED")
                }
            }
        }

        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                server = null
                registered = false
                binding = false
                failAllLocked("ROKID_RECORDING_NULL_BINDING")
            }
        }
    }

    private fun drainLocked(context: Context, binder: IBinder) {
        if (!registered) {
            val registration = runCatching { registerClient(context, binder) }
            if (registration.isFailure) {
                Log.w(TAG, "client registration failed", registration.exceptionOrNull())
                failAllLocked("ROKID_RECORDING_REGISTER_FAILED")
                return
            }
            registered = true
        }

        while (pending.isNotEmpty()) {
            val request = pending.removeFirst()
            val command = if (request.action == "start") CMD_START else CMD_STOP
            val result = runCatching { sendCommand(context, binder, command) }
            if (result.isSuccess) {
                Log.i(TAG, "stock recorder command accepted action=${request.action}")
                request.reply(
                    JSONObject()
                        .put("action", request.action)
                        .put("accepted", true)
                        .put("owner", "rokid")
                        .put("capture", "stock"),
                )
            } else {
                val error = result.exceptionOrNull()
                Log.w(TAG, "stock recorder command failed action=${request.action}", error)
                request.reply(
                    JSONObject()
                        .put("action", request.action)
                        .put("accepted", false)
                        .put("error", "ROKID_RECORDING_COMMAND_FAILED")
                        .put("detail", error?.javaClass?.simpleName ?: "unknown"),
                )
            }
        }
    }

    private fun registerClient(context: Context, binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVER_DESCRIPTOR)
            data.writeString(context.packageName)
            data.writeStrongBinder(clientBinder)
            check(binder.transact(TRANSACTION_REGISTER_CLIENT, data, reply, 0)) {
                "registerClient transaction rejected"
            }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sendCommand(context: Context, binder: IBinder, command: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVER_DESCRIPTOR)
            data.writeString(context.packageName)
            data.writeString(JSONObject().put("cmd", command).toString())
            check(binder.transact(TRANSACTION_CONTROL_MSG_JSON, data, reply, 0)) {
                "controlMsgJson transaction rejected"
            }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun failAllLocked(error: String) {
        while (pending.isNotEmpty()) {
            val request = pending.removeFirst()
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", false)
                    .put("error", error),
            )
        }
    }
}
