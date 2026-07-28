package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.StatusBadgeReserve.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The growth rule, which is the whole safety argument for observing a row we do
 * not own — now per layout. Measurements are from RG-glasses (480x640 @1.5) on
 * 2026-07-28: home row at the top third with clock+weather, teleprompter row at
 * the bottom edge with the clock alone.
 */
class StatusBadgeReserveTest {

    private val screenWidth = 480
    private val density = 1.5f

    /** Right edge of `status_weather_tv` on the home layout. */
    private val weatherRight = 92

    /** Right edge of `status_time_tv` — the same fixed-width box in every layout. */
    private val timeRight = 41

    private class FakeStore : StatusBadgeReserve.Store {
        val values = mutableMapOf<String, Int>()
        var writes = 0
        override fun read(key: String): Int = values[key] ?: 0
        override fun write(key: String, value: Int) {
            values[key] = value
            writes += 1
        }
    }

    private fun reserve(store: FakeStore = FakeStore()) = StatusBadgeReserve(store) to store

    @Test
    fun `each layout has its own floor, and both absorb their measured cluster`() {
        val (reserve, store) = reserve()

        // Home: weather ends 92, wants 92+3=95, floor 96 absorbs it.
        // App screen: clock ends 41, wants 41+3=44, floor 45 absorbs it.
        assertEquals(96, reserve.observe(Signature.WEATHER, weatherRight, screenWidth, density))
        assertEquals(45, reserve.observe(Signature.CLOCK_ONLY, timeRight, screenWidth, density))
        assertEquals(0, store.writes)
    }

    @Test
    fun `the app-screen slot clears the ROM's centre control lane`() {
        val (reserve, _) = reserve()

        // In the teleprompter the ROM's status_fragment starts at x=125 and is
        // occupied (the control pill). The chip at its widest ends ~65px past
        // its left edge; a single global reserve latched at the home's 96px is
        // exactly what put the chip under that pill.
        val leftEdge = reserve.current(Signature.CLOCK_ONLY, screenWidth, density)
        val widestChipPx = 70

        assertTrue(leftEdge + widestChipPx < 125)
    }

    @Test
    fun `switching layouts does not bleed one cluster's width into the other`() {
        val (reserve, store) = reserve()

        // Home observed, then an app screen: the app slot must hug the clock,
        // not inherit the weather's 96px — that inheritance was the bug.
        reserve.observe(Signature.WEATHER, weatherRight, screenWidth, density)
        val appSlot = reserve.observe(Signature.CLOCK_ONLY, timeRight, screenWidth, density)

        assertEquals(45, appSlot)
        assertEquals(0, store.writes)
    }

    @Test
    fun `grows once when a layout's cluster gains real capacity, and stays grown`() {
        val (reserve, store) = reserve()

        // A genuinely wider home cluster — a long temperature, another locale.
        val grown = reserve.observe(Signature.WEATHER, 105, screenWidth, density)

        assertTrue(grown > 96)
        assertEquals(
            105 + StatusBadgeGeometry.px(StatusBadgeReserve.CAPACITY_HEADROOM_DP, density),
            grown,
        )
        assertEquals(1, store.writes)

        // It keeps the ground when the cluster narrows again...
        assertEquals(grown, reserve.observe(Signature.WEATHER, weatherRight, screenWidth, density))
        // ...and the other layout is untouched.
        assertEquals(45, reserve.current(Signature.CLOCK_ONLY, screenWidth, density))
        assertEquals(1, store.writes)
    }

    @Test
    fun `a failed read is not an observation`() {
        val (reserve, store) = reserve()
        val before = reserve.current(Signature.WEATHER, screenWidth, density)

        assertEquals(before, reserve.observe(Signature.WEATHER, null, screenWidth, density))
        assertEquals(before, reserve.observe(Signature.WEATHER, 0, screenWidth, density))
        assertEquals(before, reserve.observe(Signature.WEATHER, screenWidth, screenWidth, density))
        assertEquals(0, store.writes)
    }

    @Test
    fun `a persisted reserve survives a restart, per layout`() {
        val store = FakeStore().apply { values["weather"] = 140 }
        val (reserve, _) = reserve(store)

        assertEquals(140, reserve.current(Signature.WEATHER, screenWidth, density))
        assertEquals(45, reserve.current(Signature.CLOCK_ONLY, screenWidth, density))
    }

    @Test
    fun `never reserves more than the screen`() {
        val (reserve, _) = reserve()

        assertTrue(
            reserve.observe(Signature.WEATHER, screenWidth - 1, screenWidth, density) <= screenWidth,
        )
    }
}
