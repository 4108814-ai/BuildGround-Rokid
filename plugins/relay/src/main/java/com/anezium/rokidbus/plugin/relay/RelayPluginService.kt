package com.anezium.rokidbus.plugin.relay

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/** Menu-launched inbox. The notification-band runtime remains a separate, untouched flow. */
class RelayPluginService : NexusPluginService() {
    private enum class ThreadMode {
        READING,
        LISTENING,
        REVIEW,
        VOICE_FAILURE,
        SENT,
    }

    private enum class ReplyChoice(val label: String) {
        SEND("Send"),
        RETRY("Retry"),
        CANCEL("Cancel"),
    }

    private var surface: NexusSurfaceSession? = null
    private var entries: List<RelayInboxEntry> = emptyList()
    private val selection = RelayInboxSelection()

    private var threadMode = ThreadMode.READING
    private var threadStatus: String? = null
    private var selectedChoice = 0
    private var transcript: String? = null
    private var speechPreview = ""
    private var speechStatus = "Starting speech..."
    private var speechFinalReceived = false
    private var speechGeneration = 0
    private var speech: NexusSpeechSession? = null

    override fun onNexusOpen() {
        invalidateSpeech()
        entries = ReplyRepository.inboxEntries()
        selection.reset(entries.map(RelayInboxEntry::id))
        resetThreadMode()
        surface = nexusSurfaceSession(SURFACE_ID)
        renderList(show = true)
    }

    override fun onNexusClose() {
        invalidateSpeech()
        entries = emptyList()
        selection.reset(emptyList())
        resetThreadMode()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> move(1)

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> move(-1)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> confirm()

            KeyEvent.KEYCODE_BACK -> back()
        }
    }

    private fun move(delta: Int) {
        if (selection.view == RelayInboxView.LIST) {
            refreshEntries()
            if (selection.move(delta)) renderList(show = false)
            return
        }
        val choices = visibleChoices()
        if (choices.isEmpty()) return
        selectedChoice = Math.floorMod(selectedChoice + delta, choices.size)
        renderThread(show = false)
    }

    private fun confirm() {
        if (selection.view == RelayInboxView.LIST) {
            refreshEntries()
            if (selection.openSelected() != null) {
                resetThreadMode()
                renderThread(show = false)
            }
            return
        }

        when (threadMode) {
            ThreadMode.READING -> startListening()
            ThreadMode.LISTENING,
            ThreadMode.SENT,
            -> Unit
            ThreadMode.REVIEW,
            ThreadMode.VOICE_FAILURE,
            -> activateChoice()
        }
    }

    private fun back() {
        if (selection.view == RelayInboxView.THREAD && threadMode != ThreadMode.READING) {
            invalidateSpeech()
            resetThreadMode()
            renderThread(show = false)
            return
        }
        when (selection.back()) {
            RelayInboxBackResult.SHOW_LIST -> {
                resetThreadMode()
                renderList(show = false)
            }
            RelayInboxBackResult.CLOSE_SURFACE -> surface?.hide()
        }
    }

    private fun renderList(show: Boolean) {
        refreshEntries()
        val rows = entries.mapIndexed { index, entry ->
            val selected = index == selection.selectedIndex
            NexusCardLine(
                text = RelayInboxCatalog.lineFor(entry, selected),
                sub = RelayInboxCatalog.previewFor(entry).takeIf(String::isNotBlank),
                // A thread that can no longer be answered is present but is not
                // competing for the wearer's attention.
                tone = if (entry.availability == RelayReplyAvailability.REPLIABLE) {
                    NexusRowTone.NORMAL
                } else {
                    NexusRowTone.DIM
                },
                selected = selected,
            )
        }
        val footer = if (entries.isEmpty()) {
            "back to close"
        } else {
            "${selection.selectedIndex + 1}/${entries.size} · scroll · tap to open"
        }
        sendCard(
            NexusCard(
                title = "Messages",
                lines = if (entries.isEmpty()) listOf("Nothing yet.") else emptyList(),
                subtitle = if (entries.isEmpty()) null else "${entries.size} waiting",
                footer = footer,
                contentKey = LIST_CONTENT_KEY,
                richLines = rows.takeIf { it.isNotEmpty() },
                handlesBack = false,
            ),
            show = show,
        )
    }

    private fun renderThread(show: Boolean) {
        refreshEntries()
        val entry = currentEntry()
        if (entry == null) {
            resetThreadMode()
            renderList(show = false)
            return
        }
        val snapshot = entry.snapshot
        val lines = when (threadMode) {
            ThreadMode.READING -> buildList {
                addAll(RelayInboxCatalog.cardLines(snapshot.renderedText))
                threadStatus?.let {
                    add("")
                    add(it.take(MAX_CARD_LINE_CHARS))
                }
            }
            ThreadMode.LISTENING -> buildList {
                add(speechStatus)
                if (speechPreview.isNotBlank()) {
                    add("")
                    addAll(RelayInboxCatalog.cardLines(speechPreview))
                }
            }
            ThreadMode.REVIEW -> reviewLines()
            ThreadMode.VOICE_FAILURE -> buildList {
                add(threadStatus ?: "Voice failed.")
                add("")
                addAll(choiceLines(visibleChoices().map(ReplyChoice::label)))
            }
            ThreadMode.SENT -> buildList {
                addAll(RelayInboxCatalog.cardLines(snapshot.renderedText))
                add("")
                add("Sent.")
            }
        }.takeLast(RelayInboxCatalog.MAX_CARD_LINES)

        val instruction = when (threadMode) {
            ThreadMode.READING -> if (ReplyRepository.contains(entry.id)) {
                "tap to reply · back to inbox"
            } else {
                "read only · back to inbox"
            }
            ThreadMode.LISTENING -> "speak now · back cancels"
            ThreadMode.REVIEW,
            ThreadMode.VOICE_FAILURE,
            -> "${visibleChoices().getOrNull(selectedChoice)?.label.orEmpty()} · scroll · tap"
            ThreadMode.SENT -> "back"
        }
        val footer = listOf(snapshot.appLabel.trim().take(MAX_FOOTER_SOURCE_CHARS), instruction)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .take(MAX_CARD_LINE_CHARS)
        sendCard(
            NexusCard(
                title = cardTitle(snapshot.sender.ifBlank { snapshot.appLabel }),
                lines = lines,
                footer = footer,
                contentKey = "$THREAD_CONTENT_PREFIX${entry.id}",
                handlesBack = true,
            ),
            show = show,
        )
    }

    private fun reviewLines(): List<String> = buildList {
        threadStatus?.let {
            add(it.take(MAX_CARD_LINE_CHARS))
            add("")
        }
        addAll(choiceLines(visibleChoices().map(ReplyChoice::label)))
        add("")
        addAll(RelayInboxCatalog.cardLines(transcript.orEmpty()))
    }

    private fun choiceLines(labels: List<String>): List<String> {
        var choiceIndex = 0
        return labels.map { label ->
            if (label.isBlank() || label !in ALL_CHOICE_LABELS) {
                label.take(MAX_CARD_LINE_CHARS)
            } else {
                val prefix = if (choiceIndex == selectedChoice) "> " else "  "
                choiceIndex += 1
                prefix + label
            }
        }
    }

    private fun startListening() {
        val entry = currentEntry() ?: return
        if (!ReplyRepository.contains(entry.id)) {
            threadStatus = "Reply is no longer available."
            threadMode = ThreadMode.READING
            renderThread(show = false)
            return
        }

        invalidateSpeech()
        threadMode = ThreadMode.LISTENING
        threadStatus = null
        transcript = null
        speechPreview = ""
        speechStatus = "Starting speech..."
        speechFinalReceived = false
        selectedChoice = 0
        renderThread(show = false)

        val generation = speechGeneration
        val newSpeech = nexusSpeechSession(object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) {
                if (!acceptSpeechCallback(generation)) return
                speechStatus = "Listening..."
                renderThread(show = false)
            }

            override fun onSpeechState(state: NexusSpeechState) {
                if (!acceptSpeechCallback(generation)) return
                speechStatus = when (state) {
                    NexusSpeechState.LISTENING -> "Listening..."
                    NexusSpeechState.RECOGNIZING -> "Recognizing..."
                    NexusSpeechState.PROCESSING -> "Transcribing..."
                }
                renderThread(show = false)
            }

            override fun onSpeechPartial(text: String) {
                if (!acceptSpeechCallback(generation) || text.isBlank()) return
                speechPreview = fitTranscript(text)
                renderThread(show = false)
            }

            override fun onSpeechFinal(text: String) {
                if (!acceptSpeechCallback(generation)) return
                val finalText = fitTranscript(text)
                if (finalText.isBlank()) {
                    showVoiceFailure("No speech")
                    return
                }
                speechFinalReceived = true
                transcript = finalText
                speechPreview = ""
                threadStatus = null
                threadMode = ThreadMode.REVIEW
                selectedChoice = 0
                renderThread(show = false)
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) {
                if (generation != speechGeneration) return
                speech = null
                if (speechFinalReceived && reason == NexusSpeechStopReason.COMPLETED) return
                showVoiceFailure(error?.kind?.takeIf(String::isNotBlank) ?: speechReasonLabel(reason))
            }
        }) ?: run {
            showVoiceFailure("Speech unavailable")
            return
        }
        speech = newSpeech
        val result = newSpeech.start()
        if (result != NexusSdkResult.SENT) {
            speech = null
            showVoiceFailure(speechStartFailure(result))
        }
    }

    private fun activateChoice() {
        when (visibleChoices().getOrNull(selectedChoice)) {
            ReplyChoice.SEND -> sendConfirmedReply()
            ReplyChoice.RETRY -> startListening()
            ReplyChoice.CANCEL -> {
                invalidateSpeech()
                resetThreadMode()
                renderThread(show = false)
            }
            null -> Unit
        }
    }

    private fun sendConfirmedReply() {
        val entry = currentEntry() ?: return
        val replyText = transcript
        if (replyText.isNullOrBlank()) {
            showVoiceFailure("Empty transcript")
            return
        }
        when (val result = ReplyRepository.sendReply(applicationContext, entry.id, replyText)) {
            ReplySendResult.Sent -> {
                transcript = null
                threadStatus = null
                threadMode = ThreadMode.SENT
            }
            ReplySendResult.Missing -> {
                transcript = null
                threadStatus = "Reply is no longer available."
                threadMode = ThreadMode.READING
            }
            ReplySendResult.Blank -> threadStatus = "Reply failed: empty reply"
            ReplySendResult.NoFreeFormInput -> threadStatus = "Reply failed: reply unavailable"
            is ReplySendResult.Failed -> threadStatus = "Reply failed: ${result.causeClass}"
        }
        renderThread(show = false)
    }

    private fun showVoiceFailure(reason: String) {
        transcript = null
        speechPreview = ""
        threadStatus = "Voice failed: ${reason.trim()}".take(MAX_CARD_LINE_CHARS)
        threadMode = ThreadMode.VOICE_FAILURE
        selectedChoice = 0
        renderThread(show = false)
    }

    private fun visibleChoices(): List<ReplyChoice> = when (threadMode) {
        ThreadMode.REVIEW -> REVIEW_CHOICES
        ThreadMode.VOICE_FAILURE -> FAILURE_CHOICES
        else -> emptyList()
    }

    private fun currentEntry(): RelayInboxEntry? {
        val id = selection.openedThreadId ?: return null
        return entries.firstOrNull { it.id == id }
    }

    private fun refreshEntries() {
        entries = ReplyRepository.inboxEntries()
        selection.replaceItems(entries.map(RelayInboxEntry::id))
    }

    private fun resetThreadMode() {
        threadMode = ThreadMode.READING
        threadStatus = null
        selectedChoice = 0
        transcript = null
        speechPreview = ""
        speechStatus = "Starting speech..."
        speechFinalReceived = false
    }

    private fun invalidateSpeech() {
        speechGeneration += 1
        speechFinalReceived = false
        val previous = speech
        speech = null
        previous?.stop()
    }

    private fun acceptSpeechCallback(generation: Int): Boolean =
        generation == speechGeneration && threadMode == ThreadMode.LISTENING

    private fun fitTranscript(value: String): String = NotificationTextExtractor.trimFromTop(
        value,
        NoticeSurfaceContract.MAX_BODY_CHARS,
    )

    private fun cardTitle(value: String): String = value.trim().ifBlank { "Relay" }.take(MAX_CARD_TITLE_CHARS)

    private fun sendCard(card: NexusCard, show: Boolean) {
        val session = surface ?: return
        if (show) session.showCard(card) else session.updateCard(card)
    }

    private fun speechStartFailure(result: NexusSdkResult): String = when (result) {
        NexusSdkResult.CAPABILITY_NOT_GRANTED -> "Grant Speech to text in Nexus settings"
        NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> "Speech unavailable"
        NexusSdkResult.NOT_REGISTERED -> "Nexus connection unavailable"
        else -> result.name
    }

    private fun speechReasonLabel(reason: NexusSpeechStopReason): String = when (reason) {
        NexusSpeechStopReason.COMPLETED -> "No transcript"
        NexusSpeechStopReason.CANCELLED -> "Cancelled"
        NexusSpeechStopReason.NO_SPEECH -> "No speech"
        NexusSpeechStopReason.ERROR -> "Recognition error"
        NexusSpeechStopReason.LINK_LOST -> "Link lost"
        NexusSpeechStopReason.REVOKED -> "Permission revoked"
        NexusSpeechStopReason.DENIED_BUSY -> "Speech busy"
        NexusSpeechStopReason.DENIED_NO_LINK -> "No glasses link"
        NexusSpeechStopReason.DENIED_NOT_READY -> "Set up speech in Nexus"
        NexusSpeechStopReason.DENIED_START_FAILED -> "Start failed"
        NexusSpeechStopReason.DENIED_INVALID -> "Invalid request"
    }

    private companion object {
        const val SURFACE_ID = "relay-inbox"
        const val LIST_CONTENT_KEY = "relay-inbox-v1"
        const val THREAD_CONTENT_PREFIX = "relay-thread-"
        const val MAX_CARD_TITLE_CHARS = 120
        const val MAX_CARD_LINE_CHARS = 240
        const val MAX_FOOTER_SOURCE_CHARS = 80

        val REVIEW_CHOICES = listOf(ReplyChoice.SEND, ReplyChoice.RETRY, ReplyChoice.CANCEL)
        val FAILURE_CHOICES = listOf(ReplyChoice.RETRY, ReplyChoice.CANCEL)
        val ALL_CHOICE_LABELS = ReplyChoice.entries.map(ReplyChoice::label).toSet()
    }
}
