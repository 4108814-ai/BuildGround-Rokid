package com.anezium.rokidbus.plugin.assistant

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAuthStoreLogicTest {
    @Test
    fun conversationSettingsHaveExpectedDefaults() {
        val store = CodexAuthStore(FakeSharedPreferences())

        assertTrue(store.keepConversation())
        assertTrue(store.keepPhotosInConversations())
        assertEquals(CodexAuthStore.DEFAULT_IDLE_WINDOW_MINUTES, store.conversationIdleWindowMinutes())
        assertEquals("", store.assistantMemory())
    }

    @Test
    fun idleWindowSetterCoercesToNearestSupportedValue() {
        val store = CodexAuthStore(FakeSharedPreferences())

        store.setConversationIdleWindowMinutes(7)

        assertEquals(5, store.conversationIdleWindowMinutes())
        assertTrue(
            store.conversationIdleWindowMinutes() in
                CodexAuthStore.SUPPORTED_IDLE_WINDOW_MINUTES,
        )
    }

    @Test
    fun memorySetterTrimsAndTruncatesToMaximumLength() {
        val store = CodexAuthStore(FakeSharedPreferences())
        val oversized = " \n" + "x".repeat(CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS + 25) + "\t "

        store.setAssistantMemory(oversized)

        assertEquals(CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS, store.assistantMemory().length)
        assertEquals("x".repeat(CodexAuthStore.MAX_ASSISTANT_MEMORY_CHARS), store.assistantMemory())
    }

    @Test
    fun conversationSettingsRoundTripAndClearWithTheStore() {
        val store = CodexAuthStore(FakeSharedPreferences())
        store.setKeepConversation(false)
        store.setKeepPhotosInConversations(false)
        store.setConversationIdleWindowMinutes(30)
        store.setAssistantMemory("Uses metric units.")

        assertFalse(store.keepConversation())
        assertFalse(store.keepPhotosInConversations())
        assertEquals(30, store.conversationIdleWindowMinutes())
        assertEquals("Uses metric units.", store.assistantMemory())

        store.clear()

        assertTrue(store.keepConversation())
        assertTrue(store.keepPhotosInConversations())
        assertEquals(CodexAuthStore.DEFAULT_IDLE_WINDOW_MINUTES, store.conversationIdleWindowMinutes())
        assertEquals("", store.assistantMemory())
    }

    @Test
    fun readinessUsesOAuthTokensForChatGptAndKeyForApiMode() {
        assertTrue(
            hasUsableAuth(
                authMode = CodexAuthStore.AUTH_MODE_CHATGPT,
                hasOAuthTokens = true,
                hasApiKey = false,
            ),
        )
        assertTrue(
            hasUsableAuth(
                authMode = CodexAuthStore.AUTH_MODE_API_KEY,
                hasOAuthTokens = false,
                hasApiKey = true,
            ),
        )
        assertFalse(
            hasUsableAuth(
                authMode = null,
                hasOAuthTokens = false,
                hasApiKey = false,
            ),
        )
    }

    @Test
    fun classifiesConsumerAccountApiKeyExchangeFailuresCalmly() {
        assertEquals(
            ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT,
            classifyApiKeyExchangeError(
                """
                401 {"error":{"message":"Invalid ID token: missing organization_id",
                "code":"invalid_subject_token"}}
                """.trimIndent(),
            ),
        )
        assertEquals(
            ApiKeyExchangeErrorClassification.CONSUMER_ACCOUNT,
            classifyApiKeyExchangeError("token exchange failed: invalid_subject_token"),
        )
        assertEquals(
            ApiKeyExchangeErrorClassification.UNEXPECTED,
            classifyApiKeyExchangeError("ChatGPT auth failed (503): unavailable"),
        )
        assertEquals(
            ApiKeyExchangeErrorClassification.NONE,
            classifyApiKeyExchangeError(null),
        )
    }

    @Test
    fun chatGptReasoningEffortDefaultsNoneAndRoundTripsSupportedValues() {
        val store = CodexAuthStore(FakeSharedPreferences())

        assertEquals("none", store.chatGptReasoningEffort())

        listOf("none", "low", "medium", "high", "xhigh").forEach { effort ->
            store.setChatGptReasoningEffort(effort)
            assertEquals(effort, store.chatGptReasoningEffort())
        }
    }

    @Test
    fun chatGptReasoningEffortRejectsUnknownValues() {
        val store = CodexAuthStore(FakeSharedPreferences())

        listOf("minimal", "ultra", "LOW", " medium ", "").forEach { effort ->
            assertThrows(IllegalArgumentException::class.java) {
                store.setChatGptReasoningEffort(effort)
            }
        }
        assertEquals("none", store.chatGptReasoningEffort())
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                stage(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = stage(key, values?.toSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                stage(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                stage(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                stage(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                stage(key, value)

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                key?.let {
                    removals += it
                    pending -= it
                }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
                pending.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }

            private fun stage(key: String?, value: Any?): SharedPreferences.Editor = apply {
                key?.let {
                    pending[it] = value
                    removals -= it
                }
            }
        }
    }
}
