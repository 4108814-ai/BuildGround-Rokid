package com.anezium.rokidbus.phone.speech

import android.content.Context

enum class SpeechReadiness {
    READY,
    NO_ENGINE,
    MISSING_KEY,
    MISSING_REGION,
}

class SpeechSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    var selectedEngineId: String?
        get() = prefs.getString(PREF_ENGINE, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(PREF_ENGINE, value?.trim()?.takeIf { it.isNotBlank() }).apply()
        }

    var selectedLanguageId: String
        get() = TranscriptionLanguage.fromId(prefs.getString(PREF_LANGUAGE, null)).id
        set(value) {
            prefs.edit().putString(PREF_LANGUAGE, TranscriptionLanguage.fromId(value).id).apply()
        }

    fun selectedEngine(): SpeechEngine? =
        SpeechEngine.fromId(selectedEngineId)

    fun selectedLanguage(): TranscriptionLanguage =
        TranscriptionLanguage.fromId(selectedLanguageId)

    /**
     * Every cloud engine supports the full configured language set through provider-specific
     * codes or prompts. Unlike Relay's Android engine, no cloud selection coerces a forced
     * language back to Auto.
     */
    fun selectedLanguageForEngine(engine: SpeechEngine): TranscriptionLanguage {
        @Suppress("UNUSED_VARIABLE")
        val selectedCloudEngine = engine
        return selectedLanguage()
    }

    fun readiness(secrets: HubSecretStore): SpeechReadiness {
        val engine = selectedEngine() ?: return SpeechReadiness.NO_ENGINE
        if (!secrets.hasCredential(engine)) return SpeechReadiness.MISSING_KEY
        if (engine.provider == SpeechProvider.AZURE && secrets.azureRegion().isNullOrBlank()) {
            return SpeechReadiness.MISSING_REGION
        }
        return SpeechReadiness.READY
    }

    companion object {
        internal const val PREFERENCES_FILE = "nexus_speech_settings"
        private const val PREF_ENGINE = "selectedEngineId"
        private const val PREF_LANGUAGE = "selectedLanguageId"
    }
}
