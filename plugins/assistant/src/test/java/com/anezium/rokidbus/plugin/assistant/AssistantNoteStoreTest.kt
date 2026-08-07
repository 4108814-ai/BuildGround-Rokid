package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException

class AssistantNoteStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `save read search and delete round trip with stable ids`() {
        var now = 100L
        val ids = ArrayDeque(listOf("n_aaaaaaaa", "n_bbbbbbbb"))
        val store = store(clock = { now }, idGenerator = ids::removeFirst)
        val first = store.save("Buy oat milk", "Groceries") as AssistantNoteSaveResult.Saved
        now += 1L
        val second = store.save("Book the train to Paris") as AssistantNoteSaveResult.Saved

        val reopened = store(clock = { now }, idGenerator = { "n_cccccccc" })

        assertEquals(listOf(second.note.id, first.note.id), reopened.notes().map(AssistantNote::id))
        assertEquals(first.note, reopened.note(first.note.id))
        assertEquals(listOf(first.note.id), reopened.search("OAT").map(AssistantNote::id))
        assertEquals(listOf(second.note.id), reopened.search("paris").map(AssistantNote::id))
        assertTrue(reopened.delete(first.note.id))
        assertFalse(reopened.delete(first.note.id))
        assertNull(store(clock = { now }).note(first.note.id))
    }

    @Test
    fun `text is capped at a word boundary and title is capped`() {
        val text = "word ".repeat(500)
        val result = store().save(text, "title ".repeat(30)) as AssistantNoteSaveResult.Saved

        assertTrue(result.textTruncated)
        assertTrue(result.note.text.length <= 2_000)
        assertFalse(result.note.text.endsWith(" "))
        assertTrue(result.note.title.length <= 80)
    }

    @Test
    fun `cap returns full without replacing existing notes`() {
        var id = 0
        val store = store(
            maxNotes = 2,
            idGenerator = { "n_${id++.toString().padStart(8, '0')}" },
        )

        assertTrue(store.save("one") is AssistantNoteSaveResult.Saved)
        assertTrue(store.save("two") is AssistantNoteSaveResult.Saved)
        assertEquals(AssistantNoteSaveResult.Full, store.save("three"))
        assertEquals(2, store.notes().size)
    }

    @Test
    fun `atomic move fallback persists and removes the temporary file`() {
        var fallbackCalls = 0
        val operations = object : AssistantAtomicFileOperations {
            override fun atomicReplace(source: File, target: File) {
                throw AtomicMoveNotSupportedException(source.path, target.path, "test")
            }

            override fun replace(source: File, target: File) {
                fallbackCalls += 1
                NioAssistantAtomicFileOperations.replace(source, target)
            }
        }
        val store = store(fileOperations = operations)

        val saved = store.save("fallback") as AssistantNoteSaveResult.Saved

        assertEquals(1, fallbackCalls)
        assertEquals(saved.note, store().note(saved.note.id))
        assertFalse(File(temporaryFolder.root, ".${AssistantNoteStore.STORE_FILE_NAME}.tmp").exists())
    }

    private fun store(
        clock: () -> Long = { 1_000L },
        maxNotes: Int = 200,
        idGenerator: () -> String = { "n_12345678" },
        fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
    ) = AssistantNoteStore(
        filesDir = temporaryFolder.root,
        clock = clock,
        maxNotes = maxNotes,
        idGenerator = idGenerator,
        fileOperations = fileOperations,
    )
}
