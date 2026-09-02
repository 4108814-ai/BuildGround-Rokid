package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class AssistantMeetingStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `active meeting survives recorder recreation and archives transcript plus protocol`() {
        val started = ZonedDateTime.of(2026, 9, 2, 21, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        var now = started
        val store = store()
        var recorder = AssistantMeetingRecorder(now = { now }, persistence = store)

        assertTrue(recorder.start())
        assertTrue(recorder.append("Решили закончить бетон до пятницы."))
        assertTrue(recorder.append("Ответственный — Иван."))

        recorder = AssistantMeetingRecorder(now = { now }, persistence = store)
        assertTrue(recorder.active)
        assertEquals(2, recorder.segmentCount)

        now = now.plusMinutes(15)
        val transcript = recorder.finish()
        assertNotNull(transcript)
        val archived = transcript!!
        assertEquals("m_12345678", archived.id)
        assertFalse(recorder.active)
        assertFalse(File(temporaryFolder.root, AssistantMeetingStore.ACTIVE_FILE_NAME).exists())

        val archiveDir = File(
            File(temporaryFolder.root, AssistantMeetingStore.ARCHIVE_DIR_NAME),
            archived.id!!,
        )
        assertTrue(File(archiveDir, AssistantMeetingStore.TRANSCRIPT_JSON_NAME).isFile)
        val text = File(archiveDir, AssistantMeetingStore.TRANSCRIPT_TEXT_NAME).readText()
        assertTrue(text.contains("Решили закончить бетон до пятницы."))
        assertTrue(store.saveProtocol(archived.id!!, "# Протокол\n\nРешение принято."))
        assertTrue(
            File(archiveDir, AssistantMeetingStore.PROTOCOL_MARKDOWN_NAME)
                .readText()
                .contains("Решение принято."),
        )
    }

    @Test
    fun `back style cancel removes active meeting without creating archive`() {
        val now = ZonedDateTime.of(2026, 9, 2, 22, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        val store = store()
        val recorder = AssistantMeetingRecorder(now = { now }, persistence = store)

        assertTrue(recorder.start())
        recorder.append("Черновой фрагмент")
        recorder.cancel()

        assertFalse(recorder.active)
        assertFalse(File(temporaryFolder.root, AssistantMeetingStore.ACTIVE_FILE_NAME).exists())
        val archiveRoot = File(temporaryFolder.root, AssistantMeetingStore.ARCHIVE_DIR_NAME)
        assertFalse(archiveRoot.exists())
    }

    private fun store() = AssistantMeetingStore(
        filesDir = temporaryFolder.root,
        idGenerator = { "m_12345678" },
    )
}
