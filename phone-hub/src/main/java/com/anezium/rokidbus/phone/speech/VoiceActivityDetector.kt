package com.anezium.rokidbus.phone.speech

import kotlin.math.abs

data class VoiceActivityConfig(
    val averageAbsThreshold: Int = 350,
    val peakAbsThreshold: Int = 2_800,
    val minCaptureMs: Long = 2_500L,
    val silenceAfterSpeechMs: Long = 2_500L,
    val firstByteTimeoutMs: Long = 1_800L,
    val initialNoSpeechTimeoutMs: Long = 8_000L,
    val maxCaptureMs: Long = 30_000L,
)

data class VoiceActivity(
    val averageAbs: Int,
    val peakAbs: Int,
    val isVoice: Boolean,
)

data class VoiceActivitySnapshot(
    val totalBytes: Long,
    val averageAbs: Int,
    val peakAbs: Int,
    val speechDetected: Boolean,
    val activeForMs: Long,
    val silenceForMs: Long,
)

class VoiceActivityDetector(
    private val config: VoiceActivityConfig = VoiceActivityConfig(),
) {
    var totalBytes: Long = 0L
        private set
    var speechDetected: Boolean = false
        private set

    private var startedAtMs: Long = 0L
    private var lastVoiceAtMs: Long = 0L
    private var averageAbs: Int = 0
    private var peakAbs: Int = 0

    fun reset(nowMs: Long) {
        startedAtMs = nowMs
        lastVoiceAtMs = 0L
        totalBytes = 0L
        speechDetected = false
        averageAbs = 0
        peakAbs = 0
    }

    fun acceptPcm16Le(data: ByteArray, offset: Int, length: Int, nowMs: Long): VoiceActivity {
        val activity = detectPcm16Le(data, offset, length)
        totalBytes += length.coerceAtLeast(0)
        averageAbs = activity.averageAbs
        peakAbs = activity.peakAbs
        if (activity.isVoice) {
            speechDetected = true
            lastVoiceAtMs = nowMs
        }
        return activity
    }

    fun closeReason(nowMs: Long): String? {
        val activeForMs = activeForMs(nowMs)
        val silenceForMs = silenceForMs(nowMs)
        return when {
            speechDetected &&
                activeForMs >= config.minCaptureMs &&
                silenceForMs >= config.silenceAfterSpeechMs ->
                "silence-after-speech activeForMs=$activeForMs silenceForMs=$silenceForMs"
            !speechDetected &&
                activeForMs >= config.firstByteTimeoutMs &&
                totalBytes == 0L ->
                "no-audio-bytes activeForMs=$activeForMs"
            !speechDetected &&
                activeForMs >= config.initialNoSpeechTimeoutMs &&
                totalBytes > 0L ->
                "no-vad-speech-timeout activeForMs=$activeForMs level=$averageAbs peak=$peakAbs"
            activeForMs >= config.maxCaptureMs ->
                "safety-max activeForMs=$activeForMs"
            else -> null
        }
    }

    fun snapshot(nowMs: Long): VoiceActivitySnapshot =
        VoiceActivitySnapshot(
            totalBytes = totalBytes,
            averageAbs = averageAbs,
            peakAbs = peakAbs,
            speechDetected = speechDetected,
            activeForMs = activeForMs(nowMs),
            silenceForMs = silenceForMs(nowMs),
        )

    private fun activeForMs(nowMs: Long): Long =
        if (startedAtMs == 0L) 0L else (nowMs - startedAtMs).coerceAtLeast(0L)

    private fun silenceForMs(nowMs: Long): Long =
        if (lastVoiceAtMs == 0L) 0L else (nowMs - lastVoiceAtMs).coerceAtLeast(0L)

    private fun detectPcm16Le(data: ByteArray, offset: Int, length: Int): VoiceActivity {
        val safeOffset = offset.coerceIn(0, data.size)
        val end = (safeOffset + length.coerceAtLeast(0)).coerceAtMost(data.size)
        if (end - safeOffset < 2) return VoiceActivity(0, 0, false)
        var sumAbs = 0L
        var peak = 0
        var samples = 0
        var index = safeOffset
        while (index + 1 < end) {
            val low = data[index].toInt() and 0xff
            val high = data[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(sample)
            sumAbs += magnitude
            if (magnitude > peak) peak = magnitude
            samples += 1
            index += 2
        }
        val average = if (samples == 0) 0 else (sumAbs / samples).toInt()
        return VoiceActivity(
            averageAbs = average,
            peakAbs = peak,
            isVoice = average >= config.averageAbsThreshold || peak >= config.peakAbsThreshold,
        )
    }
}
