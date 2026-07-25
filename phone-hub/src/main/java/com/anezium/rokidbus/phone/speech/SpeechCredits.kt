package com.anezium.rokidbus.phone.speech

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Provider quota lookups for the Speech settings screen. */
object SpeechCredits {
    data class ElevenLabsQuota(val used: Long, val limit: Long) {
        val remaining: Long get() = (limit - used).coerceAtLeast(0L)
    }

    /**
     * Blocking; call off the main thread. Returns null on any failure — the caller shows
     * an "unavailable" state instead of an error. Requires a key with the User read scope.
     */
    fun fetchElevenLabs(apiKey: String): ElevenLabsQuota? {
        if (apiKey.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(SUBSCRIPTION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("xi-api-key", apiKey)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val used = json.optLong("character_count", -1L)
            val limit = json.optLong("character_limit", -1L)
            if (used < 0L || limit <= 0L) null else ElevenLabsQuota(used, limit)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private const val SUBSCRIPTION_URL = "https://api.elevenlabs.io/v1/user/subscription"
    private const val TIMEOUT_MS = 10_000
}
