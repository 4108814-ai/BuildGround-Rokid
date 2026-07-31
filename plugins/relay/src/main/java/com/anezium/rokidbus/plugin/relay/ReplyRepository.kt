package com.anezium.rokidbus.plugin.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal data class VisibleNotificationContent(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val renderedText: String,
    val revision: String,
    val imageId: String?,
)

internal fun VisibleNotificationContent.hasSameVisibleContent(other: VisibleNotificationContent): Boolean =
    packageName == other.packageName &&
        appLabel == other.appLabel &&
        title == other.title &&
        renderedText == other.renderedText &&
        revision == other.revision &&
        imageId == other.imageId

internal sealed interface ReplySendResult {
    data object Sent : ReplySendResult
    data object Missing : ReplySendResult
    data object Blank : ReplySendResult
    data object NoFreeFormInput : ReplySendResult
    data class Failed(val causeClass: String) : ReplySendResult
}

internal object ReplyRepository {
    private data class ReadableReply(
        val snapshot: RelayInboxSnapshot,
        val content: VisibleNotificationContent,
    )

    data class CaptureResult(
        val reply: PendingReply,
        val shouldShowNow: Boolean,
    )

    data class PendingReply(
        val id: String,
        val notificationKey: String,
        val content: VisibleNotificationContent,
        val footer: String,
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val capturedAtMs: Long,
        val imagePreview: NotificationImagePreview?,
    )

    private val pending = linkedMapOf<String, PendingReply>()
    private val recent = linkedMapOf<String, ReadableReply>()
    private val lastCaptureAtMs = AtomicLong(0L)

    @Synchronized
    fun capture(
        context: Context,
        sbn: StatusBarNotification,
        action: Notification.Action,
    ): CaptureResult? {
        val remoteInputs = action.remoteInputs ?: return null
        if (remoteInputs.isEmpty()) return null

        val settings = RelaySettings(context)
        val extras = sbn.notification.extras
        val appLabel = appLabel(context, sbn.packageName)
        val sourceTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val displayTitle = (sourceTitle.ifBlank { appLabel })
            .take(NoticeSurfaceContract.MAX_TITLE_CHARS)
        val renderedText = NotificationTextExtractor.extract(
            NotificationTextExtractor.fromExtras(extras),
            settings.messagesPerThread(),
        )
        val footer = appLabel.trim().take(NoticeSurfaceContract.MAX_FOOTER_CHARS)
        val imagePreview = if (settings.imagePreviewsEnabled()) {
            NotificationImageExtractor.extract(context, sbn.notification)
        } else {
            null
        }
        val revision = notificationRevision(sbn, extras, imagePreview)
        if (hasRemoteInputHistory(extras) && settings.clearAfterReply()) {
            NotificationControl.cancelAfterReply(sbn.key)
        }

        val id = stableId(sbn.key)
        val content = VisibleNotificationContent(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = displayTitle,
            renderedText = renderedText,
            revision = revision,
            imageId = imagePreview?.id,
        )
        val previous = pending[id]
        val previousContent = previous?.content ?: recent[id]?.content
        val contentChanged = previousContent == null || !previousContent.hasSameVisibleContent(content)
        val capturedAtMs = if (contentChanged) {
            nextCaptureAtMs()
        } else {
            previous?.capturedAtMs ?: recent.getValue(id).snapshot.capturedAtMs
        }
        val reply = PendingReply(
            id = id,
            notificationKey = sbn.key,
            content = content,
            footer = footer,
            actionIntent = action.actionIntent,
            remoteInputs = remoteInputs,
            capturedAtMs = capturedAtMs,
            imagePreview = imagePreview,
        )
        pending[id] = reply
        recent[id] = ReadableReply(
            snapshot = RelayInboxSnapshot(
                id = id,
                sender = content.title,
                appLabel = content.appLabel,
                renderedText = content.renderedText,
                capturedAtMs = capturedAtMs,
            ),
            content = content,
        )
        trimRecent()
        return CaptureResult(
            reply = reply,
            shouldShowNow = contentChanged && isMostRecent(id),
        )
    }

    fun sendReply(context: Context, notificationId: String, text: String): ReplySendResult {
        val reply = synchronized(this) { pending[notificationId] } ?: return ReplySendResult.Missing
        if (text.isBlank()) return ReplySendResult.Blank
        val intent = Intent()
        val results = Bundle()
        reply.remoteInputs.forEach { input ->
            if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, text)
        }
        if (results.isEmpty) return ReplySendResult.NoFreeFormInput

        RemoteInput.addResultsToIntent(reply.remoteInputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        val failure = runCatching {
            reply.actionIntent.send(context, 0, intent)
        }.exceptionOrNull()
        if (failure != null) {
            return ReplySendResult.Failed(failure.javaClass.simpleName.ifBlank { "SendException" })
        }

        if (RelaySettings(context).clearAfterReply()) {
            NotificationControl.cancelAfterReply(reply.notificationKey)
        }
        // Answered, so it leaves the inbox entirely — the readable copy as well
        // as the live reply objects. A conversation the wearer has replied to is
        // no longer waiting on them, and keeping it as a read-only row means the
        // list slowly fills with finished business.
        synchronized(this) {
            if (pending[notificationId] === reply) pending.remove(notificationId)
            recent.remove(notificationId)
        }
        return ReplySendResult.Sent
    }

    @Synchronized
    fun forget(sbn: StatusBarNotification) {
        pending.remove(stableId(sbn.key))
    }

    @Synchronized
    fun inboxEntries(): List<RelayInboxEntry> = RelayInboxCatalog.entries(
        snapshots = recent.values.map(ReadableReply::snapshot),
        liveReplyIds = pending.keys.toSet(),
    )

    /** Keeps readable text in memory while dropping every process-bound reply object. */
    @Synchronized
    fun markAllReadOnly() {
        pending.clear()
    }

    @Synchronized
    fun clear() {
        pending.clear()
        recent.clear()
    }

    @Synchronized
    fun contains(notificationId: String): Boolean = notificationId in pending

    @Synchronized
    private fun isMostRecent(notificationId: String): Boolean =
        pending.values.maxByOrNull(PendingReply::capturedAtMs)?.id == notificationId

    private fun trimRecent() {
        while (recent.size > RelayInboxCatalog.MAX_ENTRIES) {
            val oldestId = recent.values
                .minWithOrNull(
                    compareBy<ReadableReply> { it.snapshot.capturedAtMs }
                        .thenBy { it.snapshot.id },
                )
                ?.snapshot
                ?.id
                ?: return
            recent.remove(oldestId)
        }
    }

    private fun notificationRevision(
        sbn: StatusBarNotification,
        extras: Bundle,
        imagePreview: NotificationImagePreview?,
    ): String {
        val imageRevision = imagePreview?.let { preview ->
            ":img:${preview.id}:${preview.width}x${preview.height}:${preview.bytes.size}"
        } ?: ":img:none"
        val messages = NotificationTextExtractor.messagingStyleMessages(extras)
        if (messages.isNotEmpty()) {
            return "msg:${messages.size}:${messages.first().timestamp}:${messages.last().timestamp}$imageRevision"
        }
        return "plain:${sbn.notification.`when`}$imageRevision"
    }

    private fun hasRemoteInputHistory(extras: Bundle): Boolean =
        extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            ?.any { !it.isNullOrBlank() } == true

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun nextCaptureAtMs(): Long {
        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastCaptureAtMs.get()
            val next = if (now > previous) now else previous + 1L
            if (lastCaptureAtMs.compareAndSet(previous, next)) return next
        }
    }

    fun stableId(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .take(10)
            .joinToString("") { byte -> "%02x".format(byte) }
}
