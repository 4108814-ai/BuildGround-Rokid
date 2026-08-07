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

class AssistantReminderStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pending records round trip with stable ids and timer deadline`() {
        val store = store(idGenerator = { "r_aaaaaaaa" })
        val saved = store.save(
            label = "Tea",
            epochMillis = 2_000L,
            originalIso = "2026-08-07T12:00:00+02:00",
            createdAtMs = 1_000L,
            kind = AssistantReminderKind.TIMER,
            elapsedRealtimeDeadlineMs = 8_000L,
        ) as AssistantReminderSaveResult.Saved

        val reopened = store(idGenerator = { "r_bbbbbbbb" })

        assertEquals(saved.reminder, reopened.reminder(saved.reminder.id))
        assertEquals(8_000L, reopened.pending().single().elapsedRealtimeDeadlineMs)
    }

    @Test
    fun `exact id wins before case insensitive unique label cancellation`() {
        val ids = ArrayDeque(listOf("r_aaaaaaaa", "r_bbbbbbbb", "r_cccccccc"))
        val store = store(idGenerator = ids::removeFirst)
        val exactId = save(store, label = "Other", epoch = 2_000L)
        val idNamedLabel = save(store, label = "r_aaaaaaaa", epoch = 3_000L)

        val result = store.cancel("r_aaaaaaaa") as AssistantReminderCancelResult.Cancelled

        assertEquals(exactId.id, result.reminder.id)
        assertEquals(idNamedLabel.id, store.pending().single().id)
        assertTrue(store.cancel("R_AAAAAAAA") is AssistantReminderCancelResult.Cancelled)
    }

    @Test
    fun `ambiguous labels return candidates without mutation`() {
        val ids = ArrayDeque(listOf("r_aaaaaaaa", "r_bbbbbbbb"))
        val store = store(idGenerator = ids::removeFirst)
        save(store, "Lunch", 3_000L)
        save(store, "lunch", 2_000L)

        val result = store.cancel("LUNCH") as AssistantReminderCancelResult.Ambiguous

        assertEquals(listOf(2_000L, 3_000L), result.candidates.map(AssistantReminder::epochMillis))
        assertEquals(2, store.pending().size)
    }

    @Test
    fun `cap returns full and delivery take is idempotent`() {
        val ids = ArrayDeque(listOf("r_aaaaaaaa", "r_bbbbbbbb"))
        val store = store(maxPending = 1, idGenerator = ids::removeFirst)
        val saved = save(store, "First", 2_000L)

        assertEquals(
            AssistantReminderSaveResult.Full,
            store.save("Second", 3_000L, "second", 1_000L, AssistantReminderKind.REMINDER),
        )
        assertEquals(saved, store.takeForDelivery(saved.id))
        assertNull(store.takeForDelivery(saved.id))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `atomic move fallback persists reminders`() {
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

        val saved = save(store, "Fallback", 2_000L)

        assertEquals(1, fallbackCalls)
        assertEquals(saved, store().reminder(saved.id))
        assertFalse(
            File(temporaryFolder.root, ".${AssistantReminderStore.STORE_FILE_NAME}.tmp").exists(),
        )
    }

    private fun save(
        store: AssistantReminderStore,
        label: String,
        epoch: Long,
    ): AssistantReminder = (
        store.save(
            label = label,
            epochMillis = epoch,
            originalIso = "time-$epoch",
            createdAtMs = 1_000L,
            kind = AssistantReminderKind.REMINDER,
        ) as AssistantReminderSaveResult.Saved
    ).reminder

    private fun store(
        maxPending: Int = 50,
        idGenerator: () -> String = { "r_12345678" },
        fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
    ) = AssistantReminderStore(
        filesDir = temporaryFolder.root,
        maxPending = maxPending,
        idGenerator = idGenerator,
        fileOperations = fileOperations,
    )
}
