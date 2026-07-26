package com.anezium.rokidbus.phone

import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Orders short bus-routing tasks while reusing the hub's existing background executor.
 */
internal class SerialExecutor(
    private val executor: Executor,
) : Executor {
    private val tasks = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    override fun execute(command: Runnable) {
        synchronized(tasks) {
            tasks.addLast(
                Runnable {
                    try {
                        command.run()
                    } finally {
                        scheduleNext()
                    }
                },
            )
            if (active == null) scheduleNext()
        }
    }

    private fun scheduleNext() {
        synchronized(tasks) {
            active = tasks.pollFirst()
            active?.let(executor::execute)
        }
    }
}
