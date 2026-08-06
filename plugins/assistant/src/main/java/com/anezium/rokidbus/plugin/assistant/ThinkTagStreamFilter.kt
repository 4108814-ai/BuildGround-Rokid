package com.anezium.rokidbus.plugin.assistant

internal class ThinkTagStreamFilter {
    private val pending = StringBuilder()
    private var insideThinkBlock = false

    fun filter(delta: String): String = buildString {
        delta.forEach { character ->
            pending.append(character)
            drainPending(this)
        }
    }

    fun finish(): String {
        val remaining = if (insideThinkBlock) "" else pending.toString()
        pending.clear()
        return remaining
    }

    private fun drainPending(output: StringBuilder) {
        while (pending.isNotEmpty()) {
            val tag = if (insideThinkBlock) CLOSING_TAG else OPENING_TAG
            val candidate = pending.toString()
            when {
                candidate == tag -> {
                    pending.clear()
                    insideThinkBlock = !insideThinkBlock
                }
                tag.startsWith(candidate) -> return
                else -> {
                    if (!insideThinkBlock) output.append(pending[0])
                    pending.deleteCharAt(0)
                }
            }
        }
    }

    private companion object {
        const val OPENING_TAG = "<think>"
        const val CLOSING_TAG = "</think>"
    }
}
