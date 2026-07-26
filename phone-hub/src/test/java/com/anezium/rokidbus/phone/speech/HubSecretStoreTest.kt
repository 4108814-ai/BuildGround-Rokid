package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSecretStoreTest {
    @Test
    fun envelopeRejectsUnsupportedVersionBeforeDecodingFields() {
        assertNull(
            HubSecretEnvelope.parse(
                """{"version":2,"iv":"not-base64","ciphertext":"not-base64"}""",
            ),
        )
    }

    @Test
    fun envelopeRejectsMissingOrMalformedFields() {
        assertNull(HubSecretEnvelope.parse("""{"version":1}"""))
        assertNull(HubSecretEnvelope.parse("not-json"))
    }

    @Test
    fun productionNamesDoNotReuseRelayStorage() {
        assertTrue(HubSecretStore.KEY_ALIAS.startsWith("nexus_"))
        assertTrue(HubSecretStore.PREFERENCES_FILE.startsWith("nexus_"))
    }
}
