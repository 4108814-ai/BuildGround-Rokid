package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhoneTtsSettingsStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `rate defaults clamps and persists`() {
        clearPreferences()
        val store = PhoneTtsSettingsStore(context)

        assertEquals(1.0f, store.speechRate())
        store.setSpeechRate(0.25f)
        assertEquals(0.5f, PhoneTtsSettingsStore(context).speechRate())
        store.setSpeechRate(2.5f)
        assertEquals(2.0f, PhoneTtsSettingsStore(context).speechRate())
        store.setSpeechRate(Float.NaN)
        assertEquals(1.0f, PhoneTtsSettingsStore(context).speechRate())
    }

    @Test
    fun `voice name persists and null restores engine default`() {
        clearPreferences()
        val store = PhoneTtsSettingsStore(context)

        assertNull(store.voiceName())
        store.setVoiceName("engine.voice.id")
        assertEquals("engine.voice.id", PhoneTtsSettingsStore(context).voiceName())
        store.setVoiceName(null)
        assertNull(PhoneTtsSettingsStore(context).voiceName())
    }

    @Test
    fun `stored glasses only mode is ignored`() {
        clearPreferences()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LEGACY_OUTPUT_MODE_KEY, "glasses")
            .commit()

        val store = PhoneTtsSettingsStore(context)

        assertEquals(PhoneTtsSettingsStore.DEFAULT_SPEECH_RATE, store.speechRate())
        store.setVoiceName("engine.voice.id")
        assertEquals("engine.voice.id", PhoneTtsSettingsStore(context).voiceName())
    }

    private fun clearPreferences() {
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PhoneTtsSettingsStore.KEY_SPEECH_RATE)
            .remove(PhoneTtsSettingsStore.KEY_VOICE_NAME)
            .remove(LEGACY_OUTPUT_MODE_KEY)
            .commit()
    }

    private companion object {
        const val LEGACY_OUTPUT_MODE_KEY = "phone_tts_output_mode"
    }
}
