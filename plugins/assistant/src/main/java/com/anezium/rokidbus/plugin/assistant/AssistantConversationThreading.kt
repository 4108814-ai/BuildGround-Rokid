package com.anezium.rokidbus.plugin.assistant

import java.util.Base64

internal data class AssistantConversationContext(
    val threadId: String?,
    val history: List<ChatMessage>,
    val keepConversation: Boolean,
)

internal class AssistantConversationThreading(
    private val threadStore: AssistantThreadStore,
) {
    fun prepare(
        keepConversation: Boolean,
        idleWindowMinutes: Int,
    ): AssistantConversationContext {
        if (!keepConversation) {
            return AssistantConversationContext(
                threadId = null,
                history = emptyList(),
                keepConversation = false,
            )
        }
        val thread = threadStore.activeThreadOrNull(idleWindowMinutes)
        val messages = thread?.messages.orEmpty()
        val replayedPhotos = buildMap {
            for (messageIndex in messages.indices.reversed()) {
                if (size >= MAX_REPLAYED_THREAD_PHOTOS) break
                val photoPath = messages[messageIndex].photoPath ?: continue
                val bytes = threadStore.photoBytes(photoPath)
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue
                put(
                    messageIndex,
                    PhotoAttachment(
                        mimeType = "image/jpeg",
                        base64 = Base64.getEncoder().encodeToString(bytes),
                    ),
                )
            }
        }
        return AssistantConversationContext(
            // Allocate the id before the first successful turn so an upstream server can use
            // the same conversation scope for that first request and every follow-up.
            threadId = thread?.id ?: threadStore.newThreadId(),
            history = messages.mapIndexed { messageIndex, message ->
                message.toHistoryMessage(replayedPhotos[messageIndex])
            },
            keepConversation = true,
        )
    }

    fun recordCompletedTurn(
        context: AssistantConversationContext,
        userText: String,
        assistantText: String?,
        hadPhoto: Boolean,
        photoJpeg: ByteArray? = null,
    ) {
        val completedAnswer = assistantText?.takeIf(String::isNotBlank) ?: return
        if (!context.keepConversation) return
        threadStore.appendTurn(
            threadId = context.threadId,
            userText = userText,
            assistantText = completedAnswer,
            hadPhoto = hadPhoto,
            photoJpeg = photoJpeg,
        )
    }

    private fun AssistantThreadMessage.toHistoryMessage(
        replayedPhoto: PhotoAttachment?,
    ): ChatMessage = ChatMessage(
        role = role,
        content = text,
        photos = when {
            replayedPhoto != null -> listOf(replayedPhoto)
            hadPhoto -> listOf(OMITTED_HISTORY_PHOTO)
            else -> emptyList()
        },
    )

    private companion object {
        val OMITTED_HISTORY_PHOTO = PhotoAttachment(
            mimeType = OMITTED_HISTORY_PHOTO_MIME_TYPE,
            base64 = OMITTED_HISTORY_PHOTO_BASE64,
        )
    }
}

internal const val MAX_REPLAYED_THREAD_PHOTOS = 2
internal const val OMITTED_HISTORY_PHOTO_MIME_TYPE = "image/x-assistant-history-marker"
internal const val OMITTED_HISTORY_PHOTO_BASE64 = "omitted"
