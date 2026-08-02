package com.anezium.rokidbus.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.anezium.rokidbus.shared.ActiveTtsUtterance
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsDoneEvent
import com.anezium.rokidbus.shared.TtsDoneReason
import com.anezium.rokidbus.shared.TtsDoneOutbox
import com.anezium.rokidbus.shared.TtsPlaybackState
import com.anezium.rokidbus.shared.TtsStartedEvent
import com.anezium.rokidbus.shared.TtsValidationResult
import com.rokid.os.sprite.tts.ITtsListener
import com.rokid.os.sprite.tts.ITtsServer
import java.util.UUID

/** Owns the lazy connection to the ROM's private, on-device neural TTS service. */
internal object TtsController {
    private const val BIND_TIMEOUT_MS = 2_000L
    private const val TTS_ACTION = "com.rokid.os.sprite.tts.TTS_SERVICE"
    private const val TTS_PACKAGE = "com.rokid.os.sprite.assistserver"
    private const val TTS_SERVICE = "com.rokid.os.sprite.tts.TtsService"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val playback = TtsPlaybackState()
    private val doneOutbox = TtsDoneOutbox()
    private var appContext: Context? = null
    private var server: ITtsServer? = null
    private var bindRegistered = false
    private var rebindScheduled = false
    private var bindTimeout: Runnable? = null

    private val listener = object : ITtsListener.Stub() {
        override fun onTtsStart(id: String?) {
            if (id == null) return
            mainHandler.post { playback.started(id)?.let(::emitStarted) }
        }

        override fun onTtsStop(id: String?) {
            if (id == null) return
            mainHandler.post { playback.stopped(id)?.let(::emitDone) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            mainHandler.post {
                if (!bindRegistered) return@post
                cancelBindTimeout()
                rebindScheduled = false
                val connected = runCatching { ITtsServer.Stub.asInterface(binder) }.getOrNull()
                if (connected == null) {
                    onBindingUnavailable("as_interface")
                    return@post
                }
                server = connected
                playback.current()?.let { play(it, connected) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mainHandler.post { onBindingLost("service_disconnected") }
        }

        override fun onBindingDied(name: ComponentName) {
            mainHandler.post { onBindingLost("binding_died") }
        }

        override fun onNullBinding(name: ComponentName) {
            mainHandler.post { onBindingUnavailable("null_binding") }
        }
    }

    fun handleEnvelope(context: Context, envelope: BusEnvelope): Boolean {
        if (envelope.path == BusPaths.TTS_CANCEL) {
            cancelCurrent(TtsDoneReason.CANCELLED)
            return true
        }
        val command = when (envelope.path) {
            BusPaths.TTS_SPEAK -> when (
                val result = TtsContract.validateSpeak(envelope.payload, requireOwner = true)
            ) {
                is TtsValidationResult.Valid -> Runnable { speak(context, result.value) }
                is TtsValidationResult.Invalid -> Runnable {
                    log("tts speak ignored reason=${result.reason}")
                }
            }
            BusPaths.TTS_STOP -> when (
                val result = TtsContract.validateStop(envelope.payload, requireOwner = true)
            ) {
                is TtsValidationResult.Valid -> Runnable { stop(result.value.ownerPluginId!!, result.value.utteranceId) }
                is TtsValidationResult.Invalid -> Runnable {
                    log("tts stop ignored reason=${result.reason}")
                }
            }
            else -> return false
        }
        mainHandler.post(command)
        return true
    }

    /** Retries terminal events that were produced during a transient phone-link loss. */
    fun onPhoneLinkAvailable() {
        mainHandler.post(::flushDoneEvents)
    }

    @Suppress("DEPRECATION")
    fun isServiceAvailable(context: Context): Boolean = runCatching {
        context.packageManager.resolveService(serviceIntent(), PackageManager.MATCH_ALL) != null
    }.getOrDefault(false)

    /** Cancellation seam used by future hub arbitration (for example a microphone interlock). */
    internal fun cancelCurrent(reason: TtsDoneReason = TtsDoneReason.STOPPED) {
        mainHandler.post {
            val stopping = playback.cancelCurrent(reason) ?: return@post
            stopEngineOrFinish(stopping)
        }
    }

    private fun speak(context: Context, request: com.anezium.rokidbus.shared.TtsSpeakRequest) {
        appContext = context.applicationContext
        val accepted = playback.accept(
            ownerPluginId = checkNotNull(request.ownerPluginId),
            utteranceId = request.utteranceId,
            engineId = UUID.randomUUID().toString(),
            text = request.text,
        )
        accepted.preempted?.let(::emitDone)
        val connected = server
        if (connected == null) ensureBound() else play(accepted.active, connected)
    }

    private fun stop(ownerPluginId: String, utteranceId: String) {
        val stopping = playback.requestStop(ownerPluginId, utteranceId) ?: return
        stopEngineOrFinish(stopping)
    }

    private fun stopEngineOrFinish(stopping: ActiveTtsUtterance) {
        val connected = server
        if (connected == null) {
            playback.stopped(stopping.engineId)?.let(::emitDone)
            return
        }
        try {
            connected.stopTtsPlay(stopping.engineId)
        } catch (exception: RemoteException) {
            log("tts stop failed type=${exception.javaClass.simpleName}")
            onBindingLost("stop_failed")
        } catch (exception: RuntimeException) {
            log("tts stop failed type=${exception.javaClass.simpleName}")
            onBindingLost("stop_failed")
        }
    }

    private fun play(active: ActiveTtsUtterance, connected: ITtsServer) {
        if (playback.current()?.engineId != active.engineId) return
        try {
            connected.playTtsMsg(active.text, active.engineId, listener)
        } catch (exception: RemoteException) {
            log("tts play failed type=${exception.javaClass.simpleName}")
            onBindingLost("play_failed")
        } catch (exception: RuntimeException) {
            log("tts play failed type=${exception.javaClass.simpleName}")
            onBindingLost("play_failed")
        }
    }

    private fun ensureBound() {
        if (bindRegistered) return
        val context = appContext ?: run {
            playback.unavailable()?.let(::emitDone)
            return
        }
        bindRegistered = true
        val accepted = try {
            context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
        } catch (exception: SecurityException) {
            log("tts bind refused type=${exception.javaClass.simpleName}")
            false
        } catch (exception: RuntimeException) {
            log("tts bind failed type=${exception.javaClass.simpleName}")
            false
        }
        if (!accepted) {
            bindRegistered = false
            server = null
            playback.unavailable()?.let(::emitDone)
        } else {
            scheduleBindTimeout()
        }
    }

    private fun onBindingUnavailable(reason: String) {
        log("tts binding unavailable reason=$reason")
        cancelBindTimeout()
        server = null
        playback.unavailable()?.let(::emitDone)
        unbindIfRegistered()
    }

    private fun onBindingLost(reason: String) {
        log("tts binding lost reason=$reason")
        cancelBindTimeout()
        server = null
        playback.unavailable()?.let(::emitDone)
        if (rebindScheduled) return
        rebindScheduled = true
        mainHandler.post {
            unbindIfRegistered()
            ensureBound()
        }
    }

    private fun unbindIfRegistered() {
        if (!bindRegistered) return
        bindRegistered = false
        appContext?.let { context ->
            runCatching { context.unbindService(connection) }
        }
    }

    private fun scheduleBindTimeout() {
        cancelBindTimeout()
        val timeout = Runnable {
            bindTimeout = null
            if (server != null || !bindRegistered) return@Runnable
            log("tts binding unavailable reason=timeout")
            playback.unavailable()?.let(::emitDone)
            unbindIfRegistered()
        }
        bindTimeout = timeout
        mainHandler.postDelayed(timeout, BIND_TIMEOUT_MS)
    }

    private fun cancelBindTimeout() {
        bindTimeout?.let(mainHandler::removeCallbacks)
        bindTimeout = null
    }

    private fun emitStarted(event: TtsStartedEvent) {
        GlassesHub.sendToPhone(
            BusPaths.TTS_STARTED,
            TtsContract.startedPayload(event.ownerPluginId, event.utteranceId),
        )
    }

    private fun emitDone(event: TtsDoneEvent) {
        doneOutbox.enqueue(event)
        flushDoneEvents()
    }

    private fun flushDoneEvents() {
        doneOutbox.flush { event ->
            GlassesHub.sendToPhone(
                BusPaths.TTS_DONE,
                TtsContract.donePayload(event.ownerPluginId, event.utteranceId, event.reason),
            )
        }
    }

    private fun serviceIntent(): Intent = Intent(TTS_ACTION).setComponent(
        ComponentName(TTS_PACKAGE, TTS_SERVICE),
    )
}
