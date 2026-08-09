package com.anezium.rokidbus.glasses

/** Releases an initial Ink show only after its matching HUD frame has drawn. */
internal class InkPresentationGate {
    private data class PendingShow(
        val surfaceId: String,
        val seq: Long,
        val generation: Long,
    )

    private var pending: PendingShow? = null
    private var nextGeneration = 0L

    fun arm(surfaceId: String, seq: Long) {
        nextGeneration += 1L
        pending = PendingShow(surfaceId, seq, nextGeneration)
    }

    fun retainForSurface(surfaceId: String, seq: Long) {
        val current = pending ?: return
        pending = if (current.surfaceId == surfaceId) {
            current.copy(seq = seq)
        } else {
            null
        }
    }

    fun isPending(surfaceId: String, seq: Long): Boolean =
        pending?.let { it.surfaceId == surfaceId && it.seq == seq } == true

    fun pendingGeneration(surfaceId: String, seq: Long): Long? =
        pending?.takeIf { it.surfaceId == surfaceId && it.seq == seq }?.generation

    fun releaseAfterDraw(
        surfaceId: String,
        seq: Long,
        widthPx: Int,
        heightPx: Int,
        displayTransitioning: Boolean = false,
    ): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false
        if (!isPending(surfaceId, seq)) return false
        if (displayTransitioning) return false
        pending = null
        return true
    }

    fun forceRelease(surfaceId: String, seq: Long): Boolean {
        if (!isPending(surfaceId, seq)) return false
        pending = null
        return true
    }

    fun cancel() {
        pending = null
    }
}
