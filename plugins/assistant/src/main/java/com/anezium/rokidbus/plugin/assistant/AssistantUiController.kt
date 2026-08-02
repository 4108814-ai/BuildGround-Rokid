package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface AssistantUiRenderer {
    val supportsNoticeSurface: Boolean

    fun showNotice(notice: NexusNotice): NexusSdkResult

    fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult

    fun hideNotice(): NexusSdkResult

    fun showCard(
        lines: List<String>,
        forceShow: Boolean,
    ): NexusSdkResult
}

internal class AssistantUiController(
    private val scope: CoroutineScope,
    private val renderer: AssistantUiRenderer,
    private val cancelPipeline: () -> Unit,
    private val resetCapture: () -> Unit,
    private val launcherHintDelayMs: Long = LAUNCHER_HINT_DELAY_MS,
    private val errorNoticeDurationMs: Long = ERROR_NOTICE_DURATION_MS,
    private val transcriptUpdateIntervalMs: Long = TRANSCRIPT_UPDATE_INTERVAL_MS,
    private val keepaliveIntervalMs: Long = NOTICE_KEEPALIVE_INTERVAL_MS,
) {
    private var launcherHintJob: Job? = null
    private var noticeHideJob: Job? = null
    private var transcriptUpdateJob: Job? = null
    private var keepaliveJob: Job? = null
    private var lastInFlightBody: String? = null
    private var pendingTranscriptBody: String? = null
    private var noticeStateVersion = 0L
    private var surfaceShown = false
    private var noticeShown = false
    private var answerCardStarted = false

    /**
     * True while the band is the render target. A surface card the wearer already
     * has open keeps that interaction on the card: hiding it from here would read
     * as a self-close to the hub, which tears the whole plugin session down.
     */
    val isNoticeBandMode: Boolean
        get() = renderer.supportsNoticeSurface && !surfaceShown

    fun onOpen() {
        cancelLauncherHint()
        stopKeepalive()
        startNewState(flushTranscript = false)
        surfaceShown = false
        noticeShown = false
        answerCardStarted = false
        launcherHintJob = scope.launch {
            delay(launcherHintDelayMs)
            launcherHintJob = null
            startNewState()
            hideNoticeIfShown()
            showCard(
                lines = listOf(LAUNCHER_HINT),
                forceShow = true,
            )
        }
    }

    fun onClose() {
        cancelLauncherHint()
        stopKeepalive()
        startNewState(flushTranscript = false)
        surfaceShown = false
        noticeShown = false
        answerCardStarted = false
    }

    fun cancelLauncherHint() {
        launcherHintJob?.cancel()
        launcherHintJob = null
    }

    fun beginGestureFlow() {
        cancelLauncherHint()
        discardPendingTranscript()
    }

    fun showTransient(
        body: String,
        legacyForceShow: Boolean = false,
    ) {
        cancelLauncherHint()
        startNewState()
        answerCardStarted = false
        if (useNoticeBand()) {
            // In-flight states (Listening, Thinking, Searching) have no natural
            // stream of updates to keep restarting the band's TTL — a wearer
            // who takes five seconds to start speaking would watch the band
            // vanish under them. The keepalive resends the latest in-flight
            // body until a terminal state (answer, error, hide) takes over.
            lastInFlightBody = body
            showOrUpdateNotice(body)
            startKeepalive()
        } else {
            showCard(listOf(body), forceShow = legacyForceShow || !surfaceShown)
        }
    }

    fun showTranscript(text: String) {
        if (!useNoticeBand()) return
        val body = truncateTranscriptTail(text)
        if (body.isBlank()) return
        pendingTranscriptBody = body
        if (transcriptUpdateJob != null) return

        renderPendingTranscript()
        transcriptUpdateJob = scope.launch {
            while (true) {
                delay(transcriptUpdateIntervalMs)
                if (pendingTranscriptBody == null) break
                renderPendingTranscript()
            }
            transcriptUpdateJob = null
        }
    }

    fun showError(
        body: String,
        legacyCardLines: List<String> = listOf(body),
        legacyForceShow: Boolean = false,
    ) {
        cancelLauncherHint()
        stopKeepalive()
        val stateVersion = startNewState()
        answerCardStarted = false
        if (useNoticeBand()) {
            if (showOrUpdateNotice(body)) {
                noticeHideJob = scope.launch {
                    delay(errorNoticeDurationMs)
                    noticeHideJob = null
                    if (noticeStateVersion == stateVersion) {
                        hideNoticeIfShown()
                    }
                }
            }
        } else {
            showCard(
                lines = legacyCardLines,
                forceShow = legacyForceShow || !surfaceShown,
            )
        }
    }

    fun showAnswer(
        body: String,
        legacyCardLines: List<String>,
    ) {
        cancelLauncherHint()
        stopKeepalive()
        startNewState()
        if (useNoticeBand()) {
            val answer = truncateAnswerHead(body)
            showOrUpdateNotice(answer, ttlMs = answerTtlMs(answer))
            return
        }

        hideNoticeIfShown()
        val result = showCard(
            lines = legacyCardLines,
            forceShow = !answerCardStarted,
        )
        if (result == NexusSdkResult.SENT) {
            answerCardStarted = true
        }
    }

    fun onSurfaceHidden() {
        surfaceShown = false
        answerCardStarted = false
    }

    fun onNoticeClosed(reason: NexusNoticeCloseReason) {
        stopKeepalive()
        startNewState(flushTranscript = false)
        noticeShown = false
        if (reason == NexusNoticeCloseReason.USER) {
            cancelPipeline()
            resetCapture()
        }
    }

    private fun useNoticeBand(): Boolean = isNoticeBandMode

    private fun showOrUpdateNotice(
        body: String,
        ttlMs: Long? = null,
    ): Boolean {
        val safeBody = truncateNoticeHead(body)
        val result = if (noticeShown) {
            renderer.updateNotice(NexusNoticeUpdate(body = safeBody, ttlMs = ttlMs))
        } else {
            renderer.showNotice(
                NexusNotice(
                    title = NOTICE_TITLE,
                    body = safeBody,
                    ttlMs = ttlMs,
                ),
            )
        }
        if (result == NexusSdkResult.SENT) {
            noticeShown = true
            return true
        }
        noticeShown = false
        return false
    }

    private fun hideNoticeIfShown() {
        stopKeepalive()
        if (!noticeShown) return
        noticeShown = false
        renderer.hideNotice()
    }

    private fun startKeepalive() {
        if (keepaliveJob != null) return
        keepaliveJob = scope.launch {
            while (true) {
                delay(keepaliveIntervalMs)
                val body = lastInFlightBody
                if (body == null || !noticeShown || !useNoticeBand()) break
                showOrUpdateNotice(body)
            }
            keepaliveJob = null
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        lastInFlightBody = null
    }

    private fun renderPendingTranscript() {
        val body = pendingTranscriptBody ?: return
        pendingTranscriptBody = null
        if (useNoticeBand()) {
            lastInFlightBody = body
            showOrUpdateNotice(body)
            startKeepalive()
        }
    }

    private fun flushPendingTranscript() {
        transcriptUpdateJob?.cancel()
        transcriptUpdateJob = null
        renderPendingTranscript()
    }

    private fun discardPendingTranscript() {
        transcriptUpdateJob?.cancel()
        transcriptUpdateJob = null
        pendingTranscriptBody = null
    }

    private fun showCard(
        lines: List<String>,
        forceShow: Boolean,
    ): NexusSdkResult {
        val result = renderer.showCard(lines, forceShow)
        if (result == NexusSdkResult.SENT) {
            surfaceShown = true
        }
        return result
    }

    private fun startNewState(flushTranscript: Boolean = true): Long {
        if (flushTranscript) {
            flushPendingTranscript()
        } else {
            discardPendingTranscript()
        }
        noticeStateVersion += 1
        noticeHideJob?.cancel()
        noticeHideJob = null
        return noticeStateVersion
    }

    private fun truncateTranscriptTail(text: String): String {
        val normalized = normalizeNoticeText(text)
        if (normalized.length <= MAX_NOTICE_BODY_CHARS) return normalized
        return "$ELLIPSIS ${normalized.takeLast(TRANSCRIPT_TAIL_CHARS).trimStart()}"
    }

    private fun truncateAnswerHead(text: String): String = truncateNoticeHead(text)

    private fun truncateNoticeHead(text: String): String {
        val normalized = normalizeNoticeText(text).ifBlank { ELLIPSIS }
        if (normalized.length <= MAX_NOTICE_BODY_CHARS) return normalized
        return normalized
            .take(MAX_NOTICE_BODY_CHARS - ELLIPSIS.length)
            .trimEnd() + ELLIPSIS
    }

    private fun normalizeNoticeText(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    /**
     * A long answer earns its reading time: roughly a character's worth of
     * milliseconds each, within the band's contract clamp. The TTL restarts on
     * every accepted update, so streaming keeps the band alive on its own.
     */
    private fun answerTtlMs(body: String): Long =
        (body.length * ANSWER_TTL_PER_CHAR_MS).coerceIn(ANSWER_TTL_MIN_MS, ANSWER_TTL_MAX_MS)

    internal companion object {
        const val LAUNCHER_HINT_DELAY_MS = 400L
        const val ERROR_NOTICE_DURATION_MS = 2_500L
        const val TRANSCRIPT_UPDATE_INTERVAL_MS = 300L
        const val NOTICE_KEEPALIVE_INTERVAL_MS = 3_000L
        const val ANSWER_TTL_PER_CHAR_MS = 75L
        const val ANSWER_TTL_MIN_MS = 8_000L
        const val ANSWER_TTL_MAX_MS = 20_000L
        const val MAX_NOTICE_BODY_CHARS = 240
        const val TRANSCRIPT_TAIL_CHARS = 200
        const val LAUNCHER_HINT = "Press the assist button, then speak."
        const val NOTICE_TITLE = "Assistant"
        const val ELLIPSIS = "…"
    }
}
