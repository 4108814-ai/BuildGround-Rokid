package com.anezium.rokidbus.glasses

/** Releases an initial Ink show only after its matching HUD frame has drawn. */
internal class InkPresentationGate {
    private data class PendingShow(
        val surfaceId: String,
        val seq: Long,
    )

    private var pending: PendingShow? = null

    fun arm(surfaceId: String, seq: Long) {
        pending = PendingShow(surfaceId, seq)
    }

    fun retainForSurface(surfaceId: String, seq: Long) {
        val current = pending ?: return
        pending = if (current.surfaceId == surfaceId) {
            PendingShow(surfaceId, seq)
        } else {
            null
        }
    }

    fun releaseAfterDraw(
        surfaceId: String,
        seq: Long,
        widthPx: Int,
        heightPx: Int,
        displayTransitioning: Boolean = false,
    ): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false
        if (pending != PendingShow(surfaceId, seq)) return false
        if (displayTransitioning) return false
        pending = null
        return true
    }

    fun cancel() {
        pending = null
    }
}
