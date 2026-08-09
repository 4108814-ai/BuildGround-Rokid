package com.anezium.rokidbus.glasses

import android.view.Choreographer

/** One Choreographer callback gates every recurring Ink redraw to at most 30 fps. */
internal class InkFrameGate : Choreographer.FrameCallback {
    private val clients = linkedSetOf<InkFrameClient>()
    private var posted = false
    private var lastDispatchNanos = 0L

    fun add(client: InkFrameClient) {
        clients += client
        postIfNeeded()
    }

    fun remove(client: InkFrameClient) {
        clients -= client
        if (clients.isEmpty()) stop()
    }

    fun clear() {
        clients.clear()
        stop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (clients.isEmpty()) return
        if (lastDispatchNanos == 0L || frameTimeNanos - lastDispatchNanos >= FRAME_INTERVAL_NANOS) {
            lastDispatchNanos = frameTimeNanos
            clients.toList().forEach { client ->
                if (!client.onInkFrame(frameTimeNanos)) clients -= client
            }
        }
        postIfNeeded()
    }

    private fun postIfNeeded() {
        if (posted || clients.isEmpty()) return
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stop() {
        if (posted) Choreographer.getInstance().removeFrameCallback(this)
        posted = false
        lastDispatchNanos = 0L
    }

    private companion object {
        const val FRAME_INTERVAL_NANOS = 1_000_000_000L / 30L
    }
}

internal fun interface InkFrameClient {
    /** Return false when no more recurring frames are needed. */
    fun onInkFrame(frameTimeNanos: Long): Boolean
}

internal interface InkAnimatedLeaf {
    fun onInkVisibilityChanged(visible: Boolean)
    fun cancelInkAnimation()
}
