package com.anezium.rokidbus.glasses

import android.view.Choreographer

/**
 * Frame-interval instrumentation for HUD motion.
 *
 * Reports the same four numbers the WebView renderer spike reported (avg fps,
 * p50, p95, jank count) so the two renderers can be compared directly instead
 * of by impression. The open question this exists to answer: whether a
 * `TYPE_ACCESSIBILITY_OVERLAY` window gets the same frame budget the WebView
 * spike measured in a `TYPE_APPLICATION_OVERLAY` one.
 *
 * Note what it cannot see. This measures frames delivered to the app, not
 * photons through the waveguide — whether a 280 ms morph reads or smears on
 * additive optics is a question only a camera pointed through the lens
 * answers.
 */
object HudFrameMeter {

    private val callback = Choreographer.FrameCallback { frameTimeNanos ->
        onFrame(frameTimeNanos)
    }

    private var label: String = ""
    private var running = false
    private var lastFrameNanos = 0L
    private var intervals = ArrayList<Double>(600)

    fun start(label: String) {
        if (running) stop()
        this.label = label
        running = true
        lastFrameNanos = 0L
        intervals = ArrayList(600)
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
        report()
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            intervals.add((frameTimeNanos - lastFrameNanos) / 1_000_000.0)
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun report() {
        val samples = intervals
        if (samples.size < 2) {
            log("Motion frames [$label]: too few frames to report (${samples.size})")
            return
        }
        val sorted = samples.sorted()
        val avg = samples.average()
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val jank = samples.count { it > JANK_MS }
        log(
            "Motion frames [$label]: ${"%.1f".format(1_000.0 / avg)} fps avg, " +
                "p50 ${"%.2f".format(p50)}ms, p95 ${"%.2f".format(p95)}ms, " +
                "jank $jank/${samples.size} (>${JANK_MS.toInt()}ms)",
        )
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Two vsync at 60 Hz: a frame the eye can catch. */
    private const val JANK_MS = 32.0
}
