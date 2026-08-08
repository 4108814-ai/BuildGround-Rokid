package com.anezium.rokidbus.glasses

import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator

/** WXSS transition timing and cancellation over the shared HUD motion primitives. */
internal class InkMotionAdapter {
    private data class Delayed(val view: View, val runnable: Runnable)

    private val values = mutableMapOf<String, HudMotionValue>()
    private val delayed = mutableMapOf<String, Delayed>()

    fun animate(
        nodeId: String,
        property: String,
        component: String = property,
        view: View,
        from: Float,
        target: Float,
        transition: InkTransitionSpec?,
        apply: (Float) -> Unit,
    ) {
        val key = "$nodeId:$component"
        cancelDelay(key)
        if (transition == null || transition.durationMs <= 0L) {
            values.remove(key)?.cancel()
            apply(target)
            return
        }
        val start = Runnable {
            delayed.remove(key)
            val value = values.getOrPut(key) { HudMotionValue(from, apply) }
            value.animateTo(target, transition.durationMs, transition.interpolator())
        }
        if (transition.delayMs > 0L) {
            delayed[key] = Delayed(view, start)
            view.postDelayed(start, transition.delayMs)
        } else {
            start.run()
        }
    }

    fun cancelNode(nodeId: String) {
        val prefix = "$nodeId:"
        values.keys.filter { it.startsWith(prefix) }.forEach { key -> values.remove(key)?.cancel() }
        delayed.keys.filter { it.startsWith(prefix) }.forEach(::cancelDelay)
    }

    fun cancelAll() {
        values.values.forEach(HudMotionValue::cancel)
        values.clear()
        delayed.keys.toList().forEach(::cancelDelay)
    }

    private fun cancelDelay(key: String) {
        delayed.remove(key)?.let { it.view.removeCallbacks(it.runnable) }
    }

    private fun InkTransitionSpec.interpolator(): Interpolator = when (easing) {
        "linear" -> LinearInterpolator()
        "ease-in" -> HudMotion.exit
        "ease-out", "ease", "ease-in-out" -> HudMotion.enter
        else -> HudMotion.enter
    }
}
