package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesRepairContractTest {
    @Test
    fun `config round-trips and noise leaves the stored setting standing`() {
        assertEquals(true, GlassesRepairContract.autoRepairFromConfig(GlassesRepairContract.configToJson(true)))
        assertEquals(false, GlassesRepairContract.autoRepairFromConfig(GlassesRepairContract.configToJson(false)))

        // Anything unrecognisable reads as null so the glasses keep what they already have.
        assertNull(GlassesRepairContract.autoRepairFromConfig(null))
        assertNull(GlassesRepairContract.autoRepairFromConfig(JSONObject()))
        assertNull(GlassesRepairContract.autoRepairFromConfig(JSONObject().put("autoRepair", true)))
        assertNull(
            GlassesRepairContract.autoRepairFromConfig(
                JSONObject().put("version", 1).put("autoRepair", "yes"),
            ),
        )
    }

    @Test
    fun `a config from a newer phone still parses`() {
        val futuristic = GlassesRepairContract.configToJson(false)
            .put("version", GlassesRepairContract.VERSION + 3)
            .put("futureField", 7)
        assertEquals(false, GlassesRepairContract.autoRepairFromConfig(futuristic))
    }

    @Test
    fun `replies round-trip and unknown results read as no answer`() {
        GlassesRepairContract.RESULTS.forEach { result ->
            assertEquals(
                result,
                GlassesRepairContract.resultFromReply(GlassesRepairContract.replyToJson(result)),
            )
        }
        assertNull(GlassesRepairContract.resultFromReply(null))
        assertNull(GlassesRepairContract.resultFromReply(JSONObject()))
        assertNull(
            GlassesRepairContract.resultFromReply(
                JSONObject().put("version", 1).put("result", "quantum_healed"),
            ),
        )
    }

    @Test
    fun `an owner who never touched the switch gets the repair`() {
        assertTrue(GlassesRepairContract.DEFAULT_AUTO_REPAIR)
        assertFalse(GlassesRepairContract.RESULTS.isEmpty())
    }
}
