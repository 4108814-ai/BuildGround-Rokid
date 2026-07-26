package com.anezium.rokidbus.phone.speech

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SpeechSettingsStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(SpeechSettingsStore.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun engineIsInitiallyUnsetAndLanguageDefaultsToAuto() {
        val store = SpeechSettingsStore(context)
        assertNull(store.selectedEngineId)
        assertNull(store.selectedEngine())
        assertEquals("auto", store.selectedLanguageId)
        assertSame(TranscriptionLanguage.AUTO, store.selectedLanguage())
    }

    @Test
    fun allCloudEnginesPreserveForcedLanguage() {
        val store = SpeechSettingsStore(context)
        store.selectedLanguageId = TranscriptionLanguage.CANTONESE.id

        SpeechEngine.values().forEach { engine ->
            assertSame(
                TranscriptionLanguage.CANTONESE,
                store.selectedLanguageForEngine(engine),
            )
        }
    }
}
