package com.anezium.rokidbus.plugin.tasker

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import java.security.MessageDigest

internal class TaskerPluginState {
    private var phase = Phase.LOADING
    private var tasks: List<TaskerTask> = emptyList()
    private var diagnostic = ""
    private var status: String? = null

    var selectedIndex: Int = 0
        private set

    fun reset() {
        phase = Phase.LOADING
        tasks = emptyList()
        diagnostic = ""
        status = null
        selectedIndex = 0
    }

    fun applySnapshot(snapshot: TaskerSnapshot) {
        selectedIndex = 0
        status = null
        if (snapshot.isReady()) {
            phase = Phase.READY
            tasks = snapshot.tasks
            diagnostic = ""
        } else {
            phase = Phase.UNHEALTHY
            tasks = emptyList()
            diagnostic = snapshot.message.ifBlank { "Tasker is not ready." }
        }
    }

    fun move(delta: Int): Boolean {
        if (phase != Phase.READY || tasks.isEmpty() || delta == 0) return false
        selectedIndex = (selectedIndex + delta).floorMod(tasks.size)
        status = null
        return true
    }

    fun selectedTask(): TaskerTask? =
        if (phase == Phase.READY) tasks.getOrNull(selectedIndex) else null

    fun setStatus(message: String) {
        if (phase == Phase.READY) status = message.cardText()
    }

    fun card(): NexusCard = when (phase) {
        Phase.LOADING -> messageCard(
            lines = listOf("Reading Tasker tasks..."),
            footer = "back",
        )
        Phase.UNHEALTHY -> messageCard(
            lines = listOf(diagnostic.cardText(), "Complete setup in the phone app."),
            footer = "back",
        )
        Phase.READY -> taskCard()
    }

    private fun taskCard(): NexusCard {
        val pageIndex = selectedIndex / MAX_TASK_ROWS
        val pageStart = pageIndex * MAX_TASK_ROWS
        val pageTasks = tasks.drop(pageStart).take(MAX_TASK_ROWS)
        val pageCount = ((tasks.size + MAX_TASK_ROWS - 1) / MAX_TASK_ROWS).coerceAtLeast(1)
        val subtitle = status ?: buildString {
            append(selectedIndex + 1)
            append('/')
            append(tasks.size)
            if (pageCount > 1) append(" . page ${pageIndex + 1}/$pageCount")
        }
        val rows = pageTasks.mapIndexed { index, task ->
            NexusCardLine(
                text = task.name.cardText(),
                sub = task.projectName.cardText().takeIf(String::isNotBlank),
                selected = pageStart + index == selectedIndex,
            )
        }
        val footer = "swipe . tap runs . back"
        return NexusCard(
            title = "Tasker",
            lines = emptyList(),
            footer = footer,
            contentKey = hashedContentKey(
                buildString {
                    append(subtitle)
                    append('|')
                    append(footer)
                    rows.forEach { row ->
                        append('|')
                        append(row.text)
                        append('|')
                        append(row.sub)
                        append('|')
                        append(row.selected)
                    }
                },
            ),
            richLines = rows,
            handlesBack = true,
            subtitle = subtitle,
        )
    }

    private fun messageCard(lines: List<String>, footer: String): NexusCard =
        NexusCard(
            title = "Tasker",
            lines = lines,
            footer = footer,
            contentKey = hashedContentKey((lines + footer).joinToString("|")),
            handlesBack = true,
        )

    private enum class Phase {
        LOADING,
        UNHEALTHY,
        READY,
    }

    private companion object {
        const val MAX_TASK_ROWS = 64
    }
}

private fun TaskerSnapshot.isReady(): Boolean =
    installed && enabled && externalAccess && runPermissionGranted && tasks.isNotEmpty()

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private fun String.cardText(): String =
    trim().replace(Regex("\\s+"), " ").take(240)

private fun hashedContentKey(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
    return buildString(32) {
        for (index in 0 until 16) {
            val byte = digest[index].toInt() and 0xff
            append("0123456789abcdef"[byte ushr 4])
            append("0123456789abcdef"[byte and 0x0f])
        }
    }
}
