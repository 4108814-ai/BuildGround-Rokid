package com.anezium.rokidbus.phone

import android.content.Context

class PhoneTtsSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        NexusPhoneState.PREFS,
        Context.MODE_PRIVATE,
    )

    fun speechRate(): Float = normalizeRate(
        preferences.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE),
    )

    fun setSpeechRate(rate: Float) {
        preferences.edit().putFloat(KEY_SPEECH_RATE, normalizeRate(rate)).apply()
    }

    fun voiceName(): String? = preferences.getString(KEY_VOICE_NAME, null)

    fun setVoiceName(name: String?) {
        val editor = preferences.edit()
        if (name == null) editor.remove(KEY_VOICE_NAME) else editor.putString(KEY_VOICE_NAME, name)
        editor.apply()
    }

    private fun normalizeRate(rate: Float): Float =
        if (rate.isFinite()) rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) else DEFAULT_SPEECH_RATE

    internal companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
        const val KEY_SPEECH_RATE = "phone_tts_speech_rate"
        const val KEY_VOICE_NAME = "phone_tts_voice_name"
    }
}
