package com.anezium.rokidbus.phone.speech

import java.util.Locale

enum class SpeechProvider(
    val displayName: String,
) {
    OPENAI("OpenAI"),
    ELEVENLABS("ElevenLabs"),
    AZURE("Azure"),
}

enum class SpeechCredentialKind {
    OPENAI,
    ELEVENLABS,
    AZURE,
}

enum class SpeechEngine(
    val id: String,
    val provider: SpeechProvider,
    val displayName: String,
    val shortLabel: String,
    val choiceDescription: String,
    val choiceBadges: List<String>,
    val credentialKind: SpeechCredentialKind,
    val completedAudioModelId: String? = null,
    val realtimeModelId: String? = null,
) {
    OPENAI_GPT_REALTIME_WHISPER(
        id = "openai_gpt_realtime_whisper",
        provider = SpeechProvider.OPENAI,
        displayName = "OpenAI GPT Realtime Whisper",
        shortLabel = "RT Whisper",
        choiceDescription = "Best when you want words to appear while you speak.",
        choiceBadges = listOf("Realtime", "Low delay", "Cloud audio"),
        credentialKind = SpeechCredentialKind.OPENAI,
        realtimeModelId = "gpt-realtime-whisper",
    ),
    OPENAI_GPT_4O_TRANSCRIBE(
        id = "openai_gpt_4o_transcribe",
        provider = SpeechProvider.OPENAI,
        displayName = "OpenAI GPT-4o Transcribe",
        shortLabel = "GPT-4o",
        choiceDescription = "Best accuracy for longer replies after you finish speaking.",
        choiceBadges = listOf("Buffered", "Most accurate", "Cloud audio"),
        credentialKind = SpeechCredentialKind.OPENAI,
        completedAudioModelId = "gpt-4o-transcribe",
    ),
    OPENAI_GPT_4O_MINI_TRANSCRIBE(
        id = "openai_gpt_4o_mini_transcribe",
        provider = SpeechProvider.OPENAI,
        displayName = "OpenAI GPT-4o mini Transcribe",
        shortLabel = "GPT-4o mini",
        choiceDescription = "Good everyday choice when cost matters more than top accuracy.",
        choiceBadges = listOf("Buffered", "Lower cost", "Cloud audio"),
        credentialKind = SpeechCredentialKind.OPENAI,
        completedAudioModelId = "gpt-4o-mini-transcribe",
    ),
    ELEVENLABS_SCRIBE_V2_REALTIME(
        id = "elevenlabs_scribe_v2_realtime",
        provider = SpeechProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v2 Realtime",
        shortLabel = "Scribe RT",
        choiceDescription = "Best for live captions with ElevenLabs voice accounts.",
        choiceBadges = listOf("Realtime", "Low delay", "Cloud audio"),
        credentialKind = SpeechCredentialKind.ELEVENLABS,
        realtimeModelId = "scribe_v2_realtime",
    ),
    ELEVENLABS_SCRIBE_V2(
        id = "elevenlabs_scribe_v2",
        provider = SpeechProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v2",
        shortLabel = "Scribe v2",
        choiceDescription = "Balanced accuracy for replies sent after you stop speaking.",
        choiceBadges = listOf("Buffered", "Balanced", "Cloud audio"),
        credentialKind = SpeechCredentialKind.ELEVENLABS,
        completedAudioModelId = "scribe_v2",
    ),
    ELEVENLABS_SCRIBE_V1(
        id = "elevenlabs_scribe_v1",
        provider = SpeechProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v1",
        shortLabel = "Scribe v1",
        choiceDescription = "Legacy option for older ElevenLabs setups.",
        choiceBadges = listOf("Buffered", "Legacy", "Cloud audio"),
        credentialKind = SpeechCredentialKind.ELEVENLABS,
        completedAudioModelId = "scribe_v1",
    ),
    AZURE_SPEECH(
        id = "azure_speech",
        provider = SpeechProvider.AZURE,
        displayName = "Azure Speech to Text",
        shortLabel = "Azure STT",
        choiceDescription = "Transcribes after you finish speaking — not realtime, no live text.",
        choiceBadges = listOf("Buffered", "Free 5 h/mo", "Cloud audio"),
        credentialKind = SpeechCredentialKind.AZURE,
        completedAudioModelId = "azure-conversation",
    ),
    ;

    val usesCompletedAudio: Boolean
        get() = completedAudioModelId != null

    val usesRealtime: Boolean
        get() = realtimeModelId != null

    companion object {
        fun fromId(id: String?): SpeechEngine? {
            val normalized = id.orEmpty().trim().lowercase(Locale.US)
            return values().firstOrNull { it.id == normalized }
        }
    }
}
