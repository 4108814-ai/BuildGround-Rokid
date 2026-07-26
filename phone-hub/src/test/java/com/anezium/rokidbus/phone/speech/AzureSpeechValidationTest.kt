package com.anezium.rokidbus.phone.speech

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AzureSpeechValidationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(HubSecretStore.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun regionValidationAcceptsOnlyLowercaseDnsLabelCharacters() {
        assertTrue(isValidAzureRegion("westeurope"))
        assertTrue(isValidAzureRegion("east-us-2"))
        assertFalse(isValidAzureRegion("East US"))
        assertFalse(isValidAzureRegion("west_europe"))
        assertFalse(isValidAzureRegion("west/europe"))
        assertFalse(isValidAzureRegion(""))
    }

    @Test
    fun invalidRegionReturnsProviderErrorWithoutOpeningAConnection() {
        val secrets = HubSecretStore(context)
        assertTrue(secrets.saveAzureRegion("east us"))
        var connectionAttempts = 0
        val engine = ApiCompletedAudioSpeechToTextEngine(
            secrets = secrets,
            engine = SpeechEngine.AZURE_SPEECH,
            connectionFactory = {
                connectionAttempts += 1
                error("must not open")
            },
        )

        val error = runCatching {
            engine.transcribe(
                CompletedAudioSpeechToTextInput(
                    pcm16Mono = ByteArray(3_200),
                    sampleRate = 16_000,
                    languageTag = "en-US",
                ),
            )
        }.exceptionOrNull() as SttEngineException

        assertEquals(0, connectionAttempts)
        assertEquals(SttErrorKind.PROVIDER, error.error.kind)
        assertEquals("Azure", error.error.providerLabel)
    }
}
