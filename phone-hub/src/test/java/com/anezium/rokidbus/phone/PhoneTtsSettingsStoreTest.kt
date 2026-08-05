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
    fun `output mode defaults and persists glasses only`() {
        clearPreferences()
        val store = PhoneTtsSettingsStore(context)

        assertEquals(PhoneTtsOutputMode.AUTO, store.outputMode())
        store.setOutputMode(PhoneTtsOutputMode.GLASSES_ONLY)
        assertEquals(
            PhoneTtsOutputMode.GLASSES_ONLY,
            PhoneTtsSettingsStore(context).outputMode(),
        )
        assertEquals(
            "glasses",
            context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
                .getString(PhoneTtsSettingsStore.KEY_OUTPUT_MODE, null),
        )
    }

    @Test
    fun `unknown output mode defaults to automatic`() {
        clearPreferences()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PhoneTtsSettingsStore.KEY_OUTPUT_MODE, "future_mode")
            .commit()

        assertEquals(PhoneTtsOutputMode.AUTO, PhoneTtsSettingsStore(context).outputMode())
    }

    private fun clearPreferences() {
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PhoneTtsSettingsStore.KEY_SPEECH_RATE)
            .remove(PhoneTtsSettingsStore.KEY_VOICE_NAME)
            .remove(PhoneTtsSettingsStore.KEY_OUTPUT_MODE)
            .commit()
    }
}
