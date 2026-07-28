package com.anezium.rokidbus.glasses

/**
 * Placement for the badge row Nexus draws in the Rokid launcher's status row.
 *
 * Split out of [StatusBadgeOverlayRenderer] so it can be tested as arithmetic
 * rather than through a window manager. The measurements are from RG-glasses
 * (480x640 @1.5) on 2026-07-28:
 *
 * ```
 * activity_global_status_bar [0,306][480,400]
 * status_time_tv             [1,353][41,375]
 * status_weather_iv          [47,355][67,375]
 * status_weather_tv          [69,353][92,375]
 * status_fragment            [125,306][355,400]   (its content sits above y=350)
 * status_wifi_iv             [415,355][435,375]   (absent when wifi is off)
 * status_power_iv            [439,356][450,374]
 * status_power_tv            [452,353][479,375]
 * ```
 *
 * In-launcher app screens rearrange this: the same container relocates to the
 * bottom edge (y 466..560 in the teleprompter, same view ids), the weather
 * disappears, and `status_fragment` fills with the app's own controls at row
 * height. The clock keeps its fixed-width slot in every layout. This is why
 * placement is per-layout ([StatusBadgeReserve.Signature]) and why the row's
 * vertical centre is always read fresh, never cached.
 *
 * ## Why the row sits on the left, joining the clock and the weather
 *
 * The ROM's two clusters are not symmetrical. The right one is *volatile*: it
 * is right-aligned and grows leftward as radios light, by a full icon at a time
 * — wifi off, it begins at 439; wifi on, 415. The left one is *calm*: clock and
 * weather, left-aligned, whose right edge moves only when the weather text
 * changes width. Placing our row beside the calm cluster instead of the
 * volatile one removes almost the whole class of collision the first revision
 * spent a reserve mechanism defending against — and it reads better, because
 * the chips join an existing group of indicators instead of floating alone in
 * the row's empty middle.
 *
 * ## One window, left-pinned, growing rightward
 *
 * A single `WRAP_CONTENT` window whose **left edge** is what gets placed
 * ([Origin.leftInset], START gravity). A widening label or an appearing chip
 * grows the window rightward into the row's free middle — hundreds of pixels of
 * nothing — while the edge beside the weather never moves. Chips pack from the
 * left, the phone first; the ring appearing appends to the right and displaces
 * nothing the wearer has already learned the place of.
 *
 * What remains dynamic is [StatusBadgeReserve]: the left cluster's observed
 * width, which only ever grows. The reasoning is unchanged from the right-side
 * revision — adapt to the row's *capacity*, never chase its *content* — the
 * side just makes the stakes far smaller.
 *
 * Chip metrics defer to the ROM's own row: its icons are 20px (glyph 13dp = 20px
 * matches), its labels regular-weight (~11sp at this density). Ours must read as
 * "one more ROM indicator": bold 12sp text out-weighed the row, and an 18px
 * glyph under-weighed it into illegibility — both were tried on device.
 */
internal object StatusBadgeGeometry {

    const val ROW_HEIGHT_DP = 20
    const val GLYPH_SIZE_DP = 13

    /**
     * Zero on purpose. The phone glyph's art spans 13 of its 24 viewport units,
     * so the square view carries ~4px of transparent bleed per side at this
     * size — and that bleed *is* the icon-to-number gap, landing right at the
     * ROM's own icon-to-text rhythm. Any margin on top of it reads as the
     * number drifting away from its icon (tried at 5dp; the owner flagged it).
     */
    const val GLYPH_GAP_DP = 0
    const val LABEL_SP = 11f

    /** The window's placement: inset of its left edge from the screen's, and its top. */
    data class Origin(val leftInset: Int, val y: Int)

    /**
     * [rowCentreY] is required, deliberately: the ROM relocates the whole row
     * between layouts (top third on the home, bottom edge in the teleprompter),
     * so there is no constant a caller could safely substitute. A row that
     * cannot be read is a row the chip must not be drawn into.
     */
    fun originFor(
        reservePx: Int,
        rowCentreY: Int,
        density: Float,
    ): Origin {
        val height = px(ROW_HEIGHT_DP, density)
        return Origin(leftInset = reservePx, y = rowCentreY - height / 2)
    }

    fun px(dp: Int, density: Float): Int = Math.round(dp * density)
}
