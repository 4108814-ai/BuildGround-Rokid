package com.anezium.rokidbus.phone

/**
 * One-slot arbitration shared by plugin and hub-internal audio holders. The fast snapshot plus
 * [tryAcquire] under the same lock preserves the service's existing double-check pattern.
 */
internal class SingleAudioLeaseArbitrator<T : Any> {
    private val lock = Any()

    @Volatile private var active: T? = null

    fun snapshot(): T? = active

    fun tryAcquire(candidate: T): Boolean =
        synchronized(lock) {
            if (active != null) {
                false
            } else {
                active = candidate
                true
            }
        }

    fun clearIf(predicate: (T) -> Boolean): T? =
        synchronized(lock) {
            val current = active
            if (current != null && predicate(current)) {
                active = null
                current
            } else {
                null
            }
        }

    fun clear(): T? =
        synchronized(lock) {
            active.also { active = null }
        }

    fun <R> withActive(block: (T) -> R): R? =
        synchronized(lock) {
            active?.let(block)
        }
}
