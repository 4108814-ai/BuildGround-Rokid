package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessAdbContractTest {
    @Test
    fun `request must carry a supported version and action`() {
        assertEquals(
            WirelessAdbAction.START_PAIRING,
            WirelessAdbContract.requestAction(
                WirelessAdbContract.request(WirelessAdbAction.START_PAIRING),
            ),
        )
        assertNull(WirelessAdbContract.requestAction(JSONObject().put("version", 1).put("action", "root")))
        assertNull(WirelessAdbContract.requestAction(JSONObject().put("version", 2).put("action", "status")))
    }

    @Test
    fun `phone hub stamps the authenticated plugin identity`() {
        val stamped = WirelessAdbContract.stampedRequest(
            WirelessAdbContract.request(WirelessAdbAction.STATUS)
                .put("pluginId", "spoofed")
                .put("unexpected", "discard me"),
            "wirelessadb",
        )

        assertEquals("wirelessadb", WirelessAdbContract.pluginId(requireNotNull(stamped)))
        assertEquals(setOf("version", "action", "pluginId"), stamped.keys().asSequence().toSet())
        assertNull(
            WirelessAdbContract.stampedRequest(
                WirelessAdbContract.request(WirelessAdbAction.STATUS),
                "Invalid.Plugin",
            ),
        )
    }

    @Test
    fun `pairing reply round trips only with a complete six digit secret`() {
        val reply = WirelessAdbReply(
            action = WirelessAdbAction.START_PAIRING,
            success = true,
            wifiConnected = true,
            enabled = true,
            pairingActive = true,
            host = "192.0.2.7",
            connectPort = 37123,
            pairingPort = 38234,
            pairingCode = "123456",
            expiresAtMillis = 123_456_789L,
        )

        assertEquals(
            reply,
            WirelessAdbContract.parseReply(WirelessAdbContract.reply("wirelessadb", reply)),
        )
        assertNull(
            WirelessAdbContract.parseReply(
                WirelessAdbContract.reply("wirelessadb", reply).put("pairingCode", "12345;sh"),
            ),
        )
        assertNull(
            WirelessAdbContract.parseReply(
                WirelessAdbContract.reply("wirelessadb", reply).put("host", "192.0.2.7;sh"),
            ),
        )
        val incomplete = WirelessAdbContract.reply("wirelessadb", reply).apply {
            remove("pairingPort")
        }
        assertNull(WirelessAdbContract.parseReply(incomplete))
        val missingExpiry = WirelessAdbContract.reply("wirelessadb", reply).apply {
            remove("expiresAtMillis")
        }
        assertNull(WirelessAdbContract.parseReply(missingExpiry))
        assertNull(
            WirelessAdbContract.parseReply(
                WirelessAdbContract.reply("wirelessadb", reply.copy(pairingActive = false)),
            ),
        )
    }
}
