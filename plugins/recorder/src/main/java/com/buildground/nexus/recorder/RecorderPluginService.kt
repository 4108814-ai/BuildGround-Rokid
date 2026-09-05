package com.buildground.nexus.recorder

import android.content.ContentValues
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated source-audio recorder for Nexus.
 *
 * The recorder owns one raw glasses-audio lease independently from HUD visibility. The WAV is
 * created directly in the phone Downloads collection and its header is checkpointed while recording,
 * so Meetings and other clients can later consume a durable user-visible source file instead of
 * owning the microphone themselves.
 */
class RecorderPluginService : NexusPluginService() {
    private enum class RecorderState {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING,
    }

    private data class Sink(
        val uri: Uri,
        val displayName: String,
        val descriptor: ParcelFileDescriptor,
        val output: FileOutputStream,
    )

    private data class FinalizeSnapshot(
        val sink: Sink?,
        val format: NexusAudioFormat?,
        val dataBytes: Long,
        val interrupted: Boolean,
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var surface: NexusSurfaceSession? = null
    private var audioSession: NexusAudioSession? = null
    private var state = RecorderState.IDLE
    private var sink: Sink? = null
    private var audioFormat: NexusAudioFormat? = null
    private var dataBytes = 0L
    private var startedElapsedRealtimeMs = 0L
    private var lastCheckpointElapsedRealtimeMs = 0L
    private var stopRequested = false
    private var statusLine = "Готов к записи"

    private val ticker = object : Runnable {
        override fun run() {
            if (!isNexusSessionOpen) return
            render(show = false)
            if (isBusy()) mainHandler.postDelayed(this, UI_TICK_MS)
        }
    }

    private val startTimeout = Runnable {
        val pending = synchronized(lock) {
            if (state != RecorderState.STARTING) return@Runnable
            statusLine = "Микрофон очков не ответил"
            audioSession
        }
        Log.w(TAG, "Recorder audio acquire timed out")
        // stop() is intentionally harmless while the SDK lease is still PENDING. We close the sink
        // now; if a stale grant arrives later its callback sees IDLE and releases that lease at once.
        pending?.stop()
        finalizeRecording(interrupted = true)
    }

    override fun retainNexusAudioOnClose(): Boolean = isBusy()

    override fun retainNexusForegroundOnClose(): Boolean = isBusy()

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        render(show = true)
        if (isBusy()) {
            mainHandler.removeCallbacks(ticker)
            mainHandler.post(ticker)
        }
    }

    override fun onNexusClose() {
        mainHandler.removeCallbacks(ticker)
        surface?.hide()
        surface = null
        // Recording intentionally survives this UI close. The base service retains both the raw
        // audio lease and foreground anchor while [isBusy] is true.
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> when (currentState()) {
                RecorderState.IDLE -> startRecording()
                RecorderState.RECORDING -> stopRecording()
                RecorderState.STARTING,
                RecorderState.STOPPING,
                -> Unit
            }
            KeyEvent.KEYCODE_BACK -> {
                surface?.hide()
                surface = null
            }
            else -> Unit
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(startTimeout)
        // If Android destroys the service, preserve whatever reached Downloads. The checkpointed
        // header already makes most of the file playable; this closes it with the latest byte count.
        finalizeRecording(interrupted = true)
        super.onDestroy()
    }

    private fun startRecording() {
        synchronized(lock) {
            if (state != RecorderState.IDLE) return
            state = RecorderState.STARTING
            stopRequested = false
            statusLine = "Подключаю микрофон очков…"
        }
        render(show = false)

        val newSink = createDownloadsSink()
        if (newSink == null) {
            synchronized(lock) {
                state = RecorderState.IDLE
                statusLine = "Не удалось создать WAV в Загрузках"
            }
            render(show = false)
            return
        }
        synchronized(lock) {
            sink = newSink
            dataBytes = 0L
            audioFormat = null
            startedElapsedRealtimeMs = 0L
            lastCheckpointElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }

        var createdSession: NexusAudioSession? = null
        val callbacks = object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                if (!isSupported(format)) {
                    Log.w(
                        TAG,
                        "Unsupported glasses audio format: ${format.sampleRate}/${format.channels}/${format.encoding}",
                    )
                    synchronized(lock) { statusLine = "Неподдерживаемый формат аудио" }
                    createdSession?.stop()
                    return
                }
                val accepted = synchronized(lock) {
                    if (audioSession !== createdSession || state != RecorderState.STARTING) {
                        false
                    } else {
                        audioFormat = format
                        startedElapsedRealtimeMs = SystemClock.elapsedRealtime()
                        lastCheckpointElapsedRealtimeMs = startedElapsedRealtimeMs
                        state = RecorderState.RECORDING
                        statusLine = "Запись идёт"
                        writeHeaderLocked(sync = true)
                        true
                    }
                }
                if (!accepted) {
                    createdSession?.stop()
                    return
                }
                mainHandler.removeCallbacks(startTimeout)
                mainHandler.removeCallbacks(ticker)
                mainHandler.post(ticker)
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                if (pcm.isEmpty()) return
                synchronized(lock) {
                    if (audioSession !== createdSession || state != RecorderState.RECORDING) return
                    val currentSink = sink ?: return
                    runCatching {
                        currentSink.output.write(pcm)
                        dataBytes += pcm.size.toLong()
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastCheckpointElapsedRealtimeMs >= CHECKPOINT_MS) {
                            writeHeaderLocked(sync = true)
                            lastCheckpointElapsedRealtimeMs = now
                        }
                    }.onFailure { failure ->
                        Log.e(TAG, "Recorder WAV write failed", failure)
                        statusLine = "Ошибка записи WAV"
                        mainHandler.post { createdSession?.stop() }
                    }
                }
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                val requested = synchronized(lock) {
                    if (audioSession === createdSession) audioSession = null
                    stopRequested
                }
                Log.i(TAG, "Recorder audio stopped reason=$reason requested=$requested")
                finalizeRecording(interrupted = !requested)
            }
        }

        val session = nexusAudioSession(callbacks)
        createdSession = session
        synchronized(lock) { audioSession = session }
        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (result != NexusSdkResult.SENT) {
            Log.w(TAG, "Recorder audio acquire rejected: $result")
            synchronized(lock) {
                if (audioSession === session) audioSession = null
                statusLine = "Микрофон очков недоступен"
            }
            finalizeRecording(interrupted = true)
            return
        }
        mainHandler.removeCallbacks(startTimeout)
        mainHandler.postDelayed(startTimeout, START_TIMEOUT_MS)
    }

    private fun stopRecording() {
        val session = synchronized(lock) {
            if (state != RecorderState.RECORDING) return
            state = RecorderState.STOPPING
            stopRequested = true
            statusLine = "Сохраняю WAV…"
            audioSession
        }
        render(show = false)
        if (session != null) {
            session.stop()
        } else {
            finalizeRecording(interrupted = false)
        }
    }

    private fun finalizeRecording(interrupted: Boolean) {
        val snapshot = synchronized(lock) {
            if (state == RecorderState.IDLE && sink == null) return
            state = RecorderState.IDLE
            mainHandler.removeCallbacks(startTimeout)
            val value = FinalizeSnapshot(
                sink = sink,
                format = audioFormat,
                dataBytes = dataBytes,
                interrupted = interrupted,
            )
            sink = null
            audioFormat = null
            audioSession = null
            dataBytes = 0L
            startedElapsedRealtimeMs = 0L
            lastCheckpointElapsedRealtimeMs = 0L
            stopRequested = false
            value
        }

        val saved = finalizeSink(snapshot)
        synchronized(lock) {
            statusLine = when {
                saved && snapshot.interrupted -> "Запись прервана • WAV сохранён"
                saved -> "WAV сохранён в Загрузки"
                snapshot.dataBytes <= 0L -> "Аудио не получено"
                else -> "Не удалось завершить WAV"
            }
        }
        mainHandler.removeCallbacks(ticker)
        if (isNexusSessionOpen) {
            mainHandler.post { render(show = false) }
        } else {
            stopNexusSessionForeground()
        }
    }

    private fun finalizeSink(snapshot: FinalizeSnapshot): Boolean {
        val currentSink = snapshot.sink ?: return false
        val format = snapshot.format
        if (format == null || snapshot.dataBytes <= 0L) {
            runCatching { currentSink.output.close() }
            runCatching { currentSink.descriptor.close() }
            runCatching { contentResolver.delete(currentSink.uri, null, null) }
            return false
        }
        return runCatching {
            rewriteWavHeader(
                output = currentSink.output,
                dataBytes = snapshot.dataBytes,
                sampleRate = format.sampleRate,
                channels = format.channels,
                sync = true,
            )
            currentSink.output.close()
            currentSink.descriptor.close()
            true
        }.onFailure { failure ->
            Log.e(TAG, "Could not finalize recorder WAV", failure)
            runCatching { currentSink.output.close() }
            runCatching { currentSink.descriptor.close() }
        }.getOrDefault(false)
    }

    private fun createDownloadsSink(): Sink? = runCatching {
        val displayName = "BuildGround_Recording_${FILE_STAMP.format(Date())}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_WAV)
            put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")
        try {
            val descriptor = contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Could not open Downloads WAV")
            val output = FileOutputStream(descriptor.fileDescriptor)
            Sink(uri, displayName, descriptor, output)
        } catch (failure: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw failure
        }
    }.onFailure { failure ->
        Log.e(TAG, "Could not create Downloads WAV", failure)
    }.getOrNull()

    private fun writeHeaderLocked(sync: Boolean) {
        val currentSink = sink ?: return
        val format = audioFormat ?: return
        rewriteWavHeader(
            output = currentSink.output,
            dataBytes = dataBytes,
            sampleRate = format.sampleRate,
            channels = format.channels,
            sync = sync,
        )
    }

    private fun rewriteWavHeader(
        output: FileOutputStream,
        dataBytes: Long,
        sampleRate: Int,
        channels: Int,
        sync: Boolean,
    ) {
        val safeDataBytes = dataBytes.coerceIn(0L, 0xffff_ffffL)
        val header = wavHeader(
            dataBytes = safeDataBytes,
            sampleRate = sampleRate,
            channels = channels,
        )
        val channel = output.channel
        channel.position(0L)
        channel.write(ByteBuffer.wrap(header))
        channel.position(WAV_HEADER_BYTES.toLong() + dataBytes)
        output.flush()
        if (sync) output.fd.sync()
    }

    private fun render(show: Boolean) {
        val currentSurface = surface ?: return
        val presentation = synchronized(lock) {
            val elapsed = if (startedElapsedRealtimeMs > 0L) {
                ((SystemClock.elapsedRealtime() - startedElapsedRealtimeMs).coerceAtLeast(0L) / 1_000L)
            } else {
                0L
            }
            val elapsedText = "%02d:%02d".format(Locale.US, elapsed / 60L, elapsed % 60L)
            when (state) {
                RecorderState.IDLE -> Triple(
                    listOf(statusLine, "Файл: Downloads/BuildGround_Recording_…wav"),
                    "Нажмите • начать запись",
                    "idle:$statusLine",
                )
                RecorderState.STARTING -> Triple(
                    listOf(statusLine),
                    "Подождите…",
                    "starting",
                )
                RecorderState.RECORDING -> Triple(
                    listOf("● ЗАПИСЬ  $elapsedText", "WAV пишется прямо в Загрузки"),
                    "Нажмите • остановить",
                    "recording:$elapsedText",
                )
                RecorderState.STOPPING -> Triple(
                    listOf(statusLine),
                    "Сохраняю…",
                    "stopping",
                )
            }
        }
        val card = NexusCard(
            title = "Recorder",
            lines = presentation.first,
            footer = presentation.second,
            contentKey = presentation.third,
            handlesBack = true,
        )
        if (show) currentSurface.showCard(card) else currentSurface.updateCard(card)
    }

    private fun currentState(): RecorderState = synchronized(lock) { state }

    private fun isBusy(): Boolean = synchronized(lock) { state != RecorderState.IDLE }

    private fun isSupported(format: NexusAudioFormat): Boolean =
        format.sampleRate > 0 &&
            format.channels > 0 &&
            format.encoding.equals("pcm16le", ignoreCase = true)

    companion object {
        private const val TAG = "NexusRecorder"
        private const val SURFACE_ID = "recorder.main"
        private const val MIME_WAV = "audio/wav"
        private const val WAV_HEADER_BYTES = 44
        private const val CHECKPOINT_MS = 2_000L
        private const val UI_TICK_MS = 1_000L
        private const val START_TIMEOUT_MS = 10_000L
        private val FILE_STAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

        internal fun wavHeader(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
            require(dataBytes in 0L..0xffff_ffffL)
            require(sampleRate > 0)
            require(channels > 0)
            val bitsPerSample = 16
            val blockAlign = channels * bitsPerSample / 8
            val byteRate = sampleRate * blockAlign
            return ByteBuffer.allocate(WAV_HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt((36L + dataBytes).coerceAtMost(0xffff_ffffL).toInt())
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1.toShort())
                    putShort(channels.toShort())
                    putInt(sampleRate)
                    putInt(byteRate)
                    putShort(blockAlign.toShort())
                    putShort(bitsPerSample.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(dataBytes.toInt())
                }
                .array()
        }
    }
}
