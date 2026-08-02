package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsDoneEvent
import com.anezium.rokidbus.shared.TtsDoneReason
import com.anezium.rokidbus.shared.TtsSpeakRequest
import com.anezium.rokidbus.shared.TtsStopRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PhoneTtsPlaybackTest {
    @Test
    fun `preemption emits exactly one PREEMPTED and completion emits exactly one COMPLETED`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val playback = playback(output, done)

        assertTrue(playback.speak(speak("alpha", "u1", "one")))
        output.start()
        assertTrue(playback.speak(speak("beta", "u2", "two")))
        output.done()
        output.done()

        assertEquals(
            listOf(
                TtsDoneEvent("alpha", "u1", TtsDoneReason.PREEMPTED),
                TtsDoneEvent("beta", "u2", TtsDoneReason.COMPLETED),
            ),
            done,
        )
    }

    @Test
    fun `explicit stop emits STOPPED exactly once`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val playback = playback(output, done)
        playback.speak(speak("alpha", "u1", "hello"))

        assertTrue(playback.stop(TtsStopRequest("u1", "alpha")))
        output.stopped()

        assertEquals(listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.STOPPED)), done)
        assertEquals(1, output.stopCount)
    }

    @Test
    fun `engine failure emits UNAVAILABLE without throwing`() {
        val output = FakePhoneTtsOutput(
            ready = true,
            speakResult = PhoneTtsSpeakResult.ENGINE_UNAVAILABLE,
        )
        val done = mutableListOf<TtsDoneEvent>()

        assertTrue(playback(output, done).speak(speak("alpha", "u1", "hello")))
        output.unavailable()

        assertEquals(listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.UNAVAILABLE)), done)
    }

    @Test
    fun `microphone interlock cancels phone speech and glasses fallback`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val forwarded = mutableListOf<BusEnvelope>()
        val dispatcher = PhoneTtsDispatcher(
            playback = playback(output, done),
            forwardToGlasses = { envelope ->
                forwarded += envelope
                null
            },
            emitDone = { done += it },
        )
        dispatcher.dispatch(routedSpeak("alpha", "u1", "hello"))

        assertEquals(null, dispatcher.cancelForMicrophone())
        output.stopped()

        assertEquals(listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.CANCELLED)), done)
        assertEquals(listOf(BusPaths.TTS_CANCEL), forwarded.map(BusEnvelope::path))
    }

    @Test
    fun `absent language uses default locale and present language is honored`() {
        val output = FakePhoneTtsOutput(ready = true)
        val playback = PhoneTtsPlayback(
            output = output,
            defaultLocale = { Locale.CANADA_FRENCH },
            engineId = sequenceIds(),
            emitStarted = { _, _ -> },
            emitDone = {},
        )

        playback.speak(speak("alpha", "u1", "bonjour"))
        playback.speak(speak("alpha", "u2", "bonjour", "fr-FR"))

        assertEquals(listOf(Locale.CANADA_FRENCH, Locale.forLanguageTag("fr-FR")), output.locales)
    }

    @Test
    fun `unready engine initializes and forwards to the glasses unchanged`() {
        val output = FakePhoneTtsOutput(ready = false)
        val forwarded = mutableListOf<BusEnvelope>()
        val dispatcher = PhoneTtsDispatcher(
            playback = playback(output, mutableListOf()),
            forwardToGlasses = { envelope ->
                forwarded += envelope
                null
            },
            emitDone = {},
        )
        val envelope = routedSpeak("alpha", "u1", "bonjour", "fr-FR")

        assertEquals(PhoneTtsDispatchResult.Forwarded(null), dispatcher.dispatch(envelope))

        assertEquals(1, output.initializeCount)
        assertEquals(listOf(envelope), forwarded)
        assertEquals(0, output.spokenIds.size)
    }

    @Test
    fun `fallback remains on glasses until its terminal event even after phone init finishes`() {
        val output = FakePhoneTtsOutput(ready = false)
        val forwarded = mutableListOf<BusEnvelope>()
        val dispatcher = PhoneTtsDispatcher(
            playback = playback(output, mutableListOf()),
            forwardToGlasses = { envelope ->
                forwarded += envelope
                null
            },
            emitDone = {},
        )
        dispatcher.dispatch(routedSpeak("alpha", "u1", "one"))
        output.ready = true

        dispatcher.dispatch(routedSpeak("beta", "u2", "two"))

        assertEquals(listOf("u1", "u2"), forwarded.map { it.payload.getString("utteranceId") })
        assertTrue(output.spokenIds.isEmpty())
    }

    @Test
    fun `a link lost mid-sentence retires the stranded utterance and frees the phone engine`() {
        val output = FakePhoneTtsOutput(ready = false)
        val done = mutableListOf<TtsDoneEvent>()
        val dispatcher = PhoneTtsDispatcher(
            playback = playback(output, done),
            forwardToGlasses = { null },
            emitDone = done::add,
        )
        dispatcher.dispatch(routedSpeak("alpha", "u1", "one"))
        output.ready = true

        dispatcher.onLinkLost()
        dispatcher.dispatch(routedSpeak("beta", "u2", "two"))

        assertEquals(
            listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.UNAVAILABLE)),
            done.filter { it.utteranceId == "u1" },
        )
        // The stranded utterance never reached the phone engine, so the sentence after
        // the drop is the first one it speaks.
        assertEquals(listOf("engine-1"), output.spokenIds)
    }

    private fun playback(
        output: FakePhoneTtsOutput,
        done: MutableList<TtsDoneEvent>,
    ) = PhoneTtsPlayback(
        output = output,
        engineId = sequenceIds(),
        emitStarted = { _, _ -> },
        emitDone = done::add,
    )

    private fun speak(
        owner: String,
        utteranceId: String,
        text: String,
        lang: String? = null,
    ) = TtsSpeakRequest(utteranceId, text, owner, lang)

    private fun routedSpeak(
        owner: String,
        utteranceId: String,
        text: String,
        lang: String? = null,
    ) = BusEnvelope(
        path = BusPaths.TTS_SPEAK,
        payload = TtsContract.withOwner(
            TtsContract.speakPayload(utteranceId, text, lang),
            owner,
        ),
    )

    private fun sequenceIds(): () -> String {
        var next = 0
        return { "engine-${++next}" }
    }

    private class FakePhoneTtsOutput(
        var ready: Boolean,
        private val speakResult: PhoneTtsSpeakResult = PhoneTtsSpeakResult.ACCEPTED,
    ) : PhoneTtsOutput {
        private lateinit var listener: PhoneTtsOutput.Listener
        var initializeCount = 0
        var stopCount = 0
        val spokenIds = mutableListOf<String>()
        val locales = mutableListOf<Locale>()

        override val isReady: Boolean
            get() = ready

        override fun setListener(listener: PhoneTtsOutput.Listener) {
            this.listener = listener
        }

        override fun initialize() {
            initializeCount += 1
        }

        override fun speak(
            utteranceId: String,
            text: String,
            locale: Locale,
        ): PhoneTtsSpeakResult {
            spokenIds += utteranceId
            locales += locale
            return speakResult
        }

        override fun stop() {
            stopCount += 1
        }

        override fun shutdown() = Unit

        fun start() = listener.onStart(spokenIds.last())
        fun done() = listener.onDone(spokenIds.last())
        fun stopped() = listener.onStopped(spokenIds.last())
        fun unavailable() = listener.onUnavailable(spokenIds.last())
    }
}
