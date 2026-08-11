package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesCapabilitiesTest {
    @Test
    fun `documented Hermes capability manifest is detected without relying on model name`() {
        // Verbatim shape from the Hermes API server docs: no runtime block at all.
        val result = HermesCapabilitiesClient.parseHermesCapabilities(
            """
            {
              "object": "hermes.api_server.capabilities",
              "platform": "hermes-agent",
              "model": "my-custom-profile",
              "auth": {"type": "bearer", "required": true},
              "features": {"chat_completions": true, "responses_api": true}
            }
            """.trimIndent(),
        )

        assertEquals(HermesDiscoveryResult.Detected("my-custom-profile"), result)
    }

    @Test
    fun `generic or incomplete compatibility responses are not classified as Hermes`() {
        val generic = HermesCapabilitiesClient.parseHermesCapabilities(
            """{"object":"list","platform":"hermes-agent"}""",
        )
        val clientToolRuntime = HermesCapabilitiesClient.parseHermesCapabilities(
            """
            {
              "object": "hermes.api_server.capabilities",
              "platform": "hermes-agent",
              "runtime": {"mode": "server_agent", "tool_execution": "client"}
            }
            """.trimIndent(),
        )

        assertTrue(generic === HermesDiscoveryResult.NotHermes)
        assertTrue(clientToolRuntime === HermesDiscoveryResult.NotHermes)
    }

    @Test
    fun `capability endpoint stays under the configured v1 root`() {
        assertEquals(
            "https://gateway.test/profile/v1/capabilities",
            HermesCapabilitiesClient.capabilitiesUrl(" https://gateway.test/profile/v1/ "),
        )
        assertEquals(null, HermesCapabilitiesClient.capabilitiesUrl("  "))
    }
}
