package com.anezium.rokidbus.phone

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal enum class PhoneTtsSpeakResult {
    ACCEPTED,
    ENGINE_UNAVAILABLE,
    LANGUAGE_UNAVAILABLE,
}

internal interface PhoneTtsOutput {
    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onUnavailable(utteranceId: String)
        fun onStopped(utteranceId: String)
    }

    val isReady: Boolean

    fun setListener(listener: Listener)
    fun initialize()
    fun speak(utteranceId: String, text: String, locale: Locale): PhoneTtsSpeakResult
    fun stop()
    fun shutdown()
}

/** Lazy wrapper around the phone's configured Android TTS engine. */
internal class PhoneTtsEngine(
    context: Context,
    private val logger: (String) -> Unit,
) : PhoneTtsOutput {
    private enum class State { NEW, INITIALIZING, READY, UNAVAILABLE, SHUTDOWN }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var state = State.NEW
    private var engine: TextToSpeech? = null
    @Volatile private var listener: PhoneTtsOutput.Listener? = null

    override val isReady: Boolean
        get() = state == State.READY

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.let { listener?.onStart(it) }
        }

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { listener?.onDone(it) }
        }

        @Deprecated("Android still calls this overload on older engines")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { listener?.onUnavailable(it) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { listener?.onUnavailable(it) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { listener?.onStopped(it) }
        }
    }

    override fun setListener(listener: PhoneTtsOutput.Listener) {
        this.listener = listener
    }

    override fun initialize() {
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.INITIALIZING
        }
        mainHandler.post(::createEngine)
    }

    override fun speak(
        utteranceId: String,
        text: String,
        locale: Locale,
    ): PhoneTtsSpeakResult {
        val current = synchronized(lock) {
            if (state == State.READY) engine else null
        } ?: return PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
        val languageResult = runCatching { current.setLanguage(locale) }
            .getOrElse {
                logger("phone TTS speak id=$utteranceId result=LANGUAGE_ERROR chars=${text.length}")
                return PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
            }
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            logger("phone TTS speak id=$utteranceId result=LANGUAGE_UNAVAILABLE chars=${text.length}")
            return PhoneTtsSpeakResult.LANGUAGE_UNAVAILABLE
        }
        val result = runCatching {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.getOrElse {
            logger("phone TTS speak id=$utteranceId result=SPEAK_ERROR chars=${text.length}")
            TextToSpeech.ERROR
        }
        logger("phone TTS speak id=$utteranceId result=$result chars=${text.length}")
        return if (result == TextToSpeech.SUCCESS) {
            PhoneTtsSpeakResult.ACCEPTED
        } else {
            PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
        }
    }

    override fun stop() {
        val current = synchronized(lock) { engine }
        val result = runCatching { current?.stop() }.getOrNull()
        logger("phone TTS stop result=${result ?: TextToSpeech.ERROR}")
    }

    override fun shutdown() {
        val current = synchronized(lock) {
            if (state == State.SHUTDOWN) return
            state = State.SHUTDOWN
            engine.also { engine = null }
        }
        runCatching { current?.shutdown() }
        listener = null
        logger("phone TTS shutdown")
    }

    private fun createEngine() {
        synchronized(lock) {
            if (state != State.INITIALIZING || engine != null) return
        }
        var candidate: TextToSpeech? = null
        candidate = try {
            TextToSpeech(appContext) { status ->
                val initialized = candidate
                mainHandler.post { completeInitialization(initialized, status) }
            }
        } catch (exception: RuntimeException) {
            logger("phone TTS ready=false reason=init_${exception.javaClass.simpleName}")
            synchronized(lock) {
                if (state == State.INITIALIZING) state = State.UNAVAILABLE
            }
            null
        }
        val initializedEngine = candidate ?: return
        synchronized(lock) {
            if (state == State.INITIALIZING) {
                engine = initializedEngine
            } else {
                initializedEngine.shutdown()
            }
        }
    }

    private fun completeInitialization(candidate: TextToSpeech?, status: Int) {
        val current = synchronized(lock) {
            if (state != State.INITIALIZING || engine !== candidate) return
            engine
        } ?: return markUnavailable("missing_engine")
        if (status != TextToSpeech.SUCCESS) {
            markUnavailable("init_status_$status")
            return
        }
        val configured = runCatching {
            val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes.USAGE_ASSISTANT
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            current.setAudioAttributes(attributes) != TextToSpeech.ERROR &&
                current.setOnUtteranceProgressListener(progressListener) != TextToSpeech.ERROR
        }.getOrDefault(false)
        if (!configured) {
            markUnavailable("configuration")
            return
        }
        val ready = synchronized(lock) {
            if (state == State.INITIALIZING && engine === current) {
                state = State.READY
                true
            } else {
                false
            }
        }
        if (ready) logger("phone TTS ready=true")
    }

    private fun markUnavailable(reason: String) {
        val current = synchronized(lock) {
            if (state == State.SHUTDOWN) return
            state = State.UNAVAILABLE
            engine.also { engine = null }
        }
        runCatching { current?.shutdown() }
        logger("phone TTS ready=false reason=$reason")
    }
}
