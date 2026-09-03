from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
SERVICE = SRC / "AssistantPluginService.kt"
STORE = SRC / "AssistantMeetingStore.kt"
ACTIVITY = SRC / "AssistantMeetingsActivity.kt"
MANIFEST = ROOT / "plugins/assistant/src/main/AndroidManifest.xml"
XML_DIR = ROOT / "plugins/assistant/src/main/res/xml"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:140]!r}; found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# --------------------------------------------------------------------------- audio recorder
(SRC / "AssistantMeetingAudioRecorder.kt").write_text(r'''package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-tolerant app-private meeting audio spool.
 *
 * The STT transport remains the microphone owner. We only append the exact PCM frames tee'd by
 * Nexus, so recording never competes for a second microphone lease. Each append closes the file,
 * making already-received audio survive Assistant/Nexus process recreation.
 */
internal class AssistantMeetingAudioRecorder(
    context: Context,
    private val meetingStore: AssistantMeetingStore,
) {
    private val spool = File(context.filesDir, "assistant-meeting-active.pcm")
    private val lock = Any()

    fun startFresh() = synchronized(lock) {
        spool.parentFile?.mkdirs()
        if (spool.exists()) spool.delete()
        spool.createNewFile()
    }

    fun resume() = synchronized(lock) {
        spool.parentFile?.mkdirs()
        if (!spool.exists()) spool.createNewFile()
    }

    fun append(
        pcm: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        encoding: String,
    ) = synchronized(lock) {
        if (pcm.isEmpty()) return@synchronized
        if (sampleRateHz != SAMPLE_RATE_HZ || channels != CHANNELS || !encoding.equals("pcm16le", true)) {
            return@synchronized
        }
        spool.parentFile?.mkdirs()
        FileOutputStream(spool, true).use { it.write(pcm) }
    }

    fun finish(meetingId: String): File? = synchronized(lock) {
        if (!spool.isFile || spool.length() <= 0L) {
            spool.delete()
            return@synchronized null
        }
        val result = meetingStore.saveMeetingAudioPcm(
            meetingId = meetingId,
            sourcePcm = spool,
            sampleRateHz = SAMPLE_RATE_HZ,
            channels = CHANNELS,
            bitsPerSample = BITS_PER_SAMPLE,
        )
        if (result != null) spool.delete()
        result
    }

    fun cancel() = synchronized(lock) {
        spool.delete()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
    }
}
''', encoding="utf-8")

# --------------------------------------------------------------------------- archive store
replace_once(
    STORE,
    "    val hasProtocol: Boolean,\n"
    ")\n\n"
    "internal data class AssistantMeetingArchive(",
    "    val hasProtocol: Boolean,\n"
    "    val hasAudio: Boolean,\n"
    ")\n\n"
    "internal data class AssistantMeetingArchive(",
)
replace_once(
    STORE,
    "                        hasProtocol = !archive.protocol.isNullOrBlank(),\n"
    "                    )\n",
    "                        hasProtocol = !archive.protocol.isNullOrBlank(),\n"
    "                        hasAudio = File(directory, \"audio.wav\").isFile,\n"
    "                    )\n",
)
replace_once(
    STORE,
    "    fun deleteMeeting(meetingId: String): Boolean = synchronized(fileLock) {\n",
    r'''    fun meetingAudioFile(meetingId: String): File? = synchronized(fileLock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized null
        File(File(archiveRoot, meetingId), "audio.wav")
            .takeIf { file -> file.isFile && file.length() > 44L }
    }

    fun saveMeetingAudioPcm(
        meetingId: String,
        sourcePcm: File,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int,
    ): File? = synchronized(fileLock) {
        if (!MEETING_ID.matches(meetingId) || !sourcePcm.isFile || sourcePcm.length() <= 0L) {
            return@synchronized null
        }
        runCatching {
            val directory = File(archiveRoot, meetingId).apply { mkdirs() }
            val target = File(directory, "audio.wav")
            val dataSize = sourcePcm.length()
            require(dataSize <= 0x7fffffffL) { "Meeting audio is too large for WAV" }
            val byteRate = sampleRateHz * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val header = java.nio.ByteBuffer.allocate(44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt((36L + dataSize).toInt())
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1.toShort())
                    putShort(channels.toShort())
                    putInt(sampleRateHz)
                    putInt(byteRate)
                    putShort(blockAlign.toShort())
                    putShort(bitsPerSample.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(dataSize.toInt())
                }
                .array()
            java.io.FileOutputStream(target, false).use { output ->
                output.write(header)
                sourcePcm.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            target
        }.onFailure(::logFailure).getOrNull()
    }

    fun deleteMeeting(meetingId: String): Boolean = synchronized(fileLock) {
''',
)

# --------------------------------------------------------------------------- service hooks
replace_once(
    SERVICE,
    "    private val meetingRecorder by lazy {\n"
    "        AssistantMeetingRecorder(persistence = meetingStore)\n"
    "    }\n",
    "    private val meetingRecorder by lazy {\n"
    "        AssistantMeetingRecorder(persistence = meetingStore)\n"
    "    }\n"
    "    private val meetingAudioRecorder by lazy {\n"
    "        AssistantMeetingAudioRecorder(applicationContext, meetingStore)\n"
    "    }\n",
)
replace_once(
    SERVICE,
    "        if (meetingRecorder.active) {\n"
    "            meetingRearmPending = true\n",
    "        if (meetingRecorder.active) {\n"
    "            meetingAudioRecorder.resume()\n"
    "            meetingRearmPending = true\n",
)
replace_once(
    SERVICE,
    "            meetingRecorder.start()\n"
    "            meetingRearmPending = true\n",
    "            meetingRecorder.start()\n"
    "            meetingAudioRecorder.startFresh()\n"
    "            meetingRearmPending = true\n",
)
replace_once(
    SERVICE,
    "            val meeting = meetingRecorder.finish()\n"
    "            if (meeting == null || meeting.segments.isEmpty()) {\n",
    "            val meeting = meetingRecorder.finish()\n"
    "            if (meeting != null) meetingAudioRecorder.finish(meeting.id)\n"
    "            if (meeting == null || meeting.segments.isEmpty()) {\n",
)
# Explicit Back/cancel remains destructive by design.
replace_once(
    SERVICE,
    "            meetingRecorder.cancel()\n"
    "            resetCapture()\n"
    "            surface?.hide()\n",
    "            meetingRecorder.cancel()\n"
    "            meetingAudioRecorder.cancel()\n"
    "            resetCapture()\n"
    "            surface?.hide()\n",
)
# STT PCM tee from Nexus 1.4.6.
replace_once(
    SERVICE,
    "            override fun onSpeechPartial(text: String) {\n",
    "            override fun onSpeechAudioPcm(\n"
    "                pcm: ByteArray,\n"
    "                sampleRateHz: Int,\n"
    "                channels: Int,\n"
    "                encoding: String,\n"
    "            ) {\n"
    "                if (generation != captureGeneration || !captureActive || !meetingRecorder.active) return\n"
    "                meetingAudioRecorder.append(pcm, sampleRateHz, channels, encoding)\n"
    "            }\n\n"
    "            override fun onSpeechPartial(text: String) {\n",
)
# Raw-capture fallback is also archived when a meeting is active.
replace_once(
    SERVICE,
    "                pcmBuffer.write(pcm)\n"
    "            }\n\n"
    "            override fun onAudioStopped",
    "                pcmBuffer.write(pcm)\n"
    "                if (meetingRecorder.active) {\n"
    "                    val format = audioFormat\n"
    "                    if (format != null) {\n"
    "                        meetingAudioRecorder.append(\n"
    "                            pcm,\n"
    "                            format.sampleRateHz,\n"
    "                            format.channels,\n"
    "                            \"pcm16le\",\n"
    "                        )\n"
    "                    }\n"
    "                }\n"
    "            }\n\n"
    "            override fun onAudioStopped",
)

# --------------------------------------------------------------------------- phone Meetings UI
replace_once(
    ACTIVITY,
    "import android.content.Intent\n",
    "import android.content.Intent\n"
    "import android.content.ContentValues\n"
    "import android.media.MediaPlayer\n"
    "import android.os.Environment\n"
    "import android.provider.MediaStore\n"
    "import androidx.core.content.FileProvider\n"
    "import java.io.File\n",
)
replace_once(
    ACTIVITY,
    "        val brief = meeting.protocol ?: \"Brief is unavailable. The transcript is preserved below.\"\n\n"
    "        val body = LinearLayout(this).apply {\n"
    "            orientation = LinearLayout.VERTICAL\n",
    "        val brief = meeting.protocol ?: \"Brief is unavailable. The transcript is preserved below.\"\n"
    "        val audioFile = meetingStore.meetingAudioFile(meeting.id)\n\n"
    "        val body = LinearLayout(this).apply {\n"
    "            orientation = LinearLayout.VERTICAL\n"
    "            if (audioFile != null) {\n"
    "                addView(NexusUi.metaLabel(this@AssistantMeetingsActivity, \"AUDIO\", NexusUi.GREEN), NexusUi.block())\n"
    "                addView(BusTheme.gap(this@AssistantMeetingsActivity, 5))\n"
    "                addView(\n"
    "                    NexusUi.card(this@AssistantMeetingsActivity).apply {\n"
    "                        addView(NexusUi.rowSub(this@AssistantMeetingsActivity, audioMeta(audioFile)), NexusUi.block())\n"
    "                        addView(\n"
    "                            LinearLayout(this@AssistantMeetingsActivity).apply {\n"
    "                                gravity = Gravity.END\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Play\").apply { setOnClickListener { playAudio(audioFile) } })\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Save\").apply { setOnClickListener { saveAudio(audioFile, meeting.id) } })\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Share\").apply { setOnClickListener { shareAudio(audioFile) } })\n"
    "                            },\n"
    "                            NexusUi.block(),\n"
    "                        )\n"
    "                    },\n"
    "                    NexusUi.block(),\n"
    "                )\n"
    "                addView(BusTheme.gap(this@AssistantMeetingsActivity, 18))\n"
    "            }\n",
)
replace_once(
    ACTIVITY,
    "    private fun copyText(label: String, text: String) {\n",
    r'''    private fun audioMeta(file: File): String {
        val bytes = (file.length() - 44L).coerceAtLeast(0L)
        val seconds = bytes / (AssistantMeetingAudioRecorder.SAMPLE_RATE_HZ * 2L)
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        val mb = file.length().toDouble() / (1024.0 * 1024.0)
        return "%d:%02d · %.1f MB · WAV 16 kHz mono".format(Locale.US, minutes, remainder, mb)
    }

    private fun playAudio(file: File) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { player -> player.start() }
                setOnCompletionListener { player -> player.release() }
                setOnErrorListener { player, _, _ -> player.release(); true }
                prepareAsync()
            }
        }.onFailure { toast("Audio playback failed.") }
    }

    private fun saveAudio(file: File, meetingId: String) {
        Thread {
            val ok = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "meeting-$meetingId.wav")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BuildGround")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Cannot create Downloads entry")
                contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Cannot open Downloads output")
                true
            }.getOrDefault(false)
            runOnUiThread { toast(if (ok) "Saved to Downloads/BuildGround." else "Audio save failed.") }
        }.start()
    }

    private fun shareAudio(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "audio/wav"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share meeting audio",
                ),
            )
        }.onFailure { toast("Audio share failed.") }
    }

    private fun copyText(label: String, text: String) {
''',
)
replace_once(
    ACTIVITY,
    "        val brief = if (meeting.hasProtocol) \"brief ready\" else \"transcript only\"\n"
    "        val fragments = if (meeting.segmentCount == 1) \"1 fragment\" else \"${meeting.segmentCount} fragments\"\n"
    "        return \"$brief · $fragments · ${duration} min\"\n",
    "        val brief = if (meeting.hasProtocol) \"brief ready\" else \"transcript only\"\n"
    "        val audio = if (meeting.hasAudio) \"audio ready\" else \"no audio\"\n"
    "        val fragments = if (meeting.segmentCount == 1) \"1 fragment\" else \"${meeting.segmentCount} fragments\"\n"
    "        return \"$audio · $brief · $fragments · ${duration} min\"\n",
)

# --------------------------------------------------------------------------- secure URI export
replace_once(
    MANIFEST,
    "        <service\n            android:name=\".AssistantPluginService\"\n",
    "        <provider\n"
    "            android:name=\"androidx.core.content.FileProvider\"\n"
    "            android:authorities=\"${applicationId}.files\"\n"
    "            android:exported=\"false\"\n"
    "            android:grantUriPermissions=\"true\">\n"
    "            <meta-data\n"
    "                android:name=\"android.support.FILE_PROVIDER_PATHS\"\n"
    "                android:resource=\"@xml/assistant_file_paths\" />\n"
    "        </provider>\n\n"
    "        <service\n            android:name=\".AssistantPluginService\"\n",
)
XML_DIR.mkdir(parents=True, exist_ok=True)
(XML_DIR / "assistant_file_paths.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<paths xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <files-path name="meeting_audio" path="." />\n'
    '</paths>\n',
    encoding="utf-8",
)

# Fail closed.
checks = {
    SERVICE: ["AssistantMeetingAudioRecorder", "onSpeechAudioPcm", "meetingAudioRecorder.finish(meeting.id)"],
    STORE: ["hasAudio: Boolean", "saveMeetingAudioPcm", "meetingAudioFile"],
    ACTIVITY: ["AUDIO", "playAudio", "saveAudio", "shareAudio"],
    MANIFEST: ["androidx.core.content.FileProvider", "assistant_file_paths"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"Missing Assistant 1.5.6 marker in {path}: {marker}")
