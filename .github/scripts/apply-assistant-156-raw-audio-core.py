from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant"
MODE = SRC / "AssistantMeetingMode.kt"
STORE = SRC / "AssistantMeetingStore.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:140]!r}; found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Stable meeting id is needed by the independent audio contour before transcript finalization.
replace_once(
    MODE,
    "    val active: Boolean\n"
    "        get() = startedAt != null\n\n"
    "    val segmentCount: Int\n",
    "    val active: Boolean\n"
    "        get() = startedAt != null\n\n"
    "    val id: String?\n"
    "        get() = meetingId\n\n"
    "    val segmentCount: Int\n",
)

# Replace the 1.5.6 prototype spool with a meeting-id-bound, crash-resumable spool.
(SRC / "AssistantMeetingAudioRecorder.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.os.SystemClock
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import java.io.File
import java.io.FileOutputStream

/**
 * App-private source-audio contour for Meeting Mode.
 *
 * PCM is persisted before any STT work. Network/provider/STT failures therefore cannot damage the
 * source recording. A process recreation reopens the same meeting-id spool and marks it recovered.
 */
internal class AssistantMeetingAudioRecorder internal constructor(
    filesDir: File,
    private val meetingStore: AssistantMeetingStore,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(context: Context, meetingStore: AssistantMeetingStore) : this(
        filesDir = context.applicationContext.filesDir,
        meetingStore = meetingStore,
    )

    private val spoolRoot = File(filesDir, SPOOL_DIR_NAME)
    private val lock = Any()
    private var activeMeetingId: String? = null
    private var output: FileOutputStream? = null
    private var lastSyncMs = 0L
    private var recoveredActive = false

    fun startFresh(meetingId: String): Boolean = synchronized(lock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized false
        closeLocked()
        spoolRoot.mkdirs()
        val spool = spoolFile(meetingId)
        if (spool.exists() && !spool.delete()) return@synchronized false
        recoveredActive = false
        openLocked(meetingId, append = false)
    }

    fun resume(meetingId: String): Boolean = synchronized(lock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized false
        if (activeMeetingId == meetingId && output != null) return@synchronized true
        closeLocked()
        spoolRoot.mkdirs()
        val spool = spoolFile(meetingId)
        recoveredActive = spool.isFile && spool.length() > 0L
        openLocked(meetingId, append = true)
    }

    fun append(pcm: ByteArray, format: NexusAudioFormat): Boolean = synchronized(lock) {
        if (pcm.isEmpty() || output == null || activeMeetingId == null) return@synchronized false
        if (!isSupported(format)) return@synchronized false
        runCatching {
            output?.write(pcm)
            val now = elapsedRealtimeMs()
            if (now - lastSyncMs >= SYNC_INTERVAL_MS) {
                output?.flush()
                output?.fd?.sync()
                lastSyncMs = now
            }
            true
        }.getOrDefault(false)
    }

    fun flush() = synchronized(lock) {
        runCatching {
            output?.flush()
            output?.fd?.sync()
            lastSyncMs = elapsedRealtimeMs()
        }
    }

    fun finish(meetingId: String): File? = synchronized(lock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized null
        val wasRecovered = recoveredActive
        if (activeMeetingId == meetingId) closeLocked()
        val spool = spoolFile(meetingId)
        if (!spool.isFile || spool.length() <= 0L) {
            spool.delete()
            resetStateLocked()
            return@synchronized null
        }
        val result = meetingStore.saveMeetingAudioPcm(
            meetingId = meetingId,
            sourcePcm = spool,
            sampleRateHz = SAMPLE_RATE_HZ,
            channels = CHANNELS,
            bitsPerSample = BITS_PER_SAMPLE,
        )
        if (result != null) {
            if (wasRecovered) meetingStore.markMeetingAudioRecovered(meetingId)
            spool.delete()
        }
        resetStateLocked()
        result
    }

    fun cancel() = synchronized(lock) {
        val meetingId = activeMeetingId
        closeLocked()
        if (meetingId != null) spoolFile(meetingId).delete()
        resetStateLocked()
    }

    /** Finalize audio left after transcript archival but before WAV finalization. */
    fun recoverCompleted(activeId: String?): Int = synchronized(lock) {
        if (!spoolRoot.isDirectory) return@synchronized 0
        var recovered = 0
        spoolRoot.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SPOOL_SUFFIX) }
            ?.forEach { spool ->
                val meetingId = spool.name.removeSuffix(SPOOL_SUFFIX)
                if (!MEETING_ID.matches(meetingId) || meetingId == activeId) return@forEach
                if (spool.length() <= 0L || meetingStore.meeting(meetingId) == null) return@forEach
                val result = meetingStore.saveMeetingAudioPcm(
                    meetingId = meetingId,
                    sourcePcm = spool,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    channels = CHANNELS,
                    bitsPerSample = BITS_PER_SAMPLE,
                )
                if (result != null) {
                    meetingStore.markMeetingAudioRecovered(meetingId)
                    if (spool.delete()) recovered += 1
                }
            }
        recovered
    }

    private fun openLocked(meetingId: String, append: Boolean): Boolean = runCatching {
        val spool = spoolFile(meetingId)
        spool.parentFile?.mkdirs()
        output = FileOutputStream(spool, append)
        activeMeetingId = meetingId
        lastSyncMs = elapsedRealtimeMs()
        true
    }.getOrElse {
        output = null
        activeMeetingId = null
        false
    }

    private fun closeLocked() {
        runCatching {
            output?.flush()
            output?.fd?.sync()
        }
        runCatching { output?.close() }
        output = null
        activeMeetingId = null
    }

    private fun resetStateLocked() {
        output = null
        activeMeetingId = null
        lastSyncMs = 0L
        recoveredActive = false
    }

    private fun spoolFile(meetingId: String): File = File(spoolRoot, meetingId + SPOOL_SUFFIX)

    private fun isSupported(format: NexusAudioFormat): Boolean =
        format.sampleRate == SAMPLE_RATE_HZ &&
            format.channels == CHANNELS &&
            format.encoding.equals("pcm16le", ignoreCase = true)

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        private const val SYNC_INTERVAL_MS = 5_000L
        private const val SPOOL_DIR_NAME = "assistant_meeting_audio_active"
        private const val SPOOL_SUFFIX = ".pcm"
        private val MEETING_ID = Regex("m_[a-z0-9]{8}")
    }
}
''', encoding="utf-8")

# Nexus-derived VAD only segments disposable STT copies; the source WAV remains continuous.
(SRC / "AssistantMeetingAudioSegmenter.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.abs

internal data class AssistantMeetingPcmChunk(
    val pcm: ByteArray,
    val format: NexusAudioFormat,
)

internal class AssistantMeetingAudioSegmenter {
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0
    private var chunk = ByteArrayOutputStream()
    private var speechActive = false
    private var lastVoiceAtMs = 0L

    fun reset() {
        preRoll.clear()
        preRollBytes = 0
        chunk = ByteArrayOutputStream()
        speechActive = false
        lastVoiceAtMs = 0L
    }

    fun accept(pcm: ByteArray, elapsedRealtimeMs: Long): ByteArray? {
        if (pcm.isEmpty()) return null
        val voice = isVoice(pcm)
        if (!speechActive) {
            addPreRoll(pcm)
            if (!voice) return null
            speechActive = true
            lastVoiceAtMs = elapsedRealtimeMs
            while (preRoll.isNotEmpty()) chunk.write(preRoll.removeFirst())
            preRollBytes = 0
        } else {
            chunk.write(pcm)
            if (voice) lastVoiceAtMs = elapsedRealtimeMs
        }

        val silenceMs = if (lastVoiceAtMs == 0L) 0L else
            (elapsedRealtimeMs - lastVoiceAtMs).coerceAtLeast(0L)
        return if (
            chunk.size() >= MAX_CHUNK_BYTES ||
            (!voice && silenceMs >= SILENCE_AFTER_SPEECH_MS)
        ) emit() else null
    }

    fun flush(): ByteArray? =
        if (speechActive && chunk.size() >= MIN_STT_BYTES) emit() else {
            reset()
            null
        }

    private fun emit(): ByteArray? {
        val result = chunk.toByteArray().takeIf { it.size >= MIN_STT_BYTES }
        reset()
        return result
    }

    private fun addPreRoll(pcm: ByteArray) {
        val copy = pcm.copyOf()
        preRoll.addLast(copy)
        preRollBytes += copy.size
        while (preRollBytes > PRE_ROLL_BYTES && preRoll.isNotEmpty()) {
            preRollBytes -= preRoll.removeFirst().size
        }
    }

    private fun isVoice(pcm: ByteArray): Boolean {
        var sumAbs = 0L
        var peak = 0
        var samples = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(sample)
            sumAbs += magnitude
            if (magnitude > peak) peak = magnitude
            samples += 1
            index += 2
        }
        val average = if (samples == 0) 0 else (sumAbs / samples).toInt()
        return average >= AVERAGE_ABS_THRESHOLD || peak >= PEAK_ABS_THRESHOLD
    }

    companion object {
        // Same signal thresholds as Nexus Phone VoiceActivityDetector.
        private const val AVERAGE_ABS_THRESHOLD = 350
        private const val PEAK_ABS_THRESHOLD = 2_800
        private const val SILENCE_AFTER_SPEECH_MS = 1_800L
        private const val PRE_ROLL_MS = 500
        private const val MAX_CHUNK_MS = 30_000
        private const val BYTES_PER_MS = AssistantMeetingAudioRecorder.SAMPLE_RATE_HZ * 2 / 1_000
        private const val PRE_ROLL_BYTES = PRE_ROLL_MS * BYTES_PER_MS
        private const val MAX_CHUNK_BYTES = MAX_CHUNK_MS * BYTES_PER_MS
        private const val MIN_STT_BYTES = 3_200
    }
}
''', encoding="utf-8")

# Audio-only deletion must leave transcript/protocol intact.
replace_once(
    STORE,
    "    fun deleteMeeting(meetingId: String): Boolean = synchronized(fileLock) {\n",
    r'''    fun deleteMeetingAudio(meetingId: String): Boolean = synchronized(fileLock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized false
        val directory = File(archiveRoot, meetingId)
        if (!directory.isDirectory) return@synchronized false
        val audio = File(directory, "audio.wav")
        val temp = File(directory, "audio.wav.tmp")
        val recovered = File(directory, "audio.recovered")
        val existed = audio.exists() || temp.exists() || recovered.exists()
        val removed = listOf(audio, temp, recovered).all { !it.exists() || it.delete() }
        existed && removed
    }

    fun markMeetingAudioRecovered(meetingId: String): Boolean = synchronized(fileLock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized false
        val directory = File(archiveRoot, meetingId)
        if (!directory.isDirectory) return@synchronized false
        runCatching {
            File(directory, "audio.recovered").writeText("recovered\n", Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun deleteMeeting(meetingId: String): Boolean = synchronized(fileLock) {
''',
)

# Final WAV is written to a temporary sibling and renamed only after fsync.
replace_once(
    STORE,
    "            java.io.FileOutputStream(target, false).use { output ->\n"
    "                output.write(header)\n"
    "                sourcePcm.inputStream().use { input -> input.copyTo(output) }\n"
    "                output.fd.sync()\n"
    "            }\n"
    "            target\n",
    "            val temp = File(directory, \"audio.wav.tmp\")\n"
    "            if (temp.exists() && !temp.delete()) error(\"Cannot clear stale audio temp\")\n"
    "            java.io.FileOutputStream(temp, false).use { output ->\n"
    "                output.write(header)\n"
    "                sourcePcm.inputStream().use { input -> input.copyTo(output) }\n"
    "                output.flush()\n"
    "                output.fd.sync()\n"
    "            }\n"
    "            if (target.exists() && !target.delete()) error(\"Cannot replace meeting audio\")\n"
    "            if (!temp.renameTo(target)) error(\"Cannot finalize meeting audio\")\n"
    "            target\n",
)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "AssistantMeetingAudioSegmenterTest.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantMeetingAudioSegmenterTest {
    @Test
    fun `speech followed by silence emits stt chunk`() {
        val segmenter = AssistantMeetingAudioSegmenter()
        var now = 1_000L
        repeat(5) {
            assertNull(segmenter.accept(pcm(5_000), now))
            now += 100L
        }
        var emitted: ByteArray? = null
        repeat(25) {
            emitted = emitted ?: segmenter.accept(pcm(0), now)
            now += 100L
        }
        assertNotNull(emitted)
    }

    @Test
    fun `silence only does not emit stt chunk`() {
        val segmenter = AssistantMeetingAudioSegmenter()
        var now = 1_000L
        repeat(50) {
            assertNull(segmenter.accept(pcm(0), now))
            now += 100L
        }
        assertNull(segmenter.flush())
    }

    private fun pcm(amplitude: Int): ByteArray {
        val samples = AssistantMeetingAudioRecorder.SAMPLE_RATE_HZ / 10
        val value = amplitude.toShort().toInt()
        return ByteArray(samples * 2).also { bytes ->
            var index = 0
            repeat(samples) {
                bytes[index] = (value and 0xff).toByte()
                bytes[index + 1] = ((value ushr 8) and 0xff).toByte()
                index += 2
            }
        }
    }
}
''', encoding="utf-8")

for path, markers in {
    MODE: ["val id: String?"],
    STORE: ["deleteMeetingAudio", "markMeetingAudioRecovered", "audio.wav.tmp"],
    SRC / "AssistantMeetingAudioRecorder.kt": ["assistant_meeting_audio_active", "recoverCompleted"],
    SRC / "AssistantMeetingAudioSegmenter.kt": ["AVERAGE_ABS_THRESHOLD = 350", "SILENCE_AFTER_SPEECH_MS"],
}.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"Missing Assistant 1.5.6 audio-core marker in {path}: {marker}")
