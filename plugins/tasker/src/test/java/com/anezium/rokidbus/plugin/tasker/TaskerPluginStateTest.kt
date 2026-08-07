package com.anezium.rokidbus.plugin.tasker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerPluginStateTest {
    @Test
    fun `ready snapshot exposes tasks and selects the first row`() {
        val state = TaskerPluginState()

        state.applySnapshot(
            readySnapshot(
                TaskerTask("Morning", "Home"),
                TaskerTask("Commute", "Travel"),
            ),
        )

        assertEquals(TaskerTask("Morning", "Home"), state.selectedTask())
        assertEquals(listOf("Morning", "Commute"), state.card().richLines!!.map { it.text })
        assertTrue(state.card().richLines!!.first().selected)
        assertEquals("1/2", state.card().subtitle)
    }

    @Test
    fun `every unhealthy snapshot renders its diagnostic`() {
        val task = TaskerTask("Morning")
        val snapshots = listOf(
            TaskerSnapshot(false, true, true, true, listOf(task), "Tasker is not installed."),
            TaskerSnapshot(true, false, true, true, listOf(task), "Tasker is disabled."),
            TaskerSnapshot(true, true, false, true, listOf(task), "External access is disabled."),
            TaskerSnapshot(true, true, true, false, listOf(task), "Run permission is missing."),
            TaskerSnapshot(true, true, true, true, emptyList(), "No named tasks found."),
        )

        snapshots.forEach { snapshot ->
            val state = TaskerPluginState()
            state.applySnapshot(snapshot)

            assertEquals(
                listOf(snapshot.message, "Complete setup in the phone app."),
                state.card().lines,
            )
            assertNull(state.selectedTask())
            assertFalse(state.move(1))
        }
    }

    @Test
    fun `blank unhealthy diagnostic uses the fallback copy`() {
        val state = TaskerPluginState()

        state.applySnapshot(
            TaskerSnapshot(false, false, false, false, emptyList(), "  "),
        )

        assertEquals("Tasker is not ready.", state.card().lines.first())
    }

    @Test
    fun `cursor wraps in either direction with move`() {
        val state = TaskerPluginState()
        state.applySnapshot(readySnapshot(TaskerTask("One"), TaskerTask("Two"), TaskerTask("Three")))

        assertTrue(state.move(-1))
        assertEquals(2, state.selectedIndex)
        assertEquals("Three", state.selectedTask()!!.name)

        assertTrue(state.move(1))
        assertEquals(0, state.selectedIndex)

        assertTrue(state.move(4))
        assertEquals(1, state.selectedIndex)
        assertFalse(state.move(0))
    }

    @Test
    fun `paging follows the selected row beyond the card limit`() {
        val state = TaskerPluginState()
        val tasks = (1..130).map { TaskerTask("Task $it", "Project $it") }
        state.applySnapshot(readySnapshot(*tasks.toTypedArray()))

        state.move(64)
        val secondPage = state.card()
        assertEquals("65/130 . page 2/3", secondPage.subtitle)
        assertEquals(64, secondPage.richLines!!.size)
        assertEquals("Task 65", secondPage.richLines!!.first().text)
        assertEquals("Task 128", secondPage.richLines!!.last().text)
        assertTrue(secondPage.richLines!!.first().selected)

        state.move(65)
        val thirdPage = state.card()
        assertEquals("130/130 . page 3/3", thirdPage.subtitle)
        assertEquals(listOf("Task 129", "Task 130"), thirdPage.richLines!!.map { it.text })
        assertTrue(thirdPage.richLines!!.last().selected)
    }

    @Test
    fun `status replaces the position and is cleared on move`() {
        val state = TaskerPluginState()
        state.applySnapshot(readySnapshot(TaskerTask("One"), TaskerTask("Two")))

        state.setStatus("  Sent:\n  One\t now  ")
        assertEquals("Sent: One now", state.card().subtitle)

        state.move(1)
        assertEquals("2/2", state.card().subtitle)
    }

    @Test
    fun `card text collapses whitespace and truncates long task fields`() {
        val state = TaskerPluginState()
        val longName = "  Alpha \n\t Beta ${"x".repeat(300)}  "
        val longProject = "  Project \r\n Name ${"y".repeat(300)}  "
        state.applySnapshot(readySnapshot(TaskerTask(longName, longProject)))

        val row = state.card().richLines!!.single()
        assertEquals(("Alpha Beta " + "x".repeat(300)).take(240), row.text)
        assertEquals(("Project Name " + "y".repeat(300)).take(240), row.sub)
        assertEquals(240, row.text.length)
        assertEquals(240, row.sub!!.length)
    }

    @Test
    fun `identical content has a stable bounded content key`() {
        val snapshot = readySnapshot(
            TaskerTask("Morning ${"x".repeat(300)}", "Home ${"y".repeat(300)}"),
        )
        val first = TaskerPluginState().apply { applySnapshot(snapshot) }.card().contentKey
        val secondState = TaskerPluginState().apply { applySnapshot(snapshot) }
        val second = secondState.card().contentKey

        assertEquals(first, second)
        assertEquals(second, secondState.card().contentKey)
        assertTrue(first!!.length <= 128)
    }

    private fun readySnapshot(vararg tasks: TaskerTask) = TaskerSnapshot(
        installed = true,
        enabled = true,
        externalAccess = true,
        runPermissionGranted = true,
        tasks = tasks.toList(),
        message = "Ready.",
    )
}
