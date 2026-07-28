package com.anezium.rokidbus.glasses

import android.content.Context

/**
 * How much of the left of the status row is not ours to draw in — per layout.
 *
 * The ROM has one status bar and several arrangements of it. On the home and
 * carousel screens it sits at the top third with clock **and weather** on the
 * left; inside an in-launcher app (teleprompter, subtitles) the same container
 * relocates to the bottom, drops the weather, and fills its centre
 * `status_fragment` lane (x 125..355, empty at row height on the home) with the
 * app's own controls. Measured on device 2026-07-28: the clock's slot is the
 * same fixed-width box in both (`status_time_tv` ends at x=41), the weather
 * runs to x=92 when present.
 *
 * One number cannot serve both. A single reserve latched at the home's 96px put
 * the chip straight under the teleprompter's control pill, because the space
 * that is dead on the home is the app's lane everywhere else. So the reserve is
 * **keyed by the layout's signature** — which left-cluster members are visible
 * — and each signature keeps its own value: beside the weather on the home,
 * beside the clock in an app screen. The same glue rule, applied to what is
 * actually there.
 *
 * **Within a signature, the reserve only ever grows.** That single rule is what
 * makes observing a row we do not own safe when chasing it is not: a capacity
 * observation always moves the chip *away* from the ROM's text, so the
 * accessibility event carrying it can be as late as it likes and still cannot
 * produce an overlap. Letting it shrink would reintroduce the measured failure
 * that shaped this design — the cluster narrows, we move in to hug it, and its
 * next widening lands under us. Switching signatures is not shrinking: it is a
 * different layout with its own history.
 *
 * The row has one other non-ROM occupant nothing here can see:
 * R08AccessBridge's ring chip, an overlay like ours and therefore invisible to
 * any observation, anchored to the right-hand radio cluster in every layout.
 * Coexistence is by side — our chip anchors left and stays short — not by
 * anything measurable at runtime; the boundary is documented at
 * [StatusBadgeOverlayRenderer].
 */
internal class StatusBadgeReserve(private val store: Store) {

    /** Which arrangement of the row we are placing against. */
    enum class Signature(internal val key: String, internal val floorDp: Int) {
        /** Home and carousel: clock and weather on the left, centre lane empty. */
        WEATHER("weather", floorDp = 64),

        /**
         * An in-launcher app screen: clock alone, centre lane occupied. The
         * floor hugs the clock's fixed-width slot (ends x=41, same box in every
         * layout) at the usual glue distance; the widest chip ends ~65px later,
         * well clear of the app lane at x=125.
         */
        CLOCK_ONLY("clock", floorDp = 30),
    }

    /** Kept behind an interface so the growth rule can be tested as arithmetic. */
    interface Store {
        fun read(key: String): Int
        fun write(key: String, value: Int)
    }

    /** Current reserve in pixels for this layout, never below its floor. */
    fun current(signature: Signature, screenWidth: Int, density: Float): Int {
        val floor = StatusBadgeGeometry.px(signature.floorDp, density)
        return maxOf(store.read(signature.key), floor).coerceAtMost(screenWidth)
    }

    /**
     * Fold in the visible cluster's right edge, if it could be read, and return
     * the reserve to lay out with.
     *
     * A null observation changes nothing: not seeing the cluster tells us
     * nothing about how wide it can get, and guessing from an absence is how a
     * row starts oscillating.
     */
    fun observe(
        signature: Signature,
        clusterRightPx: Int?,
        screenWidth: Int,
        density: Float,
    ): Int {
        val existing = current(signature, screenWidth, density)
        val right = clusterRightPx ?: return existing
        if (right <= 0 || right >= screenWidth) return existing
        val headroom = StatusBadgeGeometry.px(CAPACITY_HEADROOM_DP, density)
        val wanted = (right + headroom).coerceAtMost(screenWidth)
        if (wanted <= existing) return existing
        store.write(signature.key, wanted)
        return wanted
    }

    companion object {
        /**
         * The gap between the cluster's observed edge and our *window* — and it
         * is a *glue* gap, not breathing room: the ROM spaces its own row
         * elements 6px apart, and the chip must sit as tightly or it reads as
         * floating rather than as one more element. Small because the glyph
         * view opens with ~4px of transparent bleed (its art spans 13 of 24
         * viewport units), which supplies the rest of the visible spacing. The
         * first cut used 16dp "to be safe"; on screen that safety read as a
         * hole in the row.
         */
        const val CAPACITY_HEADROOM_DP = 2

        private const val PREFS = "status_badge_reserve"
        private const val KEY_PREFIX = "reserve_px."

        fun forContext(context: Context): StatusBadgeReserve {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return StatusBadgeReserve(
                object : Store {
                    override fun read(key: String): Int = prefs.getInt(KEY_PREFIX + key, 0)
                    override fun write(key: String, value: Int) {
                        prefs.edit().putInt(KEY_PREFIX + key, value).apply()
                    }
                },
            )
        }
    }
}
