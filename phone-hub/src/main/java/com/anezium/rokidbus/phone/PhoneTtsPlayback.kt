package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsDoneEvent
import com.anezium.rokidbus.shared.TtsDoneReason
import com.anezium.rokidbus.shared.TtsPlaybackState
import com.anezium.rokidbus.shared.TtsSpeakRequest
import com.anezium.rokidbus.shared.TtsStopRequest
import com.anezium.rokidbus.shared.TtsValidationResult
import java.util.Locale
import java.util.UUID

internal class PhoneTtsPlayback(
    private val output: PhoneTtsOutput,
    private val defaultLocale: () -> Locale = Locale::getDefault,
    private val engineId: () -> String = { UUID.randomUUID().toString() },
    private val emitStarted: (ownerPluginId: String, utteranceId: String) -> Unit,
    private val emitDone: (TtsDoneEvent) -> Unit,
) : PhoneTtsOutput.Listener {
    private val lock = Any()
    private val state = TtsPlaybackState()
    private var activeRecoveryEnvelope: BusEnvelope? = null
    private var recoverUnexpectedFailure: (BusEnvelope) -> Boolean = { false }

    init {
        output.setListener(this)
    }

    val isReady: Boolean
        get() = output.isReady

    fun initialize() = output.initialize()

    fun prewarm(): Boolean = synchronized(lock) {
        if (!output.isReady || state.current() != null) return@synchronized false
        output.prewarm()
    }

    fun availableVoices(locale: Locale): List<PhoneTtsVoiceOption> =
        output.availableVoices(locale)

    fun speakSample(text: String, locale: Locale): Boolean = synchronized(lock) {
        if (!output.isReady || state.current() != null) return@synchronized false
        output.speakSample(text, locale)
    }

    fun setUnexpectedFailureHandler(handler: (BusEnvelope) -> Boolean) = synchronized(lock) {
        recoverUnexpectedFailure = handler
    }

    fun speak(
        request: TtsSpeakRequest,
        recoveryEnvelope: BusEnvelope? = null,
    ): Boolean = synchronized(lock) {
        if (!output.isReady) return@synchronized false
        val ownerPluginId = request.ownerPluginId ?: return@synchronized false
        val accepted =
            state.accept(ownerPluginId, request.utteranceId, engineId(), request.text)
        accepted.preempted?.let(emitDone)
        activeRecoveryEnvelope = recoveryEnvelope
        val locale = request.lang?.let(Locale::forLanguageTag) ?: defaultLocale()
        val result = output.speak(
            accepted.active.engineId,
            accepted.active.text,
            locale,
        )
        if (result != PhoneTtsSpeakResult.ACCEPTED) {
            output.stop()
            state.unavailable(accepted.active.engineId)?.let { event ->
                activeRecoveryEnvelope = null
                emitDone(event)
            }
        }
        true
    }

    fun stop(request: TtsStopRequest): Boolean = synchronized(lock) {
        val ownerPluginId = request.ownerPluginId ?: return@synchronized false
        val stopping =
            state.requestStop(ownerPluginId, request.utteranceId)
                ?: return@synchronized false
        val event = state.stopped(stopping.engineId)
        if (event != null) activeRecoveryEnvelope = null
        output.stop()
        event?.let(emitDone)
        true
    }

    fun cancel(reason: TtsDoneReason) = synchronized(lock) {
        val stopping = state.cancelCurrent(reason) ?: return@synchronized
        val event = state.stopped(stopping.engineId)
        if (event != null) activeRecoveryEnvelope = null
        output.stop()
        event?.let(emitDone)
    }

    fun shutdown() = synchronized(lock) {
        val stopping = state.cancelCurrent(TtsDoneReason.CANCELLED)
        val event = stopping?.let { state.stopped(it.engineId) }
        if (event != null) activeRecoveryEnvelope = null
        if (stopping != null) output.stop()
        event?.let(emitDone)
        output.shutdown()
    }

    override fun onStart(utteranceId: String) {
        synchronized(lock) { state.started(utteranceId) }
            ?.let { emitStarted(it.ownerPluginId, it.utteranceId) }
    }

    override fun onDone(utteranceId: String) {
        synchronized(lock) {
            state.stopped(utteranceId)?.also { activeRecoveryEnvelope = null }
        }?.let(emitDone)
    }

    override fun onUnavailable(utteranceId: String) {
        handleUnexpectedFailure(utteranceId)
    }

    override fun onStopped(utteranceId: String) {
        handleUnexpectedFailure(utteranceId)
    }

    private fun handleUnexpectedFailure(utteranceId: String) {
        val failure = synchronized(lock) {
            val event = state.unavailable(utteranceId) ?: return
            val envelope = activeRecoveryEnvelope
            activeRecoveryEnvelope = null
            Triple(event, envelope, recoverUnexpectedFailure)
        }
        val recovered = failure.second?.let { envelope ->
            runCatching { failure.third(envelope) }.getOrDefault(false)
        } == true
        if (!recovered) emitDone(failure.first)
    }
}

internal sealed interface PhoneTtsDispatchResult {
    data object PhoneHandled : PhoneTtsDispatchResult
    data class Forwarded(val error: String?) : PhoneTtsDispatchResult
    data class Invalid(val error: String) : PhoneTtsDispatchResult
}

internal class PhoneTtsDispatcher(
    private val playback: PhoneTtsPlayback,
    private val forwardToGlasses: (BusEnvelope) -> String?,
    private val emitDone: (TtsDoneEvent) -> Unit,
    private val outputMode: () -> PhoneTtsOutputMode = { PhoneTtsOutputMode.AUTO },
    private val phoneRoute: () -> PhoneTtsRoute = { PhoneTtsRoute.EXTERNAL_SINK },
    private val logger: (String) -> Unit = {},
) {
    private data class RemoteUtterance(val ownerPluginId: String, val utteranceId: String)

    private val remoteLock = Any()
    private val dispatchLock = Any()
    private val remoteInFlight = mutableMapOf<RemoteUtterance, Int>()

    init {
        playback.setUnexpectedFailureHandler(::recoverPhoneFailureOnGlasses)
    }

    fun dispatch(envelope: BusEnvelope): PhoneTtsDispatchResult = synchronized(dispatchLock) {
        when (envelope.path) {
            BusPaths.TTS_SPEAK -> dispatchSpeak(envelope)
            BusPaths.TTS_STOP -> dispatchStop(envelope)
            else -> PhoneTtsDispatchResult.Invalid(TtsContract.ERROR_INVALID_TTS)
        }
    }

    fun onRemoteDone(ownerPluginId: String, utteranceId: String) {
        val key = RemoteUtterance(ownerPluginId, utteranceId)
        synchronized(dispatchLock) {
            synchronized(remoteLock) { decrementRemote(key) }
        }
    }

    fun initialize() = synchronized(dispatchLock) { playback.initialize() }

    fun prewarm(): Boolean = synchronized(dispatchLock) {
        if (outputMode() == PhoneTtsOutputMode.GLASSES_ONLY) false else playback.prewarm()
    }

    fun availableVoices(locale: Locale): List<PhoneTtsVoiceOption> =
        synchronized(dispatchLock) { playback.availableVoices(locale) }

    fun speakSample(text: String, locale: Locale): Boolean = synchronized(dispatchLock) {
        if (hasRemoteInFlight()) return@synchronized false
        playback.speakSample(text, locale)
    }

    /**
     * A forwarded utterance is only cleared by the glasses reporting it done, so a link
     * that drops mid-sentence would otherwise strand it: the phone engine would stay
     * disabled for the rest of the process and every later sentence would keep being sent
     * to glasses that are no longer listening. Retire them here, and give their owners the
     * terminal event the contract promises them.
     */
    fun onLinkLost() = synchronized(dispatchLock) {
        val stranded = synchronized(remoteLock) {
            remoteInFlight.keys.toList().also { remoteInFlight.clear() }
        }
        stranded.forEach {
            emitDone(TtsDoneEvent(it.ownerPluginId, it.utteranceId, TtsDoneReason.UNAVAILABLE))
        }
    }

    fun cancelForMicrophone(): String? = synchronized(dispatchLock) {
        playback.cancel(TtsDoneReason.CANCELLED)
        forwardToGlasses(BusEnvelope(path = BusPaths.TTS_CANCEL))
    }

    fun shutdown() = synchronized(dispatchLock) { playback.shutdown() }

    private fun dispatchSpeak(envelope: BusEnvelope): PhoneTtsDispatchResult {
        val request = when (val validation = TtsContract.validateSpeak(envelope.payload, requireOwner = true)) {
            is TtsValidationResult.Valid -> validation.value
            is TtsValidationResult.Invalid -> return PhoneTtsDispatchResult.Invalid(validation.reason)
        }
        if (outputMode() == PhoneTtsOutputMode.GLASSES_ONLY) {
            logger("phone TTS dispatch classification=NOT_PROBED route=glasses mode=GLASSES_ONLY")
            val key = RemoteUtterance(checkNotNull(request.ownerPluginId), request.utteranceId)
            synchronized(remoteLock) {
                remoteInFlight[key] = remoteInFlight.getOrDefault(key, 0) + 1
            }
            val error = forwardToGlasses(envelope)
            if (error != null) synchronized(remoteLock) { decrementRemote(key) }
            return PhoneTtsDispatchResult.Forwarded(error)
        }
        val classification = phoneRoute()
        if (
            !hasRemoteInFlight() &&
            playback.isReady &&
            classification == PhoneTtsRoute.EXTERNAL_SINK
        ) {
            if (playback.speak(request, recoveryEnvelope = envelope)) {
                logger("phone TTS dispatch classification=$classification route=phone")
                return PhoneTtsDispatchResult.PhoneHandled
            }
        }
        val glassesReason = when {
            classification != PhoneTtsRoute.EXTERNAL_SINK -> "classified"
            hasRemoteInFlight() -> "remote_in_flight"
            else -> "phone_unavailable"
        }
        logger(
            "phone TTS dispatch classification=$classification " +
                "route=glasses reason=$glassesReason",
        )
        if (classification == PhoneTtsRoute.EXTERNAL_SINK && !playback.isReady) {
            playback.initialize()
        }
        return forwardAutoToGlasses(envelope, request, emitUnavailableOnError = false)
    }

    /**
     * A dispatch-time forward failure surfaces to the plugin as a rejected speak request,
     * so emitting a done event as well would signal the same utterance twice. The async
     * recovery path has no request left to reject — there the done event is the only
     * terminal signal, hence [emitUnavailableOnError].
     */
    private fun forwardAutoToGlasses(
        envelope: BusEnvelope,
        request: TtsSpeakRequest,
        emitUnavailableOnError: Boolean,
    ): PhoneTtsDispatchResult.Forwarded {
        val key = RemoteUtterance(checkNotNull(request.ownerPluginId), request.utteranceId)
        synchronized(remoteLock) {
            remoteInFlight[key] = remoteInFlight.getOrDefault(key, 0) + 1
        }
        val error = forwardToGlasses(envelope)
        if (error != null) {
            synchronized(remoteLock) { decrementRemote(key) }
            if (emitUnavailableOnError) {
                emitDone(TtsDoneEvent(key.ownerPluginId, key.utteranceId, TtsDoneReason.UNAVAILABLE))
            }
        }
        return PhoneTtsDispatchResult.Forwarded(error)
    }

    private fun recoverPhoneFailureOnGlasses(envelope: BusEnvelope): Boolean =
        synchronized(dispatchLock) {
            val request = when (
                val validation = TtsContract.validateSpeak(envelope.payload, requireOwner = true)
            ) {
                is TtsValidationResult.Valid -> validation.value
                is TtsValidationResult.Invalid -> return@synchronized false
            }
            logger("phone TTS dispatch classification=ASYNC_PHONE_FAILURE route=glasses")
            forwardAutoToGlasses(envelope, request, emitUnavailableOnError = true)
            true
        }

    private fun dispatchStop(envelope: BusEnvelope): PhoneTtsDispatchResult {
        val request = when (val validation = TtsContract.validateStop(envelope.payload, requireOwner = true)) {
            is TtsValidationResult.Valid -> validation.value
            is TtsValidationResult.Invalid -> return PhoneTtsDispatchResult.Invalid(validation.reason)
        }
        if (!hasRemoteInFlight() && playback.stop(request)) {
            return PhoneTtsDispatchResult.PhoneHandled
        }
        return PhoneTtsDispatchResult.Forwarded(forwardToGlasses(envelope))
    }

    private fun hasRemoteInFlight(): Boolean = synchronized(remoteLock) {
        remoteInFlight.isNotEmpty()
    }

    private fun decrementRemote(key: RemoteUtterance) {
        val count = remoteInFlight[key] ?: return
        if (count <= 1) remoteInFlight.remove(key) else remoteInFlight[key] = count - 1
    }
}
