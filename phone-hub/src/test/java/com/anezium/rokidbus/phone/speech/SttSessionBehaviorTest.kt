package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class SttSessionBehaviorTest {
    @Test
    fun bufferedCancelBeforeExecutorRunsNeverInvokesTranscriber() {
        val tasks = mutableListOf<Runnable>()
        val transcriber = RecordingTranscriber()
        val session = BufferedSttSession(
            engine = SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE,
            language = TranscriptionLanguage.AUTO,
            languageTag = "en-US",
            transcriber = transcriber,
            executor = Executor(tasks::add),
            listener = RecordingListener(),
        )

        assertTrue(session.start())
        session.acceptPcm(ByteArray(3_200), 0, 3_200)
        session.finishAudio()
        session.cancel()
        tasks.single().run()

        assertEquals(0, transcriber.transcribeCalls)
        assertTrue(transcriber.cancelled)
    }

    @Test
    fun bufferedCancelWithoutFinishNeverSchedulesAnUpload() {
        val tasks = mutableListOf<Runnable>()
        val transcriber = RecordingTranscriber()
        val session = BufferedSttSession(
            engine = SpeechEngine.ELEVENLABS_SCRIBE_V2,
            language = TranscriptionLanguage.AUTO,
            languageTag = "en-US",
            transcriber = transcriber,
            executor = Executor(tasks::add),
            listener = RecordingListener(),
        )

        session.start()
        session.acceptPcm(ByteArray(32_000), 0, 32_000)
        session.cancel()

        assertTrue(tasks.isEmpty())
        assertEquals(0, transcriber.transcribeCalls)
    }

    @Test
    fun postCommitTimeoutCancelsDelegateAndReturnsStructuredTimeout() {
        val scheduler = ManualTimeoutScheduler()
        lateinit var delegate: HangingSession
        val listener = RecordingListener()
        val session = PostCommitTimeoutSttSession(
            delegateFactory = { engineListener ->
                HangingSession(engineListener).also { delegate = it }
            },
            listener = listener,
            providerLabel = "ElevenLabs",
            timeoutScheduler = scheduler,
            timeoutMs = 15_000L,
        )

        assertTrue(session.start())
        session.finishAudio()
        assertEquals(15_000L, scheduler.delayMs)
        scheduler.task!!.invoke()

        assertTrue(delegate.cancelled)
        assertEquals(SttErrorKind.TIMEOUT, listener.error?.kind)
        assertEquals("ElevenLabs", listener.error?.providerLabel)
    }

    @Test
    fun providerErrorUsesTheCorrectProviderLabelAndDoesNotEchoRawText() {
        val error = providerError(
            provider = SpeechProvider.ELEVENLABS,
            providerMessage = "auth_error secret-provider-message",
        )

        assertEquals(SttErrorKind.AUTH, error.kind)
        assertEquals("ElevenLabs", error.providerLabel)
        assertFalse(error.detail.orEmpty().contains("secret-provider-message"))
    }

    private class RecordingTranscriber : CompletedAudioSpeechToTextEngine {
        var transcribeCalls = 0
        var cancelled = false

        override fun transcribe(input: CompletedAudioSpeechToTextInput): String {
            transcribeCalls += 1
            return "unused"
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class HangingSession(
        private val listener: SttSessionListener,
    ) : SttSession {
        var cancelled = false

        override fun start(): Boolean {
            listener.onReady()
            return true
        }

        override fun acceptPcm(data: ByteArray, offset: Int, length: Int) = Unit
        override fun finishAudio() = Unit

        override fun cancel() {
            cancelled = true
        }
    }

    private class ManualTimeoutScheduler : SttTimeoutScheduler {
        var delayMs: Long? = null
        var task: (() -> Unit)? = null

        override fun schedule(delayMs: Long, task: () -> Unit): SttTimeoutHandle {
            this.delayMs = delayMs
            this.task = task
            return SttTimeoutHandle {
                this.task = null
            }
        }
    }

    private class RecordingListener : SttSessionListener {
        var error: SttError? = null

        override fun onReady() = Unit
        override fun onPartial(text: String) = Unit
        override fun onFinal(text: String) = Unit
        override fun onError(error: SttError) {
            this.error = error
        }
    }
}
