package com.anezium.rokidbus.phone

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class Socket(val name: String, val closing: () -> Unit = {}) {
    val closes = AtomicInteger()
    fun close() { closes.incrementAndGet(); closing() }
}

private fun await(latch: CountDownLatch) { check(latch.await(3, TimeUnit.SECONDS)) }

fun main() {
    // Same baseline ordering: write captures old output; its catch closes the global socket.
    val captured = CountDownLatch(1)
    val failOldWrite = CountDownLatch(1)
    val old = Socket("old")
    val replacement = Socket("replacement")
    var global: Socket? = old
    val legacyWriter = thread {
        val out = global ?: error("missing baseline session")
        try {
            check(out === old)
            captured.countDown(); await(failOldWrite)
            throw IOException("old output failed after reconnect")
        } catch (_: IOException) {
            global?.close(); global = null
        }
    }
    await(captured)
    old.close(); global = replacement
    failOldWrite.countDown(); legacyWriter.join(3000)
    check(!legacyWriter.isAlive && replacement.closes.get() == 1 && global == null)
    println("PASS baseline late write failure closes replacement connection")

    val owner = SppClientSessionOwner<Socket> { it.close() }
    val a = Socket("A"); val b = Socket("B")
    owner.install(a)
    val ready = CountDownLatch(1); val release = CountDownLatch(1)
    val writer = thread {
        val session = owner.current() ?: error("missing current")
        ready.countDown(); await(release)
        owner.retire(session)
    }
    await(ready); owner.retire(a); owner.install(b); release.countDown(); writer.join(3000)
    check(!writer.isAlive && owner.current() === b && b.closes.get() == 0)
    println("PASS delayed old writer cannot close or clear new session")

    check(!owner.retire(a) && owner.current() === b && b.closes.get() == 0)
    println("PASS old reader finally cannot clear replacement")

    val many = (1..16).map { thread { repeat(100) { owner.retire(a) } } }
    many.forEach { it.join(3000); check(!it.isAlive) }
    check(owner.current() === b && b.closes.get() == 0)
    println("PASS concurrent stale failures preserve replacement")

    owner.clearCurrent()
    check(owner.current() == null && b.closes.get() == 1)
    println("PASS explicit current close detaches and closes exactly its snapshot")

    lateinit var reentrant: SppClientSessionOwner<Socket>
    val successor = Socket("successor")
    val retiring = Socket("retiring") { reentrant.install(successor) }
    reentrant = SppClientSessionOwner { it.close() }
    reentrant.install(retiring); reentrant.clearCurrent()
    check(reentrant.current() === successor && successor.closes.get() == 0)
    println("PASS new session published during old close is not subsequently cleared")

    repeat(1000) {
        val session = Socket("cycle")
        owner.install(session)
        check(owner.current() === session)
        check(owner.retire(session) && owner.current() == null && session.closes.get() == 1)
    }
    println("PASS 1000 ownership reconnect cycles without stale current session")
}
