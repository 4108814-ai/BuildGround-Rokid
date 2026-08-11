package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface HermesDiscoveryResult {
    data class Detected(val model: String?) : HermesDiscoveryResult

    data object NotHermes : HermesDiscoveryResult

    data object Unavailable : HermesDiscoveryResult
}

internal class HermesCapabilitiesClient {
    suspend fun discover(baseUrl: String, apiKey: String): HermesDiscoveryResult =
        withContext(Dispatchers.IO) {
            val endpoint = capabilitiesUrl(baseUrl) ?: return@withContext HermesDiscoveryResult.Unavailable
            val connection = runCatching {
                (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }.getOrElse { return@withContext HermesDiscoveryResult.Unavailable }

            try {
                when (connection.responseCode) {
                    in 200..299 -> connection.inputStream.bufferedReader().use { reader ->
                        parseHermesCapabilities(reader.readText())
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> HermesDiscoveryResult.NotHermes
                    else -> HermesDiscoveryResult.Unavailable
                }
            } catch (_: Exception) {
                HermesDiscoveryResult.Unavailable
            } finally {
                connection.disconnect()
            }
        }

    internal companion object {
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val CAPABILITIES_OBJECT = "hermes.api_server.capabilities"

        fun capabilitiesUrl(baseUrl: String): String? = baseUrl
            .trim()
            .trimEnd('/')
            .takeIf(String::isNotBlank)
            ?.let { "$it/capabilities" }

        fun parseHermesCapabilities(payload: String): HermesDiscoveryResult {
            val value = runCatching { JSONObject(payload) }.getOrNull()
                ?: return HermesDiscoveryResult.NotHermes
            // The manifest's own object name is the Hermes namespace itself, so it is the
            // only field the documented contract guarantees. `runtime` is optional and
            // only ever disqualifies a server that says it wants client-side tools.
            val runtime = value.optJSONObject("runtime")
            val clientToolRuntime = runtime?.optString("tool_execution")
                ?.takeIf(String::isNotBlank)
                ?.let { it != "server" } == true
            val isHermes = value.optString("object") == CAPABILITIES_OBJECT && !clientToolRuntime
            return if (isHermes) {
                HermesDiscoveryResult.Detected(
                    model = value.optString("model").trim().takeIf(String::isNotBlank),
                )
            } else {
                HermesDiscoveryResult.NotHermes
            }
        }
    }
}
