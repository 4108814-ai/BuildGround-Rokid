package com.anezium.rokidbus.plugin.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

internal const val LIST_NOTIFICATIONS_TOOL_NAME = "list_recent_notifications"
internal const val REPLY_NOTIFICATION_TOOL_NAME = "reply_to_notification"

class AssistantNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        AssistantNotificationBridge.onPosted(applicationContext, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        AssistantNotificationBridge.onRemoved(sbn.key)
    }
}

internal data class AssistantNotificationSnapshot(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val canReply: Boolean,
)

private data class LiveNotification(
    val snapshot: AssistantNotificationSnapshot,
    val replyAction: Notification.Action?,
)

internal object AssistantNotificationBridge {
    private val live = LinkedHashMap<String, LiveNotification>()

    @Synchronized
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString().orEmpty().trim()
        val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()?.trim().takeUnless(String?::isNullOrBlank)
            ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return

        val replyAction = notification.actions
            ?.firstOrNull { action ->
                action.actionIntent != null &&
                    action.remoteInputs?.any { input -> input.allowFreeFormInput } == true
            }
        val appName = runCatching {
            val info = context.packageManager.getApplicationInfo(sbn.packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)

        live.remove(sbn.key)
        live[sbn.key] = LiveNotification(
            snapshot = AssistantNotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                canReply = replyAction != null,
            ),
            replyAction = replyAction,
        )
        while (live.size > MAX_LIVE_NOTIFICATIONS) {
            val oldest = live.entries.firstOrNull()?.key ?: break
            live.remove(oldest)
        }
    }

    @Synchronized
    fun onRemoved(key: String) {
        live.remove(key)
    }

    @Synchronized
    fun snapshots(limit: Int): List<AssistantNotificationSnapshot> =
        live.values.asSequence()
            .map(LiveNotification::snapshot)
            .sortedByDescending(AssistantNotificationSnapshot::postTime)
            .take(limit.coerceIn(1, MAX_TOOL_NOTIFICATIONS))
            .toList()

    @Synchronized
    fun reply(context: Context, key: String, message: String): Boolean {
        val entry = live[key] ?: return false
        val action = entry.replyAction ?: return false
        val remoteInputs = action.remoteInputs ?: return false
        val freeFormInputs = remoteInputs.filter(RemoteInput::getAllowFreeFormInput)
        if (freeFormInputs.isEmpty()) return false
        return runCatching {
            val intent = Intent()
            val results = Bundle()
            freeFormInputs.forEach { input ->
                results.putCharSequence(input.resultKey, message)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        }.getOrDefault(false)
    }

    private const val MAX_LIVE_NOTIFICATIONS = 50
    private const val MAX_TOOL_NOTIFICATIONS = 20
}

internal fun hasAssistantNotificationAccess(context: Context): Boolean {
    val component = ComponentName(context, AssistantNotificationListenerService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ).orEmpty()
    return enabled.split(':').any { value ->
        ComponentName.unflattenFromString(value)?.let { it == component } == true
    }
}

internal fun assistantNotificationTools(context: Context): List<AssistantToolDefinition> = listOf(
    ListRecentNotificationsTool(context.applicationContext),
    ReplyNotificationTool(context.applicationContext),
)

internal class ListRecentNotificationsTool(
    private val context: Context,
) : TextAssistantTool() {
    override val name = LIST_NOTIFICATIONS_TOOL_NAME
    override val description =
        "Read recent live phone notifications captured by Assistant. Use this when the user asks " +
            "what just arrived, asks to explain the latest notification, asks whether a notification " +
            "is important, or needs notification context before replying. The model must judge " +
            "importance from the returned content and must not invent missing details."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"limit":{"type":["integer","null"],"minimum":1,"maximum":20,"default":5}},"required":["limit"],"additionalProperties":false}""",
    )
    override val sideEffecting = false
    override val progressLabel = "Checking notifications…"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val parsed = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val keys = parsed.keys()
        while (keys.hasNext()) {
            if (keys.next() != "limit") return AssistantToolValidation.Invalid()
        }
        val limit = if (!parsed.has("limit") || parsed.isNull("limit")) 5 else parsed.optInt("limit", -1)
        if (limit !in 1..20) return AssistantToolValidation.Invalid()
        return AssistantToolValidation.Valid(JSONObject().put("limit", limit))
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.Default) {
        if (!hasAssistantNotificationAccess(context)) {
            return@withContext AssistantToolResult.Error(
                code = "notification_access_required",
                detailsJson = JSONObject()
                    .put(
                        "message",
                        "Notification access is off. Open Assistant settings and grant Notification access.",
                    )
                    .toString(),
            )
        }
        val values = AssistantNotificationBridge.snapshots(arguments.getInt("limit"))
        AssistantToolResult.Json(
            JSONObject()
                .put(
                    "notifications",
                    JSONArray().apply {
                        values.forEach { item ->
                            put(
                                JSONObject()
                                    .put("key", item.key)
                                    .put("app", item.appName)
                                    .put("package", item.packageName)
                                    .put("title", item.title)
                                    .put("text", item.text)
                                    .put("post_time_ms", item.postTime)
                                    .put("can_reply", item.canReply),
                            )
                        }
                    },
                )
                .toString(),
        )
    }
}

internal class ReplyNotificationTool(
    private val context: Context,
) : TextAssistantTool() {
    override val name = REPLY_NOTIFICATION_TOOL_NAME
    override val description =
        "Reply through a notification's Android RemoteInput action. Use only when the user " +
            "explicitly asks to reply/send an answer to a notification. First use " +
            "list_recent_notifications if the exact notification key is not known. Never claim " +
            "a reply was sent unless this tool returns sent=true."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"key":{"type":"string","minLength":1},"message":{"type":"string","minLength":1}},"required":["key","message"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val progressLabel = "Replying…"
    override val retiresProgressOnSuccess = true

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val parsed = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val keys = parsed.keys()
        while (keys.hasNext()) {
            if (keys.next() !in setOf("key", "message")) return AssistantToolValidation.Invalid()
        }
        val key = parsed.optString("key").trim()
        val message = parsed.optString("message").trim()
        if (key.isBlank() || message.isBlank()) return AssistantToolValidation.Invalid()
        return AssistantToolValidation.Valid(
            JSONObject().put("key", key).put("message", message),
        )
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.Main) {
        if (!hasAssistantNotificationAccess(context)) {
            return@withContext AssistantToolResult.Error("notification_access_required")
        }
        val sent = AssistantNotificationBridge.reply(
            context = context,
            key = arguments.getString("key"),
            message = arguments.getString("message"),
        )
        if (!sent) return@withContext AssistantToolResult.Error("notification_reply_failed")
        AssistantToolResult.Json(JSONObject().put("sent", true).toString())
    }
}
