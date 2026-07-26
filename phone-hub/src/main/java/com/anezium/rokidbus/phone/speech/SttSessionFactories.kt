package com.anezium.rokidbus.phone.speech

import android.content.Context

internal class AndroidSttSessionFactory(
    context: Context,
) : SpeechSttSessionFactory {
    private val appContext = context.applicationContext

    override fun create(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        listener: SttSessionListener,
    ): SttSession {
        require(engine.usesAndroidRecognizer) {
            "AndroidSttSessionFactory cannot create engine ${engine.id}"
        }
        return AndroidSttSession(
            context = appContext,
            language = language,
            listener = listener,
        )
    }
}

internal class RoutingSttSessionFactory(
    private val cloud: SpeechSttSessionFactory,
    private val android: SpeechSttSessionFactory,
) : SpeechSttSessionFactory {
    override fun create(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        listener: SttSessionListener,
    ): SttSession =
        if (engine.usesAndroidRecognizer) {
            android.create(engine, language, phoneLanguageTag, listener)
        } else {
            cloud.create(engine, language, phoneLanguageTag, listener)
        }

    override fun close() {
        try {
            cloud.close()
        } finally {
            android.close()
        }
    }
}
