package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatGptAccountContext(
    val memories: List<String> = emptyList(),
    val callName: String = "",
    val userRole: String = "",
    val aboutUser: String = "",
    val responsePrefs: String = "",
    val other: String = "",
)

fun ChatGptAccountContext.isEmpty(): Boolean =
    memories.none(String::isNotBlank) &&
        callName.isBlank() &&
        userRole.isBlank() &&
        aboutUser.isBlank() &&
        responsePrefs.isBlank() &&
        other.isBlank()

fun ChatGptAccountContext.renderForPrompt(maxChars: Int): String {
    if (maxChars <= 0 || isEmpty()) return ""

    val aboutUserLines = buildList {
        callName.trim().takeIf(String::isNotEmpty)?.let { add("Call the user: $it") }
        userRole.trim().takeIf(String::isNotEmpty)?.let { add("User role: $it") }
        aboutUser.trim().takeIf(String::isNotEmpty)?.let { add("About the user: $it") }
        memories
            .map(String::trim)
            .filter(String::isNotEmpty)
            .takeIf(List<String>::isNotEmpty)
            ?.let { savedMemories ->
                add("Saved memories:\n" + savedMemories.joinToString("\n") { "- $it" })
            }
        other.trim().takeIf(String::isNotEmpty)?.let { add("Other user context: $it") }
    }
    val sections = buildList {
        if (aboutUserLines.isNotEmpty()) add(aboutUserLines.joinToString("\n"))
        responsePrefs.trim().takeIf(String::isNotEmpty)?.let { preferences ->
            add("The user's response preferences:\n$preferences")
        }
    }
    return sections.joinToString("\n\n").truncateOnWordBoundary(maxChars)
}

internal fun parseMemories(json: String): List<String> = runCatching {
    val memories = JSONObject(json).optJSONArray("memories") ?: return@runCatching emptyList()
    buildList {
        for (index in 0 until memories.length()) {
            memories.optJSONObject(index)
                ?.optString("content")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::add)
        }
    }
}.getOrDefault(emptyList())

internal fun parseUserSystemMessages(json: String): ChatGptAccountContext = runCatching {
    val value = JSONObject(json)
    if (!value.optBoolean("enabled", true)) return@runCatching ChatGptAccountContext()

    val aboutModel = value.trimmedString("about_model_message")
    val modelTraits = value.trimmedString("traits_model_message")
    ChatGptAccountContext(
        callName = value.trimmedString("name_user_message"),
        userRole = value.trimmedString("role_user_message"),
        aboutUser = value.trimmedString("about_user_message"),
        responsePrefs = listOf(aboutModel, modelTraits)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString("\n"),
        other = value.trimmedString("other_user_message"),
    )
}.getOrDefault(ChatGptAccountContext())

class ChatGptAccountContextClient internal constructor(
    private val tokenProvider: () -> CodexChatGptOAuthTokenBundle?,
    private val isChatGptMode: () -> Boolean,
    private val refreshTokens: suspend () -> CodexChatGptOAuthTokenBundle,
    private val transport: ChatGptAccountContextHttpTransport =
        HttpUrlConnectionChatGptAccountContextTransport(),
) {
    constructor(context: Context) : this(
        tokenProvider = CodexAuthStore(context.applicationContext)::oauthTokens,
        isChatGptMode = {
            CodexAuthStore(context.applicationContext).authMode() ==
                CodexAuthStore.AUTH_MODE_CHATGPT
        },
        refreshTokens = { CodexChatGptOAuth.refreshStoredTokens(context.applicationContext) },
    )

    suspend fun fetch(): ChatGptAccountContext = withContext(Dispatchers.IO) {
        if (!isChatGptMode()) throw ChatGptAccountContextNotSignedInException()
        val storedTokens = tokenProvider()?.takeIf(CodexChatGptOAuthTokenBundle::isUsable)
            ?: throw ChatGptAccountContextNotSignedInException()
        val auth = FetchAuthState(storedTokens)

        val memoriesJson = fetchJson(MEMORIES_ENDPOINT, auth)
        val instructionsJson = fetchJson(USER_SYSTEM_MESSAGES_ENDPOINT, auth)
        val instructions = parseUserSystemMessages(instructionsJson)
        instructions.copy(memories = parseMemories(memoriesJson))
    }

    private suspend fun fetchJson(
        endpoint: String,
        auth: FetchAuthState,
    ): String {
        currentCoroutineContext().ensureActive()
        var response = executeGet(endpoint, auth.tokens)
        if (response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && !auth.refreshAttempted) {
            auth.refreshAttempted = true
            auth.tokens = try {
                refreshTokens().takeIf(CodexChatGptOAuthTokenBundle::isUsable)
                    ?: throw ChatGptAccountContextUnavailableException(
                        SyncUnavailableReason.REFRESH_FAILED,
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unavailable: ChatGptAccountContextUnavailableException) {
                throw unavailable
            } catch (_: Throwable) {
                throw ChatGptAccountContextUnavailableException(
                    SyncUnavailableReason.REFRESH_FAILED,
                )
            }
            response = executeGet(endpoint, auth.tokens)
        }

        when {
            response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED ->
                throw ChatGptAccountContextUnavailableException(
                    SyncUnavailableReason.UNAUTHORIZED,
                )
            response.statusCode !in 200..299 ->
                throw ChatGptAccountContextUnavailableException(
                    SyncUnavailableReason.HTTP_ERROR,
                )
        }
        if (runCatching { JSONObject(response.body) }.isFailure) {
            throw ChatGptAccountContextUnavailableException(
                SyncUnavailableReason.INVALID_RESPONSE,
            )
        }
        return response.body
    }

    private suspend fun executeGet(
        endpoint: String,
        tokens: CodexChatGptOAuthTokenBundle,
    ): ChatGptAccountContextHttpResponse = try {
        transport.execute(
            ChatGptAccountContextHttpRequest(
                endpoint = endpoint,
                headers = linkedMapOf(
                    "Authorization" to "Bearer ${tokens.accessToken}",
                    "chatgpt-account-id" to tokens.accountId,
                    "User-Agent" to DESKTOP_USER_AGENT,
                    "Accept" to "application/json",
                ),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        throw ChatGptAccountContextUnavailableException(SyncUnavailableReason.NETWORK)
    }

    private class FetchAuthState(
        var tokens: CodexChatGptOAuthTokenBundle,
        var refreshAttempted: Boolean = false,
    )

    companion object {
        internal const val MEMORIES_ENDPOINT = "https://chatgpt.com/backend-api/memories"
        internal const val USER_SYSTEM_MESSAGES_ENDPOINT =
            "https://chatgpt.com/backend-api/user_system_messages"
        internal const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}

internal data class ChatGptAccountContextHttpRequest(
    val endpoint: String,
    val headers: Map<String, String>,
)

internal data class ChatGptAccountContextHttpResponse(
    val statusCode: Int,
    val body: String = "",
)

internal fun interface ChatGptAccountContextHttpTransport {
    suspend fun execute(
        request: ChatGptAccountContextHttpRequest,
    ): ChatGptAccountContextHttpResponse
}

internal class HttpUrlConnectionChatGptAccountContextTransport :
    ChatGptAccountContextHttpTransport {
    override suspend fun execute(
        request: ChatGptAccountContextHttpRequest,
    ): ChatGptAccountContextHttpResponse {
        val connection = (URL(request.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return ChatGptAccountContextHttpResponse(statusCode = status, body = body)
        } finally {
            connection.disconnect()
        }
    }
}

internal class ChatGptAccountContextNotSignedInException : Exception()

internal class ChatGptAccountContextUnavailableException(
    val reason: SyncUnavailableReason,
) : Exception(reason.name)

private fun JSONObject.trimmedString(name: String): String = optString(name).trim()

private fun CodexChatGptOAuthTokenBundle.isUsable(): Boolean =
    accessToken.isNotBlank() && accountId.isNotBlank()

private fun String.truncateOnWordBoundary(maxChars: Int): String {
    val trimmed = trim()
    if (trimmed.length <= maxChars) return trimmed
    val candidate = trimmed.take(maxChars)
    if (candidate.lastOrNull()?.isWhitespace() == true || trimmed[maxChars].isWhitespace()) {
        return candidate.trimEnd()
    }
    val boundary = candidate.indexOfLast(Char::isWhitespace)
    return if (boundary <= 0) "" else candidate.take(boundary).trimEnd()
}
