package com.anezium.rokidbus.plugin.agents

import java.net.URI
import kotlin.math.roundToLong
import kotlin.random.Random

internal sealed interface ConnectionOutcome {
    data object Retry : ConnectionOutcome
    data class RetryWithDetail(val detail: String) : ConnectionOutcome
    data class AuthFailed(val detail: String) : ConnectionOutcome
}

internal fun reconnectDelayMs(attempt: Int, random: Random = Random.Default): Long {
    val base = when (attempt.coerceAtLeast(0)) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        else -> 5_000L
    }
    return (base * random.nextDouble(0.8, 1.2)).roundToLong().coerceIn(800L, 6_000L)
}

internal fun webSocketUrl(hostInput: String, port: Int): String {
    val input = hostInput.trim().trimEnd('/')
    if (input.startsWith("ws://", ignoreCase = true) ||
        input.startsWith("wss://", ignoreCase = true)
    ) {
        val uri = URI(input)
        val scheme = uri.scheme.lowercase()
        val host = uri.host ?: throw IllegalArgumentException("Invalid WebSocket host")
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val path = uri.rawPath?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
        return "$scheme://$renderedHost:$port$path"
    }
    val renderedHost = if (input.contains(':') && !input.startsWith("[")) "[$input]" else input
    return "ws://$renderedHost:$port"
}
