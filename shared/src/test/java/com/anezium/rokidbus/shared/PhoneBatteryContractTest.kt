package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBatteryContractTest {

    private fun valid(payload: JSONObject) =
        PhoneBatteryContract.validate(payload) as PhoneBatteryContract.ValidationResult.Valid

    private fun invalid(payload: JSONObject?) =
        PhoneBatteryContract.validate(payload) as PhoneBatteryContract.ValidationResult.Invalid

    @Test
    fun `round-trips a reading`() {
        val reading = PhoneBatteryContract.Reading(level = 43, charging = true)

        val result = valid(PhoneBatteryContract.toJson(reading, seq = 7))

        assertEquals(reading, result.reading)
        assertEquals(7L, result.seq)
    }

    @Test
    fun `treats a missing charging flag as not charging`() {
        val result = valid(JSONObject().put("level", 62).put("seq", 1))

        assertFalse(result.reading!!.charging)
    }

    @Test
    fun `rejects a level outside the range rather than clamping it`() {
        // Clamping would turn a phone-side bug into a badge that reads 100
        // forever; rejecting leaves the last good value on screen instead.
        assertEquals(PhoneBatteryContract.ERROR_INVALID, invalid(JSONObject().put("level", 101)).reason)
        assertEquals(PhoneBatteryContract.ERROR_INVALID, invalid(JSONObject().put("level", -1)).reason)
    }

    @Test
    fun `rejects a payload with no level at all`() {
        assertEquals(PhoneBatteryContract.ERROR_INVALID, invalid(JSONObject()).reason)
        assertEquals(PhoneBatteryContract.ERROR_INVALID, invalid(null).reason)
    }

    @Test
    fun `accepts both ends of the range`() {
        assertEquals(0, valid(JSONObject().put("level", 0)).reading!!.level)
        assertEquals(100, valid(JSONObject().put("level", 100)).reading!!.level)
    }

    @Test
    fun `round-trips the hidden state`() {
        val result = valid(PhoneBatteryContract.toHiddenJson(seq = 9))

        assertEquals(null, result.reading)
        assertEquals(9L, result.seq)
    }

    @Test
    fun `hidden wins over an accompanying level`() {
        // A payload cannot half-hide: if the phone says hidden, no level rides
        // along into the chip.
        val result = valid(JSONObject().put("hidden", true).put("level", 55).put("seq", 2))

        assertEquals(null, result.reading)
    }

    @Test
    fun `reports a missing sequence as older than any real one`() {
        // The controller drops anything at or below the last sequence it saw, so
        // an unsequenced payload must not be able to win against a real update.
        val result = valid(JSONObject().put("level", 50))

        assertTrue(result.seq < 0L)
    }

    @Test
    fun `labels charge with a suffix instead of a second colour`() {
        val discharging = PhoneBatteryContract.Reading(level = 8, charging = false)
        val charging = PhoneBatteryContract.Reading(level = 100, charging = true)

        assertEquals("8", PhoneBatteryContract.label(discharging))
        assertEquals("100+", PhoneBatteryContract.label(charging))
    }
}
