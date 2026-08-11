package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test
    fun presetIdsAreUnique() {
        val ids = ProviderCatalog.presets.map(ProviderPreset::id)

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf("openai", "openrouter", "minimax", "deepseek", "zai", "hermes", "custom"),
            ids.toSet(),
        )
    }

    @Test
    fun visionUsesSuggestionUntilExplicitlyOverridden() {
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.openAi,
                model = "gpt-4o-mini",
                visionOverride = null,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.openAi,
                model = "my-free-text-model",
                visionOverride = null,
            ),
        )
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.custom,
                model = "my-free-text-model",
                visionOverride = true,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.minimax,
                model = "MiniMax-M3",
                visionOverride = false,
            ),
        )
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.hermes,
                model = "hermes-agent",
                visionOverride = null,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.custom,
                model = "hermes-agent",
                visionOverride = null,
            ),
        )
        assertTrue(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.custom,
                model = "profile-name-can-vary",
                visionOverride = null,
                backend = ProviderBackend.HERMES,
            ),
        )
        assertFalse(
            ProviderCatalog.supportsVision(
                preset = ProviderCatalog.hermes,
                model = "hermes-agent",
                visionOverride = false,
            ),
        )
    }
}
