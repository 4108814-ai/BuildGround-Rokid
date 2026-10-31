package com.anezium.rokidbus.glasses

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

fun main() {
    val ui = Thread.currentThread()
    val queue = ConcurrentLinkedQueue<() -> Unit>()
    val rendered = mutableListOf<List<String>>()
    var selected = 0
    fun delivery() = LauncherListDelivery<List<String>>(
        dispatch = { queue.add(it) },
        deliver = {
            check(Thread.currentThread() === ui)
            selected = selected.coerceIn(0, (it.size - 1).coerceAtLeast(0))
            rendered.add(it)
        },
    )
    fun drain() { while (true) (queue.poll() ?: break).invoke() }

    var oldCallbackViolatedUiThread = false
    thread {
        try { check(Thread.currentThread() === ui) }
        catch (_: IllegalStateException) { oldCallbackViolatedUiThread = true }
    }.join()
    check(oldCallbackViolatedUiThread)
    println("PASS baseline transport-thread rendering violation reproduced")

    val first = delivery()
    thread { first.accept(listOf("relay", "delfos-gate")) }.join()
    check(rendered.isEmpty())
    drain()
    check(rendered.single() == listOf("relay", "delfos-gate"))
    println("PASS worker update reaches UI dispatcher with both entries")

    val keys = DpadPairDedupe()
    fun key(code: Int, at: Long) {
        when (keys.onKey(code, 0, 0, at)) {
            DpadPairDedupe.Direction.FORWARD -> selected = (selected + 1) % 2
            DpadPairDedupe.Direction.BACKWARD -> selected = (selected + 1) % 2
            null -> Unit
        }
    }
    key(22, 1000); key(20, 1080)
    check(selected == 1)
    println("PASS actual gesture detector moves Relay to Delfos once for duplicate pair")
    first.accept(listOf("relay", "delfos-gate")); drain()
    check(selected == 1)
    println("PASS list refresh retains Delfos selection")

    thread { first.accept(listOf("stale")) }.join()
    first.close()
    val second = delivery()
    second.accept(listOf("relay", "delfos-gate")); drain()
    check(rendered.last() == listOf("relay", "delfos-gate") && rendered.none { it == listOf("stale") })
    println("PASS queued old session cannot overwrite reopened launcher")
    first.accept(listOf("late")); drain()
    check(rendered.none { it == listOf("late") })
    println("PASS late callback after unsubscribe cannot update new session")

    selected = 0
    val slowPair = DpadPairDedupe()
    for ((code, time) in listOf(22 to 2000L, 20 to 2151L)) {
        if (slowPair.onKey(code, 0, 0, time) != null) selected = (selected + 1) % 2
    }
    check(selected == 0)
    println("PASS two accepted directions outside 150ms wrap two-item selection (hypothesis only)")

    val lines = mutableListOf<String>()
    val trace = LauncherNavigationTrace(lines::add)
    trace.record(false, 1, 22, 0, 0, LauncherNavigationTrace.Owner.MAIN, 0, 1, 2)
    trace.record(true, 1, 29, 0, 0, LauncherNavigationTrace.Owner.MAIN, 0, 1, 2)
    check(lines.isEmpty())
    println("PASS hidden launcher and text key produce no diagnostic records")
    trace.record(true, 1000, 22, 0, 0, LauncherNavigationTrace.Owner.MAIN, 0, 1, 2)
    check(lines.single().contains("owner=MAIN before=0 after=1 count=2"))
    trace.record(true, 301001, 22, 0, 0, LauncherNavigationTrace.Owner.SURFACE, -1, -1, -1)
    check(lines.size == 1)
    println("PASS diagnostic identifies consumer/index and stops after five minutes")
    repeat(100) { trace.record(true, 1001L + it, 22, 0, 0,
        LauncherNavigationTrace.Owner.OVERLAY, 0, 1, 2) }
    check(lines.size == 64)
    println("PASS diagnostic record budget is capped at 64")
}
