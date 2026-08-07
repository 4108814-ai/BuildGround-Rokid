package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.PluginRegistrationResult

internal const val REPLAY_WINDOW_MS = 120_000L

internal fun pendingShowAgeMs(startedAtMs: Long, nowMs: Long): Long =
    if (nowMs >= startedAtMs) nowMs - startedAtMs else 0L

internal fun isReplayWindowExpired(startedAtMs: Long, nowMs: Long): Boolean =
    pendingShowAgeMs(startedAtMs, nowMs) >= REPLAY_WINDOW_MS

internal fun shouldAbandonPendingShow(
    timerGeneration: Int,
    activeGeneration: Int,
    startedAtMs: Long,
    nowMs: Long,
): Boolean = timerGeneration == activeGeneration && isReplayWindowExpired(startedAtMs, nowMs)

internal fun isTerminalRegistrationResult(result: Int): Boolean = when (result) {
    PluginRegistrationResult.DENIED,
    PluginRegistrationResult.INVALID_DESCRIPTOR,
    PluginRegistrationResult.IDENTITY_MISMATCH,
    PluginRegistrationResult.UNSUPPORTED_API -> true
    else -> false
}
