package com.buildground.baseline.phone

import java.util.concurrent.atomic.AtomicReference

/** Publishes a whole connection and retires only the session held by the failing operation. */
internal class SppClientSessionOwner<T : Any>(private val close: (T) -> Unit) {
    private val active = AtomicReference<T?>(null)

    fun current(): T? = active.get()

    fun install(session: T) {
        val previous = active.getAndSet(session)
        if (previous != null && previous !== session) close(previous)
    }

    fun retire(expected: T): Boolean {
        val removed = active.compareAndSet(expected, null)
        close(expected)
        return removed
    }

    fun clearCurrent() {
        active.getAndSet(null)?.let(close)
    }
}
