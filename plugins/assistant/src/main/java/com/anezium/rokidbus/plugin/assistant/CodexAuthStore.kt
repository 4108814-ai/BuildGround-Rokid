package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CodexAuthStore {
    private val prefs: SharedPreferences

    constructor(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal constructor(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    fun hasUsableAuth(): Boolean = hasUsableAuth(
        authMode = authMode(),
        hasOAuthTokens = oauthTokens() != null,
        hasApiKey = hasApiKey(),
    )

    fun apiKey(): String? {
        val encrypted = prefs.getString(KEY_API_KEY, null) ?: return null
        return runCatching { CodexKeystoreAesGcm.decrypt(encrypted) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    fun oauthTokens(): CodexChatGptOAuthTokenBundle? {
        val encrypted = prefs.getString(KEY_OAUTH_TOKENS, null) ?: return null
        return runCatching {
            val value = JSONObject(CodexKeystoreAesGcm.decrypt(encrypted))
            CodexChatGptOAuthTokenBundle(
                accessToken = value.getString("accessToken"),
                idToken = value.getString("idToken"),
                refreshToken = value.optString("refreshToken").takeIf(String::isNotBlank),
                accountId = value.getString("accountId"),
                planType = value.optString("planType").takeIf(String::isNotBlank),
                email = value.optString("email").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun accountLabel(): String? =
        prefs.getString(KEY_ACCOUNT_LABEL, null)?.takeIf(String::isNotBlank)

    fun apiKeyExchangeError(): String? {
        val stored = prefs.getString(KEY_API_KEY_EXCHANGE_ERROR, null)
            ?.takeIf(String::isNotBlank)
        return stored?.takeUnless {
            classifyApiKeyExchangeError(it) == ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT
        }
    }

    fun isConsumerChatGptAccount(): Boolean =
        prefs.getBoolean(KEY_CONSUMER_ACCOUNT_NO_API_ORG, false) ||
            classifyApiKeyExchangeError(
                prefs.getString(KEY_API_KEY_EXCHANGE_ERROR, null),
            ) == ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT

    fun model(): String = OpenAiApiClient.supportedModel(
        prefs.getString(KEY_MODEL, OpenAiApiClient.DEFAULT_MODEL_ID).orEmpty(),
    )

    fun chatGptModel(): String = ChatGptCodexApiClient.supportedModel(
        prefs.getString(
            KEY_CHATGPT_MODEL,
            ChatGptCodexApiClient.DEFAULT_MODEL_ID,
        ).orEmpty(),
    )

    fun chatGptReasoningEffort(): String = ChatGptCodexApiClient.supportedReasoningEffort(
        prefs.getString(
            KEY_CHATGPT_REASONING_EFFORT,
            ChatGptCodexApiClient.DEFAULT_REASONING_EFFORT,
        ).orEmpty(),
    )

    fun keepConversation(): Boolean = prefs.getBoolean(KEY_KEEP_CONVERSATION, true)

    fun keepPhotosInConversations(): Boolean =
        prefs.getBoolean(KEY_KEEP_PHOTOS_IN_CONVERSATIONS, true)

    fun speakAnswers(): Boolean = prefs.getBoolean(KEY_SPEAK_ANSWERS, true)

    fun conversationIdleWindowMinutes(): Int = supportedIdleWindowMinutes(
        prefs.getInt(KEY_CONVERSATION_IDLE_WINDOW_MINUTES, DEFAULT_IDLE_WINDOW_MINUTES),
    )

    fun assistantMemory(): String = prefs.getString(KEY_ASSISTANT_MEMORY, "").orEmpty()

    fun syncAccountContext(): Boolean = prefs.getBoolean(KEY_SYNC_ACCOUNT_CONTEXT, true)

    fun syncedAccountContext(): String =
        prefs.getString(KEY_SYNCED_ACCOUNT_CONTEXT, "").orEmpty()

    fun accountContextSyncedAtMs(): Long =
        prefs.getLong(KEY_ACCOUNT_CONTEXT_SYNCED_AT, 0L)

    /** [AUTH_MODE_CHATGPT], [AUTH_MODE_API_KEY], or null when nothing is connected. */
    fun authMode(): String? = prefs.getString(KEY_AUTH_MODE, null)?.takeIf(String::isNotBlank)

    fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, OpenAiApiClient.supportedModel(model)).apply()
    }

    fun setChatGptModel(model: String) {
        prefs.edit()
            .putString(KEY_CHATGPT_MODEL, ChatGptCodexApiClient.supportedModel(model))
            .apply()
    }

    fun setChatGptReasoningEffort(effort: String) {
        require(effort in ChatGptCodexApiClient.SUPPORTED_REASONING_EFFORTS) {
            "Unsupported ChatGPT reasoning effort: $effort"
        }
        prefs.edit().putString(KEY_CHATGPT_REASONING_EFFORT, effort).apply()
    }

    fun setKeepConversation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_CONVERSATION, enabled).apply()
    }

    fun setKeepPhotosInConversations(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_PHOTOS_IN_CONVERSATIONS, enabled).apply()
    }

    fun setSpeakAnswers(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_ANSWERS, enabled).apply()
    }

    fun setConversationIdleWindowMinutes(minutes: Int) {
        prefs.edit()
            .putInt(KEY_CONVERSATION_IDLE_WINDOW_MINUTES, supportedIdleWindowMinutes(minutes))
            .apply()
    }

    fun setAssistantMemory(memory: String) {
        prefs.edit()
            .putString(KEY_ASSISTANT_MEMORY, memory.trim().take(MAX_ASSISTANT_MEMORY_CHARS))
            .apply()
    }

    fun setSyncAccountContext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ACCOUNT_CONTEXT, enabled).apply()
    }

    fun setSyncedAccountContext(text: String) {
        prefs.edit()
            .putString(
                KEY_SYNCED_ACCOUNT_CONTEXT,
                text.trim().take(MAX_SYNCED_ACCOUNT_CONTEXT_CHARS),
            )
            .apply()
    }

    fun setAccountContextSyncedAtMs(ms: Long) {
        prefs.edit().putLong(KEY_ACCOUNT_CONTEXT_SYNCED_AT, ms.coerceAtLeast(0L)).apply()
    }

    fun clearSyncedAccountContext() {
        prefs.edit()
            .remove(KEY_SYNCED_ACCOUNT_CONTEXT)
            .remove(KEY_ACCOUNT_CONTEXT_SYNCED_AT)
            .apply()
    }

    fun saveApiKey(
        apiKey: String,
        model: String = OpenAiApiClient.DEFAULT_MODEL_ID,
    ) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotBlank()) { "API key is blank." }
        prefs.edit()
            .putString(KEY_API_KEY, CodexKeystoreAesGcm.encrypt(trimmed))
            .putString(KEY_MODEL, OpenAiApiClient.supportedModel(model))
            .putString(KEY_ACCOUNT_LABEL, "API key ...${trimmed.takeLast(4)}")
            .putString(KEY_AUTH_MODE, AUTH_MODE_API_KEY)
            .remove(KEY_API_KEY_EXCHANGE_ERROR)
            .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
            .apply()
    }

    fun saveOAuth(
        tokens: CodexChatGptOAuthTokenBundle,
        apiKey: String?,
        model: String = ChatGptCodexApiClient.DEFAULT_MODEL_ID,
        apiKeyExchangeError: String? = null,
    ) {
        val tokenJson = JSONObject()
            .put("accessToken", tokens.accessToken)
            .put("idToken", tokens.idToken)
            .put("accountId", tokens.accountId)
            .put("planType", tokens.planType)
            .put("email", tokens.email)
            .apply {
                tokens.refreshToken?.let { put("refreshToken", it) }
            }
            .toString()
        val editor = prefs.edit()
            .putString(KEY_OAUTH_TOKENS, CodexKeystoreAesGcm.encrypt(tokenJson))
            .putString(KEY_CHATGPT_MODEL, ChatGptCodexApiClient.supportedModel(model))
            .putString(KEY_ACCOUNT_LABEL, tokens.displayLabel())
            .putString(KEY_AUTH_MODE, AUTH_MODE_CHATGPT)

        val trimmedApiKey = apiKey?.trim().orEmpty()
        if (trimmedApiKey.isNotBlank()) {
            editor
                .putString(KEY_API_KEY, CodexKeystoreAesGcm.encrypt(trimmedApiKey))
                .remove(KEY_API_KEY_EXCHANGE_ERROR)
                .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
        } else {
            when (classifyApiKeyExchangeError(apiKeyExchangeError)) {
                ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT -> editor
                    .putBoolean(KEY_CONSUMER_ACCOUNT_NO_API_ORG, true)
                    .remove(KEY_API_KEY_EXCHANGE_ERROR)
                ApiKeyExchangeErrorClassification.UNEXPECTED -> editor
                    .putString(
                        KEY_API_KEY_EXCHANGE_ERROR,
                        apiKeyExchangeError.orEmpty().redactProviderSecrets().take(300),
                    )
                    .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
                ApiKeyExchangeErrorClassification.NONE -> editor
                    .remove(KEY_API_KEY_EXCHANGE_ERROR)
                    .remove(KEY_CONSUMER_ACCOUNT_NO_API_ORG)
            }
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val AUTH_MODE_CHATGPT = "chatgpt_oauth"
        const val AUTH_MODE_API_KEY = "api_key"
        val SUPPORTED_IDLE_WINDOW_MINUTES = listOf(2, 5, 10, 30)
        const val DEFAULT_IDLE_WINDOW_MINUTES = 10
        const val MAX_ASSISTANT_MEMORY_CHARS = 4000
        const val MAX_SYNCED_ACCOUNT_CONTEXT_CHARS = 6000

        private const val PREFS_NAME = "assistant_auth_v1"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_OAUTH_TOKENS = "oauth_tokens"
        private const val KEY_MODEL = "model"
        private const val KEY_CHATGPT_MODEL = "chatgpt_model"
        private const val KEY_CHATGPT_REASONING_EFFORT = "chatgpt_reasoning_effort"
        private const val KEY_KEEP_CONVERSATION = "keep_conversation"
        private const val KEY_KEEP_PHOTOS_IN_CONVERSATIONS =
            "keep_photos_in_conversations"
        private const val KEY_SPEAK_ANSWERS = "speak_answers"
        private const val KEY_CONVERSATION_IDLE_WINDOW_MINUTES =
            "conversation_idle_window_minutes"
        private const val KEY_ASSISTANT_MEMORY = "assistant_memory"
        private const val KEY_SYNC_ACCOUNT_CONTEXT = "sync_account_context"
        private const val KEY_SYNCED_ACCOUNT_CONTEXT = "synced_account_context"
        private const val KEY_ACCOUNT_CONTEXT_SYNCED_AT = "account_context_synced_at"
        private const val KEY_ACCOUNT_LABEL = "account_label"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_API_KEY_EXCHANGE_ERROR = "api_key_exchange_error"
        private const val KEY_CONSUMER_ACCOUNT_NO_API_ORG = "consumer_account_no_api_org"

        private fun supportedIdleWindowMinutes(minutes: Int): Int =
            SUPPORTED_IDLE_WINDOW_MINUTES.minBy { supported ->
                kotlin.math.abs(supported.toLong() - minutes.toLong())
            }
    }
}

internal fun hasUsableAuth(
    authMode: String?,
    hasOAuthTokens: Boolean,
    hasApiKey: Boolean,
): Boolean = when (authMode) {
    CodexAuthStore.AUTH_MODE_CHATGPT -> hasOAuthTokens
    CodexAuthStore.AUTH_MODE_API_KEY -> hasApiKey
    else -> hasApiKey
}

internal enum class ApiKeyExchangeErrorClassification {
    NONE,
    CONSUMER_ACCOUNT,
    UNEXPECTED,
}

internal fun classifyApiKeyExchangeError(
    message: String?,
): ApiKeyExchangeErrorClassification {
    val normalized = message?.trim().orEmpty()
    if (normalized.isEmpty()) return ApiKeyExchangeErrorClassification.NONE
    return if (
        normalized.contains("invalid_subject_token", ignoreCase = true) ||
        normalized.contains("missing organization_id", ignoreCase = true)
    ) {
        ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT
    } else {
        ApiKeyExchangeErrorClassification.UNEXPECTED
    }
}

internal object CodexKeystoreAesGcm {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rokid_nexus_assistant_auth_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
    }

    fun decrypt(payload: String): String {
        val value = JSONObject(payload)
        val iv = Base64.decode(value.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(value.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
