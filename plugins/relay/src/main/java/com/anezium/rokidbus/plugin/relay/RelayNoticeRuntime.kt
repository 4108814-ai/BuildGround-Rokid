package com.anezium.rokidbus.plugin.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeImage
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.speechSession
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import java.util.ArrayDeque

/** One bus connection per live notice/reply exchange; it closes when that band closes. */
internal class RelayNoticeRuntime(context: Context) : NexusPluginCallbacks {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val essentialUpdates = ArrayDeque<NexusNoticeUpdate>()

    private var client: NexusPluginClient? = null
    private var pendingShow: ReplyRepository.PendingReply? = null
    private var currentReply: ReplyRepository.PendingReply? = null
    private var currentTranscript: String? = null
    private var activeNotice = false
    private var showGeneration = 0
    private var speechGeneration = 0
    private var speechFinalReceived = false
    private var speech: NexusSpeechSession? = null
    private var pendingPartial: NexusNoticeUpdate? = null
    private var updateDrainScheduled = false
    private var lastNoticeMessageAtMs = Long.MIN_VALUE

    fun show(reply: ReplyRepository.PendingReply) = onMain {
        showGeneration += 1
        val generation = showGeneration
        invalidateSpeech()
        essentialUpdates.clear()
        pendingPartial = null
        currentReply = reply
        currentTranscript = null
        pendingShow = reply

        if (client == null) {
            client = NexusPluginClient.create(appContext, PLUGIN_ID, this).also(NexusPluginClient::connect)
        }
        tryShowPending()
        main.postDelayed({
            if (showGeneration == generation && pendingShow != null) {
                Log.i(TAG, "notice delivery timed out")
                closeClient()
            }
        }, SHOW_TIMEOUT_MS)
    }

    fun shutdown() = onMain {
        client?.hideNotice()
        closeClient()
    }

    override fun onOpen() = Unit

    override fun onClose() = Unit

    override fun onInput(event: NexusInputEvent) = Unit

    override fun onLinkState(state: Int) = onMain { tryShowPending() }

    override fun onRegistrationState(result: Int) = onMain {
        if (result == PluginRegistrationResult.APPROVED) {
            tryShowPending()
        } else {
            Log.i(TAG, "registration unavailable result=$result")
            closeClient()
        }
    }

    override fun onNoticeAction(id: String) = onMain {
        when (id) {
            ACTION_REPLY, ACTION_RETRY -> startListening()
            ACTION_SEND -> sendConfirmedReply()
            ACTION_DISMISS, ACTION_CANCEL -> dismissNotice()
        }
    }

    override fun onNoticeClosed(reason: NexusNoticeCloseReason) = onMain {
        closeClient()
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) = Unit

    private fun tryShowPending() {
        val reply = pendingShow ?: return
        val currentClient = client ?: return
        if (!currentClient.isApproved || !currentClient.supportsNoticeSurface) return

        val preview = reply.imagePreview
        val image = preview?.let {
            NexusNoticeImage(
                contentKey = it.id,
                mimeType = it.mimeType,
                pixelWidth = it.width,
                pixelHeight = it.height,
            )
        }
        val notice = NexusNotice(
            title = reply.content.title,
            body = reply.content.renderedText.takeIf(String::isNotBlank),
            footer = reply.footer.takeIf(String::isNotBlank),
            actions = INITIAL_ACTIONS,
            image = image?.takeIf { currentClient.supportsImageSurface },
            wakeDisplay = true,
        )
        val result = if (notice.image != null && preview != null) {
            currentClient.showNotice(notice, preview.bytes)
        } else {
            currentClient.showNotice(notice)
        }
        Log.i(
            TAG,
            "notice show result=$result textChars=${reply.content.renderedText.length} " +
                "imageBytes=${preview?.bytes?.size ?: 0}",
        )
        if (result == NexusSdkResult.SENT) {
            pendingShow = null
            activeNotice = true
            lastNoticeMessageAtMs = SystemClock.uptimeMillis()
        } else if (result != NexusSdkResult.CAPABILITY_NOT_AVAILABLE) {
            closeClient()
        }
    }

    private fun startListening() {
        val currentClient = client ?: return
        if (!activeNotice || currentReply == null) return
        invalidateSpeech()
        currentTranscript = null
        speechFinalReceived = false
        queueEssential(NexusNoticeUpdate(footer = "Listening…"), dropPartial = true)

        val generation = speechGeneration
        val newSpeech = currentClient.speechSession(object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) = Unit

            override fun onSpeechState(state: NexusSpeechState) = Unit

            override fun onSpeechPartial(text: String) = onMain {
                if (generation != speechGeneration || text.isBlank()) return@onMain
                queuePartial(
                    NexusNoticeUpdate(
                        footer = NotificationTextExtractor.trimFromTop(
                            text,
                            NoticeSurfaceContract.MAX_FOOTER_CHARS,
                        ),
                    ),
                )
            }

            override fun onSpeechFinal(text: String) = onMain {
                if (generation != speechGeneration) return@onMain
                if (text.isBlank()) {
                    queueSpeechFailure("No speech")
                    return@onMain
                }
                speechFinalReceived = true
                currentTranscript = text
                pendingPartial = null
                queueEssential(
                    NexusNoticeUpdate(
                        body = NotificationTextExtractor.trimFromTop(
                            text,
                            NoticeSurfaceContract.MAX_BODY_CHARS,
                        ),
                        footer = "Review, then send",
                        actions = CONFIRM_ACTIONS,
                    ),
                    dropPartial = true,
                )
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) = onMain {
                if (generation != speechGeneration) return@onMain
                speech = null
                if (speechFinalReceived && reason == NexusSpeechStopReason.COMPLETED) return@onMain
                val detail = error?.kind?.takeIf(String::isNotBlank)
                queueSpeechFailure(detail ?: speechReasonLabel(reason))
            }
        })
        speech = newSpeech
        val result = newSpeech.start()
        if (result != NexusSdkResult.SENT) {
            speech = null
            queueSpeechFailure(result.name)
        }
    }

    private fun sendConfirmedReply() {
        val reply = currentReply ?: return
        val transcript = currentTranscript
        if (transcript.isNullOrBlank()) {
            queueSpeechFailure("Empty transcript")
            return
        }
        when (val result = ReplyRepository.sendReply(appContext, reply.id, transcript)) {
            ReplySendResult.Sent -> {
                currentTranscript = null
                queueEssential(NexusNoticeUpdate(footer = "Sent"), dropPartial = true)
            }
            ReplySendResult.Missing -> queueSendFailure("Notification gone")
            ReplySendResult.Blank -> queueSendFailure("Empty reply")
            ReplySendResult.NoFreeFormInput -> queueSendFailure("Reply unavailable")
            is ReplySendResult.Failed -> queueSendFailure(result.causeClass)
        }
    }

    private fun queueSendFailure(cause: String) {
        queueEssential(
            NexusNoticeUpdate(
                footer = fitFooter("Reply failed: $cause"),
                actions = CONFIRM_ACTIONS,
            ),
            dropPartial = true,
        )
    }

    private fun queueSpeechFailure(cause: String) {
        pendingPartial = null
        queueEssential(
            NexusNoticeUpdate(
                footer = fitFooter("Voice failed: $cause"),
                actions = SPEECH_FAILURE_ACTIONS,
            ),
            dropPartial = true,
        )
    }

    private fun dismissNotice() {
        invalidateSpeech()
        pendingPartial = null
        essentialUpdates.clear()
        client?.hideNotice()
        main.postDelayed({
            if (activeNotice) closeClient()
        }, HIDE_FALLBACK_MS)
    }

    private fun invalidateSpeech() {
        speechGeneration += 1
        speechFinalReceived = false
        speech?.stop()
        speech = null
    }

    private fun queuePartial(update: NexusNoticeUpdate) {
        pendingPartial = update
        scheduleUpdateDrain()
    }

    private fun queueEssential(update: NexusNoticeUpdate, dropPartial: Boolean) {
        if (dropPartial) pendingPartial = null
        essentialUpdates.addLast(update)
        scheduleUpdateDrain()
    }

    private fun scheduleUpdateDrain() {
        if (updateDrainScheduled || !activeNotice) return
        val now = SystemClock.uptimeMillis()
        val earliest = if (lastNoticeMessageAtMs == Long.MIN_VALUE) now else {
            lastNoticeMessageAtMs + MIN_NOTICE_MESSAGE_INTERVAL_MS
        }
        updateDrainScheduled = true
        main.postDelayed(::drainOneUpdate, (earliest - now).coerceAtLeast(0L))
    }

    private fun drainOneUpdate() {
        updateDrainScheduled = false
        if (!activeNotice) return
        val update = if (essentialUpdates.isNotEmpty()) {
            essentialUpdates.removeFirst()
        } else {
            pendingPartial.also { pendingPartial = null }
        } ?: return
        val result = client?.updateNotice(update)
        lastNoticeMessageAtMs = SystemClock.uptimeMillis()
        if (result != NexusSdkResult.SENT) Log.i(TAG, "notice update result=$result")
        if (essentialUpdates.isNotEmpty() || pendingPartial != null) scheduleUpdateDrain()
    }

    private fun closeClient() {
        showGeneration += 1
        invalidateSpeech()
        essentialUpdates.clear()
        pendingPartial = null
        updateDrainScheduled = false
        pendingShow = null
        currentReply = null
        currentTranscript = null
        activeNotice = false
        client?.close()
        client = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun fitFooter(value: String): String =
        value.trim().take(NoticeSurfaceContract.MAX_FOOTER_CHARS)

    private fun speechReasonLabel(reason: NexusSpeechStopReason): String = when (reason) {
        NexusSpeechStopReason.COMPLETED -> "No transcript"
        NexusSpeechStopReason.CANCELLED -> "Cancelled"
        NexusSpeechStopReason.NO_SPEECH -> "No speech"
        NexusSpeechStopReason.ERROR -> "Recognition error"
        NexusSpeechStopReason.LINK_LOST -> "Link lost"
        NexusSpeechStopReason.REVOKED -> "Permission revoked"
        NexusSpeechStopReason.DENIED_BUSY -> "Speech busy"
        NexusSpeechStopReason.DENIED_NO_LINK -> "No glasses link"
        NexusSpeechStopReason.DENIED_NOT_READY -> "Speech not ready"
        NexusSpeechStopReason.DENIED_START_FAILED -> "Start failed"
        NexusSpeechStopReason.DENIED_INVALID -> "Invalid request"
    }

    private companion object {
        const val TAG = "NexusRelayNotice"
        const val PLUGIN_ID = "relay"
        const val SHOW_TIMEOUT_MS = 5_000L
        const val HIDE_FALLBACK_MS = 500L
        const val MIN_NOTICE_MESSAGE_INTERVAL_MS = 210L

        const val ACTION_REPLY = "reply"
        const val ACTION_DISMISS = "dismiss"
        const val ACTION_SEND = "send"
        const val ACTION_RETRY = "retry"
        const val ACTION_CANCEL = "cancel"

        val INITIAL_ACTIONS = listOf(
            NexusNoticeAction(ACTION_REPLY, "reply", "Reply"),
            NexusNoticeAction(ACTION_DISMISS, "cancel", "Dismiss"),
        )
        val CONFIRM_ACTIONS = listOf(
            NexusNoticeAction(ACTION_SEND, "send", "Send"),
            NexusNoticeAction(ACTION_RETRY, "retry", "Retry"),
            NexusNoticeAction(ACTION_CANCEL, "cancel", "Cancel"),
        )
        val SPEECH_FAILURE_ACTIONS = listOf(
            NexusNoticeAction(ACTION_RETRY, "mic", "Speak again"),
            NexusNoticeAction(ACTION_CANCEL, "cancel", "Cancel"),
        )
    }
}
