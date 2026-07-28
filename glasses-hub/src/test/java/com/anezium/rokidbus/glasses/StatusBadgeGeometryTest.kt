package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.StatusBadgeReserve.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row placement, against the launcher's status row as measured on device
 * (RG-glasses, 480x640 @1.5, 2026-07-28) — in both of its layouts.
 *
 * These are not invented fixtures. On the home, `status_weather_tv` really ends
 * at x=92 and `status_wifi_iv` really appears at `[415,355][435,375]` only once
 * wifi is on. In the teleprompter, the same container really relocates to
 * `[0,466][480,560]` with `status_time_tv` at `[1,513][41,535]` — the same
 * fixed-width clock box — and the centre `status_fragment` lane occupied by the
 * app's controls from x=125.
 */
class StatusBadgeGeometryTest {

    private val screenWidth = 480
    private val density = 1.5f

    /** Centre of `status_power_iv`: home layout and teleprompter layout. */
    private val homeRowCentre = 365
    private val prompterRowCentre = 525

    /** Right edge of the ROM's clock-and-weather cluster on the home. */
    private val leftClusterRight = 92

    /** Left edge of the ROM's radio cluster with wifi lit. */
    private val rightClusterLeftWifiOn = 415

    private fun floorFor(signature: Signature) =
        StatusBadgeReserve(
            object : StatusBadgeReserve.Store {
                override fun read(key: String): Int = 0
                override fun write(key: String, value: Int) = Unit
            },
        ).current(signature, screenWidth, density)

    private fun origin(reservePx: Int, rowCentre: Int) =
        StatusBadgeGeometry.originFor(
            reservePx = reservePx,
            rowCentreY = rowCentre,
            density = density,
        )

    @Test
    fun `on the home the chip is glued to the weather at the ROM's own spacing`() {
        val origin = origin(floorFor(Signature.WEATHER), homeRowCentre)

        // The ROM spaces its row elements 6px apart (clock [ends 41] to weather
        // icon [starts 47], measured). Our window edge sits 4px after the
        // cluster and the glyph's visible body ~4px later (its art spans 13 of
        // 24 viewport units), so the *visible* gap lands at ~8px. 25px of
        // "safety" was tried and read as a hole in the row. Glued or it is not
        // part of the cluster.
        assertEquals(96, origin.leftInset)
        assertEquals(4, origin.leftInset - leftClusterRight)
        assertTrue(origin.leftInset + 70 < rightClusterLeftWifiOn)
    }

    @Test
    fun `in an app screen the chip hugs the clock and clears the control lane`() {
        val origin = origin(floorFor(Signature.CLOCK_ONLY), prompterRowCentre)

        // Clock ends at 41 in every layout; the app's controls own the centre
        // lane from x=125. The chip fits between them or it does not show.
        assertEquals(45, origin.leftInset)
        assertTrue(origin.leftInset + 70 < 125)
    }

    @Test
    fun `the chip centres on whichever row the ROM is currently drawing`() {
        val height = StatusBadgeGeometry.px(StatusBadgeGeometry.ROW_HEIGHT_DP, density)

        val home = origin(96, homeRowCentre)
        val prompter = origin(45, prompterRowCentre)

        // The row relocates wholesale between layouts (top third at home,
        // bottom edge in the teleprompter). The Y is the fresh power-icon read,
        // which is why originFor refuses a null centre instead of falling back
        // to either layout's constant.
        assertEquals(homeRowCentre, home.y + height / 2)
        assertEquals(prompterRowCentre, prompter.y + height / 2)
    }

    @Test
    fun `a wider reserve moves the chip right, and nothing else`() {
        val tight = origin(96, homeRowCentre)
        val wide = origin(96 + 12, homeRowCentre)

        assertEquals(tight.leftInset + 12, wide.leftInset)
        assertEquals(tight.y, wide.y)
    }

    @Test
    fun `chip metrics defer to the ROM's own row`() {
        // Its icons are 20px and its labels regular-weight; ours must sit just
        // under, never over. 13dp glyphs with 12sp bold text were tried on
        // device and visibly out-weighed the row.
        assertTrue(StatusBadgeGeometry.px(StatusBadgeGeometry.GLYPH_SIZE_DP, density) <= 20)
        assertTrue(StatusBadgeGeometry.LABEL_SP <= 11f)
    }
}
