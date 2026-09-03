from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant"
STORE = SRC / "AssistantMeetingStore.kt"
SETTINGS = SRC / "AssistantSettingsActivity.kt"
MANIFEST = ROOT / "plugins/assistant/src/main/AndroidManifest.xml"
ACTIVITY = SRC / "AssistantMeetingsActivity.kt"
ARCHIVE_TEST = TEST / "AssistantMeetingArchiveTest.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:140]!r}; found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# --------------------------------------------------------------------------- store API
replace_once(
    STORE,
    "internal data class AssistantMeetingDraft(\n"
    "    val id: String,\n"
    "    val startedAt: ZonedDateTime,\n"
    "    val segments: List<String>,\n"
    ")\n\n",
    "internal data class AssistantMeetingDraft(\n"
    "    val id: String,\n"
    "    val startedAt: ZonedDateTime,\n"
    "    val segments: List<String>,\n"
    ")\n\n"
    "internal data class AssistantMeetingSummary(\n"
    "    val id: String,\n"
    "    val startedAt: ZonedDateTime,\n"
    "    val finishedAt: ZonedDateTime,\n"
    "    val segmentCount: Int,\n"
    "    val hasProtocol: Boolean,\n"
    ")\n\n"
    "internal data class AssistantMeetingArchive(\n"
    "    val id: String,\n"
    "    val startedAt: ZonedDateTime,\n"
    "    val finishedAt: ZonedDateTime,\n"
    "    val segments: List<String>,\n"
    "    val protocol: String?,\n"
    ")\n\n",
)

replace_once(
    STORE,
    "    override fun cancel(meetingId: String?) = synchronized(fileLock) {\n"
    "        if (meetingId == null) return@synchronized\n"
    "        deleteActiveIfSameMeeting(meetingId)\n"
    "    }\n\n"
    "    private val fileLock: Any\n",
    "    override fun cancel(meetingId: String?) = synchronized(fileLock) {\n"
    "        if (meetingId == null) return@synchronized\n"
    "        deleteActiveIfSameMeeting(meetingId)\n"
    "    }\n\n"
    "    fun meetings(): List<AssistantMeetingSummary> = synchronized(fileLock) {\n"
    "        if (!archiveRoot.isDirectory) return@synchronized emptyList()\n"
    "        archiveRoot.listFiles()\n"
    "            ?.asSequence()\n"
    "            ?.filter { directory -> directory.isDirectory && MEETING_ID.matches(directory.name) }\n"
    "            ?.mapNotNull { directory ->\n"
    "                readArchive(directory)?.let { archive ->\n"
    "                    AssistantMeetingSummary(\n"
    "                        id = archive.id,\n"
    "                        startedAt = archive.startedAt,\n"
    "                        finishedAt = archive.finishedAt,\n"
    "                        segmentCount = archive.segments.size,\n"
    "                        hasProtocol = !archive.protocol.isNullOrBlank(),\n"
    "                    )\n"
    "                }\n"
    "            }\n"
    "            ?.sortedByDescending { summary -> summary.finishedAt.toInstant() }\n"
    "            ?.toList()\n"
    "            .orEmpty()\n"
    "    }\n\n"
    "    fun meeting(meetingId: String): AssistantMeetingArchive? = synchronized(fileLock) {\n"
    "        if (!MEETING_ID.matches(meetingId)) return@synchronized null\n"
    "        readArchive(File(archiveRoot, meetingId))\n"
    "    }\n\n"
    "    fun deleteMeeting(meetingId: String): Boolean = synchronized(fileLock) {\n"
    "        if (!MEETING_ID.matches(meetingId)) return@synchronized false\n"
    "        val directory = File(archiveRoot, meetingId)\n"
    "        directory.exists() && directory.deleteRecursively()\n"
    "    }\n\n"
    "    fun deleteAllMeetings(): Int = synchronized(fileLock) {\n"
    "        if (!archiveRoot.isDirectory) return@synchronized 0\n"
    "        archiveRoot.listFiles()\n"
    "            ?.filter { directory -> directory.isDirectory && MEETING_ID.matches(directory.name) }\n"
    "            ?.count { directory -> directory.deleteRecursively() }\n"
    "            ?: 0\n"
    "    }\n\n"
    "    private fun readArchive(directory: File): AssistantMeetingArchive? {\n"
    "        if (!directory.isDirectory || !MEETING_ID.matches(directory.name)) return null\n"
    "        val transcriptFile = File(directory, TRANSCRIPT_JSON_NAME)\n"
    "        if (!transcriptFile.isFile) return null\n"
    "        return runCatching {\n"
    "            val value = JSONObject(transcriptFile.readText(Charsets.UTF_8))\n"
    "            if (value.optInt(JSON_VERSION) != STORE_VERSION) return@runCatching null\n"
    "            val id = value.getString(JSON_ID)\n"
    "            if (id != directory.name || !MEETING_ID.matches(id)) return@runCatching null\n"
    "            val startedAt = ZonedDateTime.parse(value.getString(JSON_STARTED_AT))\n"
    "            val finishedAt = ZonedDateTime.parse(value.getString(JSON_FINISHED_AT))\n"
    "            val values = value.getJSONArray(JSON_SEGMENTS)\n"
    "            val segments = buildList {\n"
    "                for (index in 0 until values.length()) {\n"
    "                    if (size >= MAX_SEGMENTS) break\n"
    "                    values.optString(index)\n"
    "                        .trim()\n"
    "                        .takeIf(String::isNotBlank)\n"
    "                        ?.let { add(it.take(MAX_SEGMENT_CHARS)) }\n"
    "                }\n"
    "            }\n"
    "            val protocol = File(directory, PROTOCOL_MARKDOWN_NAME)\n"
    "                .takeIf(File::isFile)\n"
    "                ?.readText(Charsets.UTF_8)\n"
    "                ?.trim()\n"
    "                ?.takeIf(String::isNotBlank)\n"
    "            AssistantMeetingArchive(\n"
    "                id = id,\n"
    "                startedAt = startedAt,\n"
    "                finishedAt = finishedAt,\n"
    "                segments = segments,\n"
    "                protocol = protocol,\n"
    "            )\n"
    "        }.onFailure(::logFailure).getOrNull()\n"
    "    }\n\n"
    "    private val fileLock: Any\n",
)

# --------------------------------------------------------------------------- phone UI
ACTIVITY.write_text(r'''package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Completed meeting briefs and transcripts kept in Assistant private storage. */
class AssistantMeetingsActivity : Activity() {
    private val meetingStore by lazy { AssistantMeetingStore(applicationContext) }
    private lateinit var listColumn: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        renderMeetings()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantMeetingsActivity,
                    "Meeting briefs and full transcripts are saved on this phone only. " +
                        "Open a meeting to read, copy, share, or delete it.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 18))
            addView(listColumn, NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@AssistantMeetingsActivity,
                    R.drawable.nexus_glyph_assistant,
                    "Meetings",
                    "Briefs & transcripts",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@AssistantMeetingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderMeetings()
    }

    private fun renderMeetings() {
        Thread {
            val meetings = runCatching { meetingStore.meetings() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showMeetings(meetings)
            }
        }.start()
    }

    private fun showMeetings(meetings: List<AssistantMeetingSummary>) {
        listColumn.removeAllViews()
        if (meetings.isEmpty()) {
            listColumn.addView(
                NexusUi.card(this).apply {
                    addView(
                        NexusUi.rowSub(
                            this@AssistantMeetingsActivity,
                            "No meetings yet. Say “начать совещание” on the glasses.",
                        ),
                    )
                },
                NexusUi.block(),
            )
            return
        }

        meetings.forEachIndexed { index, meeting ->
            if (index > 0) listColumn.addView(BusTheme.gap(this, 10))
            listColumn.addView(
                NexusUi.navCard(
                    this,
                    meetingTitle(meeting),
                    meetingMeta(meeting),
                ) { openMeeting(meeting.id) },
                NexusUi.block(),
            )
        }

        listColumn.addView(BusTheme.gap(this, 22))
        listColumn.addView(
            NexusUi.outlinePillButton(this, "Delete all meetings").apply {
                NexusUi.stylePillAsDanger(this@AssistantMeetingsActivity, this)
                setOnClickListener { confirmDeleteAll(meetings.size) }
            },
            NexusUi.block(),
        )
    }

    private fun meetingTitle(meeting: AssistantMeetingSummary): String =
        meeting.startedAt.format(TITLE_FORMAT)

    private fun meetingMeta(meeting: AssistantMeetingSummary): String {
        val duration = Duration.between(meeting.startedAt, meeting.finishedAt)
            .toMinutes()
            .coerceAtLeast(0)
        val brief = if (meeting.hasProtocol) "brief ready" else "transcript only"
        val fragments = if (meeting.segmentCount == 1) "1 fragment" else "${meeting.segmentCount} fragments"
        return "$brief · $fragments · ${duration} min"
    }

    private fun openMeeting(meetingId: String) {
        Thread {
            val meeting = runCatching { meetingStore.meeting(meetingId) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (meeting == null) renderMeetings() else showMeetingDialog(meeting)
            }
        }.start()
    }

    private fun showMeetingDialog(meeting: AssistantMeetingArchive) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val transcript = meeting.segments.mapIndexed { index, segment ->
            "${index + 1}. $segment"
        }.joinToString("\n\n").ifBlank { "[No transcript fragments]" }
        val brief = meeting.protocol ?: "Brief is unavailable. The transcript is preserved below."

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                NexusUi.metaLabel(this@AssistantMeetingsActivity, "BRIEF", NexusUi.GREEN),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 5))
            addView(NexusUi.cardBody(this@AssistantMeetingsActivity, brief), NexusUi.block())
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 18))
            addView(
                NexusUi.metaLabel(this@AssistantMeetingsActivity, "TRANSCRIPT", NexusUi.INK3),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 5))
            addView(NexusUi.cardBody(this@AssistantMeetingsActivity, transcript), NexusUi.block())
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantMeetingsActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantMeetingsActivity, 18),
                NexusUi.dp(this@AssistantMeetingsActivity, 18),
                NexusUi.dp(this@AssistantMeetingsActivity, 18),
                NexusUi.dp(this@AssistantMeetingsActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantMeetingsActivity, meeting.startedAt.format(TITLE_FORMAT)))
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 2))
            addView(
                NexusUi.rowSub(
                    this@AssistantMeetingsActivity,
                    "${meeting.startedAt.format(TIME_FORMAT)} — ${meeting.finishedAt.format(TIME_FORMAT)}",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 14))
            addView(
                ScrollView(this@AssistantMeetingsActivity).apply {
                    isVerticalScrollBarEnabled = true
                    addView(body)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 12))
            addView(
                LinearLayout(this@AssistantMeetingsActivity).apply {
                    gravity = Gravity.END
                    if (!meeting.protocol.isNullOrBlank()) {
                        addView(
                            NexusUi.textButton(this@AssistantMeetingsActivity, "Copy brief").apply {
                                setOnClickListener { copyText("Meeting brief", meeting.protocol) }
                            },
                        )
                        addView(
                            NexusUi.textButton(this@AssistantMeetingsActivity, "Share").apply {
                                setOnClickListener { shareText(meeting.protocol) }
                            },
                        )
                    } else {
                        addView(
                            NexusUi.textButton(this@AssistantMeetingsActivity, "Copy transcript").apply {
                                setOnClickListener { copyText("Meeting transcript", transcript) }
                            },
                        )
                    }
                },
                NexusUi.block(),
            )
            addView(
                LinearLayout(this@AssistantMeetingsActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantMeetingsActivity, "Close").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(this@AssistantMeetingsActivity, "Delete", danger = true).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                confirmDeleteMeeting(meeting)
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.78f).toInt(),
        )
    }

    private fun copyText(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied.")
    }

    private fun shareText(text: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Meeting brief")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, "Share meeting brief"))
    }

    private fun confirmDeleteMeeting(meeting: AssistantMeetingArchive) {
        confirm(
            title = "Delete this meeting",
            body = "The brief and transcript are removed from this phone. This cannot be undone.",
            confirmLabel = "Delete",
        ) {
            mutate({ meetingStore.deleteMeeting(meeting.id) }, "Meeting deleted.")
        }
    }

    private fun confirmDeleteAll(count: Int) {
        val what = if (count == 1) "1 meeting" else "$count meetings"
        confirm(
            title = "Delete all meetings",
            body = "All $what, briefs and transcripts are removed from this phone. This cannot be undone.",
            confirmLabel = "Delete all",
        ) {
            mutate({ meetingStore.deleteAllMeetings() }, "Meetings deleted.")
        }
    }

    private fun mutate(change: () -> Any, confirmation: String) {
        Thread {
            runCatching(change)
            val meetings = runCatching { meetingStore.meetings() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showMeetings(meetings)
                toast(confirmation)
            }
        }.start()
    }

    private fun confirm(
        title: String,
        body: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantMeetingsActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantMeetingsActivity, 18),
                NexusUi.dp(this@AssistantMeetingsActivity, 18),
                NexusUi.dp(this@AssistantMeetingsActivity, 18),
                NexusUi.dp(this@AssistantMeetingsActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantMeetingsActivity, title))
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 6))
            addView(NexusUi.cardBody(this@AssistantMeetingsActivity, body), NexusUi.block())
            addView(BusTheme.gap(this@AssistantMeetingsActivity, 14))
            addView(
                LinearLayout(this@AssistantMeetingsActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantMeetingsActivity, "Cancel").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(
                            this@AssistantMeetingsActivity,
                            confirmLabel,
                            danger = true,
                        ).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                onConfirm()
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val TITLE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.getDefault())
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    }
}
''', encoding="utf-8")

# --------------------------------------------------------------------------- Settings entry point
replace_once(
    SETTINGS,
    "    private lateinit var conversationsSlot: LinearLayout\n"
    "    private lateinit var productivitySlot: LinearLayout\n",
    "    private lateinit var conversationsSlot: LinearLayout\n"
    "    private lateinit var meetingsSlot: LinearLayout\n"
    "    private lateinit var productivitySlot: LinearLayout\n",
)

# Settings renders twice: once after building the screen and again on resume. Integrate both
# passes deterministically so the meetings counter is current in either path.
text = SETTINGS.read_text(encoding="utf-8")
old_render = "        renderConversationSettings()\n        renderPersona()\n"
new_render = "        renderConversationSettings()\n        renderMeetingsCard()\n        renderPersona()\n"
if text.count(new_render) == 2:
    pass
elif text.count(old_render) == 2:
    SETTINGS.write_text(text.replace(old_render, new_render), encoding="utf-8")
else:
    raise SystemExit(
        f"Expected exactly two Assistant settings render passes; "
        f"old={text.count(old_render)} new={text.count(new_render)}"
    )

replace_once(
    SETTINGS,
    "            addView(conversationsSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, \"Memory\"), NexusUi.block())\n",
    "            addView(conversationsSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, \"Meetings\"), NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))\n"
    "            meetingsSlot = LinearLayout(this@AssistantSettingsActivity).apply {\n"
    "                orientation = LinearLayout.VERTICAL\n"
    "            }\n"
    "            addView(meetingsSlot, NexusUi.block())\n"
    "            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n"
    "            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, \"Memory\"), NexusUi.block())\n",
)

replace_once(
    SETTINGS,
    "    private fun conversationsSubtitle(count: Int): String = when (count) {\n"
    "        0 -> \"Nothing saved yet\"\n"
    "        1 -> \"1 saved on this phone\"\n"
    "        else -> \"$count saved on this phone\"\n"
    "    }\n\n"
    "    // ------------------------------------------------------- notes & reminders\n",
    "    private fun conversationsSubtitle(count: Int): String = when (count) {\n"
    "        0 -> \"Nothing saved yet\"\n"
    "        1 -> \"1 saved on this phone\"\n"
    "        else -> \"$count saved on this phone\"\n"
    "    }\n\n"
    "    private fun renderMeetingsCard() {\n"
    "        Thread {\n"
    "            val count = runCatching { AssistantMeetingStore(applicationContext).meetings().size }\n"
    "                .getOrDefault(0)\n"
    "            runOnUiThread {\n"
    "                if (isFinishing || isDestroyed) return@runOnUiThread\n"
    "                meetingsSlot.removeAllViews()\n"
    "                meetingsSlot.addView(\n"
    "                    NexusUi.navCard(\n"
    "                        this,\n"
    "                        \"Meeting archive\",\n"
    "                        meetingsSubtitle(count),\n"
    "                    ) {\n"
    "                        startActivity(Intent(this, AssistantMeetingsActivity::class.java))\n"
    "                    },\n"
    "                    NexusUi.block(),\n"
    "                )\n"
    "            }\n"
    "        }.start()\n"
    "    }\n\n"
    "    private fun meetingsSubtitle(count: Int): String = when (count) {\n"
    "        0 -> \"No saved meetings yet\"\n"
    "        1 -> \"1 brief & transcript saved on this phone\"\n"
    "        else -> \"$count briefs & transcripts saved on this phone\"\n"
    "    }\n\n"
    "    // ------------------------------------------------------- notes & reminders\n",
)

# --------------------------------------------------------------------------- manifest
replace_once(
    MANIFEST,
    "        <activity\n"
    "            android:name=\".AssistantConversationsActivity\"\n"
    "            android:exported=\"false\" />\n\n"
    "        <activity\n"
    "            android:name=\".AssistantProductivityActivity\"\n",
    "        <activity\n"
    "            android:name=\".AssistantConversationsActivity\"\n"
    "            android:exported=\"false\" />\n\n"
    "        <activity\n"
    "            android:name=\".AssistantMeetingsActivity\"\n"
    "            android:exported=\"false\" />\n\n"
    "        <activity\n"
    "            android:name=\".AssistantProductivityActivity\"\n",
)

# --------------------------------------------------------------------------- archive API regression test
ARCHIVE_TEST.write_text(r'''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

class AssistantMeetingArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `completed meeting can be listed opened and deleted through archive API`() {
        val started = ZonedDateTime.of(2026, 9, 3, 8, 15, 0, 0, ZoneId.of("Europe/Moscow"))
        var now = started
        val store = AssistantMeetingStore(
            filesDir = temporaryFolder.root,
            idGenerator = { "m_87654321" },
        )
        val recorder = AssistantMeetingRecorder(persistence = store, now = { now })

        assertTrue(recorder.start())
        assertTrue(recorder.append("Зафиксировали срок до пятницы."))
        assertTrue(recorder.append("Ответственный Иван."))
        now = now.plusMinutes(37)
        val completed = recorder.finish()
        assertNotNull(completed)
        assertTrue(store.saveProtocol("m_87654321", "# Бриф\n\nСрок — пятница."))

        val summaries = store.meetings()
        assertEquals(1, summaries.size)
        assertEquals("m_87654321", summaries.single().id)
        assertEquals(2, summaries.single().segmentCount)
        assertTrue(summaries.single().hasProtocol)

        val archive = store.meeting("m_87654321")
        assertNotNull(archive)
        assertEquals(2, archive!!.segments.size)
        assertTrue(archive.protocol!!.contains("Срок — пятница."))

        assertTrue(store.deleteMeeting("m_87654321"))
        assertTrue(store.meetings().isEmpty())
        assertFalse(store.deleteMeeting("m_87654321"))
    }
}
''', encoding="utf-8")

required = {
    STORE: ["fun meetings(): List<AssistantMeetingSummary>", "fun deleteMeeting(meetingId: String)"],
    SETTINGS: ["private lateinit var meetingsSlot", "AssistantMeetingsActivity::class.java"],
    MANIFEST: [".AssistantMeetingsActivity"],
    ACTIVITY: ["class AssistantMeetingsActivity", "Copy brief", "Share meeting brief"],
    ARCHIVE_TEST: ["AssistantMeetingArchiveTest", "store.meetings()"],
}
for path, markers in required.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"Missing Assistant 1.5.4 meetings marker in {path}: {marker}")
