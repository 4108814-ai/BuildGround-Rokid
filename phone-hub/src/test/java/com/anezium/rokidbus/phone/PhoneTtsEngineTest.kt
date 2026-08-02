package com.anezium.rokidbus.phone

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PhoneTtsEngineTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearSettings() {
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PhoneTtsSettingsStore.KEY_SPEECH_RATE)
            .remove(PhoneTtsSettingsStore.KEY_VOICE_NAME)
            .commit()
    }

    @Test
    fun `prewarm is skipped when engine is not ready`() {
        val engine = PhoneTtsEngine(context) {}

        assertFalse(engine.prewarm())
    }

    @Test
    fun `prewarm is rate limited after completion`() {
        val backend = FakePhoneTtsBackend()
        var now = 1_000L
        val engine = PhoneTtsEngine(context, backend, nowMs = { now })

        assertTrue(engine.prewarm())
        backend.done(PhoneTtsEngine.PREWARM_UTTERANCE_ID)
        now = 10_999L
        assertFalse(engine.prewarm())
        now = 11_000L
        assertTrue(engine.prewarm())

        assertEquals(2, backend.silentUtterances.size)
        assertEquals(
            listOf(TextToSpeech.QUEUE_ADD, TextToSpeech.QUEUE_ADD),
            backend.silentUtterances.map { it.queueMode },
        )
    }

    @Test
    fun `prewarm is skipped while a plugin utterance is active`() {
        val backend = FakePhoneTtsBackend()
        val engine = PhoneTtsEngine(context, backend)
        engine.speak("plugin-id", "private", Locale.US)

        assertFalse(engine.prewarm())

        assertTrue(backend.silentUtterances.isEmpty())
    }

    @Test
    fun `stored rate is applied before plugin and sample utterances`() {
        PhoneTtsSettingsStore(context).setSpeechRate(1.6f)
        val backend = FakePhoneTtsBackend()
        val engine = PhoneTtsEngine(context, backend)

        assertEquals(
            PhoneTtsSpeakResult.ACCEPTED,
            engine.speak("plugin-id", "private plugin text", Locale.US),
        )
        backend.done("plugin-id")
        assertTrue(engine.speakSample("private sample text", Locale.US))

        assertEquals(listOf(1.6f, 1.6f), backend.speechRates)
    }

    @Test
    fun `stored voice that no longer exists falls back without clearing preference`() {
        val settings = PhoneTtsSettingsStore(context)
        settings.setVoiceName("removed.voice")
        val default = voice("default.voice", Locale.US)
        val backend = FakePhoneTtsBackend(
            voices = linkedSetOf(voice("available.voice", Locale.US)),
            defaultVoice = default,
        )
        val engine = PhoneTtsEngine(context, backend)

        assertEquals(
            PhoneTtsSpeakResult.ACCEPTED,
            engine.speak("plugin-id", "private", Locale.US),
        )

        assertEquals(default, backend.selectedVoices.first())
        assertEquals("removed.voice", settings.voiceName())
    }

    @Test
    fun `stored voice is applied when it still exists`() {
        val selected = voice("selected.voice", Locale.US)
        PhoneTtsSettingsStore(context).setVoiceName(selected.name)
        val backend = FakePhoneTtsBackend(voices = linkedSetOf(selected))
        val engine = PhoneTtsEngine(context, backend)

        engine.speak("plugin-id", "private", Locale.US)

        assertEquals(listOf(selected), backend.selectedVoices)
    }

    @Test
    fun `available voices is empty until ready and maps matching locale without sorting`() {
        val unready = PhoneTtsEngine(context) {}
        assertTrue(unready.availableVoices(Locale.US).isEmpty())

        val first = voice("z.voice", Locale.US, quality = Voice.QUALITY_HIGH, network = true)
        val second = voice("a.voice", Locale.US, quality = Voice.QUALITY_NORMAL)
        val backend = FakePhoneTtsBackend(
            voices = linkedSetOf(first, voice("fr.voice", Locale.FRANCE), second),
        )
        val ready = PhoneTtsEngine(context, backend)

        assertEquals(
            listOf(
                PhoneTtsVoiceOption("z.voice", Locale.US, Voice.QUALITY_HIGH, true),
                PhoneTtsVoiceOption("a.voice", Locale.US, Voice.QUALITY_NORMAL, false),
            ),
            ready.availableVoices(Locale.US),
        )
    }

    @Test
    fun `sample refuses while plugin utterance is active`() {
        val backend = FakePhoneTtsBackend()
        val engine = PhoneTtsEngine(context, backend)
        engine.speak("plugin-id", "private", Locale.US)

        assertFalse(engine.speakSample("private sample", Locale.US))

        assertEquals(listOf("plugin-id"), backend.spokenUtterances.map { it.id })
    }

    @Test
    fun `audio callbacks log elapsed time without spoken text`() {
        val backend = FakePhoneTtsBackend()
        val logs = mutableListOf<String>()
        var now = 100L
        val engine = PhoneTtsEngine(context, backend, logger = logs::add, nowMs = { now })
        engine.speak("plugin-id", "never log these words", Locale.US)

        now = 260L
        backend.start("plugin-id")
        now = 430L
        backend.done("plugin-id")

        assertTrue(logs.contains("phone TTS audio start id=plugin-id afterMs=160"))
        assertTrue(logs.contains("phone TTS audio done id=plugin-id afterMs=330"))
        assertFalse(logs.any { it.contains("never log these words") })
    }

    private fun voice(
        name: String,
        locale: Locale,
        quality: Int = Voice.QUALITY_NORMAL,
        network: Boolean = false,
    ) = Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, emptySet())

    private data class SpokenUtterance(val id: String, val queueMode: Int)
    private data class SilentUtterance(val id: String, val queueMode: Int, val durationMs: Long)

    private class FakePhoneTtsBackend(
        override val voices: Set<Voice>? = emptySet(),
        override val defaultVoice: Voice? = null,
    ) : PhoneTtsBackend {
        private lateinit var listener: UtteranceProgressListener
        val speechRates = mutableListOf<Float>()
        val selectedVoices = mutableListOf<Voice>()
        val spokenUtterances = mutableListOf<SpokenUtterance>()
        val silentUtterances = mutableListOf<SilentUtterance>()

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setSpeechRate(rate: Float): Int {
            speechRates += rate
            return TextToSpeech.SUCCESS
        }

        override fun setVoice(voice: Voice): Int {
            selectedVoices += voice
            return TextToSpeech.SUCCESS
        }

        override fun setAudioAttributes(attributes: AudioAttributes): Int = TextToSpeech.SUCCESS

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }

        override fun speak(text: String, queueMode: Int, utteranceId: String): Int {
            spokenUtterances += SpokenUtterance(utteranceId, queueMode)
            return TextToSpeech.SUCCESS
        }

        override fun playSilentUtterance(
            durationInMs: Long,
            queueMode: Int,
            utteranceId: String,
        ): Int {
            silentUtterances += SilentUtterance(utteranceId, queueMode, durationInMs)
            return TextToSpeech.SUCCESS
        }

        override fun stop(): Int = TextToSpeech.SUCCESS
        override fun shutdown() = Unit

        fun start(utteranceId: String) = listener.onStart(utteranceId)
        fun done(utteranceId: String) = listener.onDone(utteranceId)
    }
}
