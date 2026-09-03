from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantMeetingsActivity.kt"


def replace_once(old: str, new: str) -> None:
    text = ACTIVITY.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one AssistantMeetingsActivity match, found {count}: {old[:150]!r}")
    ACTIVITY.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "import android.media.MediaPlayer\n",
    "import android.media.MediaPlayer\n"
    "import android.widget.Button\n",
)

replace_once(
    "    private val meetingStore by lazy { AssistantMeetingStore(applicationContext) }\n"
    "    private lateinit var listColumn: LinearLayout\n",
    "    private val meetingStore by lazy { AssistantMeetingStore(applicationContext) }\n"
    "    private lateinit var listColumn: LinearLayout\n"
    "    private var audioPlayer: MediaPlayer? = null\n"
    "    private var audioPlayerFile: File? = null\n"
    "    private var audioPlayButton: Button? = null\n",
)

replace_once(
    "    override fun onResume() {\n"
    "        super.onResume()\n"
    "        renderMeetings()\n"
    "    }\n",
    "    override fun onResume() {\n"
    "        super.onResume()\n"
    "        renderMeetings()\n"
    "    }\n\n"
    "    override fun onStop() {\n"
    "        releaseAudioPlayer()\n"
    "        super.onStop()\n"
    "    }\n",
)

replace_once(
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Play\").apply { setOnClickListener { playAudio(audioFile) } })\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Save\").apply { setOnClickListener { saveAudio(audioFile, meeting.id) } })\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Share\").apply { setOnClickListener { shareAudio(audioFile) } })\n",
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Play\").apply { setOnClickListener { toggleAudio(audioFile, this) } })\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Save\").apply { setOnClickListener { saveAudio(audioFile, meeting.id) } })\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Share\").apply { setOnClickListener { shareAudio(audioFile) } })\n"
    "                                addView(NexusUi.textButton(this@AssistantMeetingsActivity, \"Delete audio\", danger = true).apply {\n"
    "                                    setOnClickListener { confirmDeleteAudio(meeting, dialog) }\n"
    "                                })\n",
)

replace_once(
    r'''    private fun playAudio(file: File) {
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
''',
    r'''    private fun toggleAudio(file: File, button: Button) {
        val current = audioPlayer
        if (current != null && audioPlayerFile == file) {
            runCatching {
                if (current.isPlaying) {
                    current.pause()
                    button.text = "Play"
                } else {
                    current.start()
                    button.text = "Pause"
                }
            }.onFailure {
                releaseAudioPlayer()
                toast("Audio playback failed.")
            }
            return
        }

        releaseAudioPlayer()
        runCatching {
            val player = MediaPlayer()
            audioPlayer = player
            audioPlayerFile = file
            audioPlayButton = button
            button.isEnabled = false
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                button.isEnabled = true
                it.start()
                button.text = "Pause"
            }
            player.setOnCompletionListener {
                runCatching { it.seekTo(0) }
                button.text = "Play"
            }
            player.setOnErrorListener { _, _, _ ->
                releaseAudioPlayer()
                toast("Audio playback failed.")
                true
            }
            player.prepareAsync()
        }.onFailure {
            releaseAudioPlayer()
            toast("Audio playback failed.")
        }
    }

    private fun releaseAudioPlayer() {
        audioPlayButton?.text = "Play"
        audioPlayButton?.isEnabled = true
        audioPlayButton = null
        audioPlayerFile = null
        runCatching { audioPlayer?.stop() }
        runCatching { audioPlayer?.release() }
        audioPlayer = null
    }
''',
)

replace_once(
    "    private fun confirmDeleteMeeting(meeting: AssistantMeetingArchive) {\n",
    r'''    private fun confirmDeleteAudio(meeting: AssistantMeetingArchive, parent: Dialog) {
        confirm(
            title = "Delete meeting audio",
            body = "Only the audio file is removed. Transcript and protocol stay in this meeting.",
            confirmLabel = "Delete audio",
        ) {
            releaseAudioPlayer()
            parent.dismiss()
            mutate({ meetingStore.deleteMeetingAudio(meeting.id) }, "Meeting audio deleted.")
        }
    }

    private fun confirmDeleteMeeting(meeting: AssistantMeetingArchive) {
''',
)

replace_once(
    '            body = "The brief and transcript are removed from this phone. This cannot be undone.",\n',
    '            body = "Audio, brief and transcript are removed from this phone. This cannot be undone.",\n',
)
replace_once(
    '            body = "All $what, briefs and transcripts are removed from this phone. This cannot be undone.",\n',
    '            body = "All $what, audio files, briefs and transcripts are removed from this phone. This cannot be undone.",\n',
)

# Releasing on dialog close prevents playback leaking beyond the meeting card.
replace_once(
    "        dialog.setContentView(panel)\n"
    "        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))\n"
    "        dialog.show()\n"
    "        dialog.window?.setLayout(\n"
    "            (resources.displayMetrics.widthPixels * 0.92f).toInt(),\n"
    "            (resources.displayMetrics.heightPixels * 0.78f).toInt(),\n",
    "        dialog.setContentView(panel)\n"
    "        dialog.setOnDismissListener { releaseAudioPlayer() }\n"
    "        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))\n"
    "        dialog.show()\n"
    "        dialog.window?.setLayout(\n"
    "            (resources.displayMetrics.widthPixels * 0.92f).toInt(),\n"
    "            (resources.displayMetrics.heightPixels * 0.78f).toInt(),\n",
)

text = ACTIVITY.read_text(encoding="utf-8")
for marker in (
    "toggleAudio(audioFile, this)",
    "Delete audio",
    "deleteMeetingAudio(meeting.id)",
    "setOnDismissListener { releaseAudioPlayer() }",
):
    if marker not in text:
        raise SystemExit(f"Missing Assistant 1.5.6 audio UI marker: {marker}")
