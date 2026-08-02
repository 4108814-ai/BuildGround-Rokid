package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsValidationResult
import org.json.JSONObject

internal sealed interface PhoneTtsGateResult {
    data class Accepted(val payload: JSONObject) : PhoneTtsGateResult
    data class Rejected(val code: String) : PhoneTtsGateResult
}

/** Pure owner injection, validation, and per-plugin command budgeting for TTS. */
internal class PhoneTtsRequestGate(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val recentCommandsByPlugin = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun evaluate(
        ownerPluginId: String,
        path: String,
        payload: JSONObject,
        hasBinary: Boolean,
    ): PhoneTtsGateResult {
        if (hasBinary) return rejected(TtsContract.ERROR_INVALID_TTS)
        val normalized = when (path) {
            BusPaths.TTS_SPEAK -> when (val result = TtsContract.validateSpeak(payload)) {
                is TtsValidationResult.Valid ->
                    TtsContract.speakPayload(
                        result.value.utteranceId,
                        result.value.text,
                        result.value.lang,
                    )
                is TtsValidationResult.Invalid -> return rejected(result.reason)
            }
            BusPaths.TTS_STOP -> when (val result = TtsContract.validateStop(payload)) {
                is TtsValidationResult.Valid -> TtsContract.stopPayload(result.value.utteranceId)
                is TtsValidationResult.Invalid -> return rejected(result.reason)
            }
            else -> return rejected(TtsContract.ERROR_INVALID_TTS)
        }
        if (!admit(ownerPluginId, nowMs())) {
            return rejected(TtsContract.ERROR_TTS_RATE_LIMITED)
        }
        return PhoneTtsGateResult.Accepted(TtsContract.withOwner(normalized, ownerPluginId))
    }

    private fun admit(ownerPluginId: String, now: Long): Boolean {
        val recent = recentCommandsByPlugin.getOrPut(ownerPluginId) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() >= RATE_WINDOW_MS) {
            recent.removeFirst()
        }
        if (recent.size >= TtsContract.MAX_MESSAGES_PER_SECOND) return false
        recent.addLast(now)
        return true
    }

    private fun rejected(code: String) = PhoneTtsGateResult.Rejected(code)

    private companion object {
        const val RATE_WINDOW_MS = 1_000L
    }
}

internal object PhoneTtsCapabilityPolicy {
    fun isAvailable(ttsVersion: Int, linkState: Int): Boolean =
        ttsVersion == TtsContract.VERSION &&
            linkState and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) != 0
}
