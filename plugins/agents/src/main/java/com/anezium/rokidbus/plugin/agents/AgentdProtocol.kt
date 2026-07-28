package com.anezium.rokidbus.plugin.agents

import org.json.JSONArray
import org.json.JSONObject

sealed interface AgentdAction {
    /** A computer dialling in on the LAN link, before anything else is sent. */
    data class Hello(
        val machineId: String,
        val machineName: String,
        val token: String,
    ) : AgentdAction
    data class HelloAcknowledged(val machineName: String?) : AgentdAction
    data class Snapshot(val seq: Long, val sessions: List<AgentSession>) : AgentdAction
    data class Upsert(val seq: Long, val session: AgentSession) : AgentdAction
    data class Removed(val seq: Long, val sessionId: String) : AgentdAction
    data class Detail(val sessionId: String, val messages: List<AgentMessage>) : AgentdAction
    data class DetailAppend(val sessionId: String, val message: AgentMessage) : AgentdAction
    data class ApprovalRequested(val approval: AgentApproval) : AgentdAction
    data class ApprovalResolved(val requestId: String) : AgentdAction
    data class Send(val text: String) : AgentdAction
    data object Ignore : AgentdAction
}

class AgentdProtocolCodec {
    private var lastSeq: Long? = null
    private var awaitingSnapshot = true
    private var refreshRequested = false
    private val sessionIds = linkedSetOf<String>()
    private val detailMessageCounts = object : LinkedHashMap<String, Int>(
        MAX_TRACKED_DETAIL_SESSIONS + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > MAX_TRACKED_DETAIL_SESSIONS
    }

    @Synchronized
    fun reset() {
        lastSeq = null
        awaitingSnapshot = true
        refreshRequested = false
        sessionIds.clear()
        detailMessageCounts.clear()
    }

    fun hello(token: String, versionName: String): String = JSONObject()
        .put("type", "hello")
        .put("v", 1)
        .put("token", token)
        .put(
            "client",
            JSONObject()
                .put("name", "plugin-agents")
                .put("version", versionName),
        )
        .toString()

    @Synchronized
    fun parse(text: String): AgentdAction {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return AgentdAction.Ignore
        return when (json.nullableString("type", MAX_WIRE_TYPE_CHARS)) {
            "hello" -> {
                if (json.intOrNull("v") != 1) return AgentdAction.Ignore
                val machineId = json.identifierOrNull("machineId") ?: return AgentdAction.Ignore
                val token = json.identifierOrNull("token", MAX_AUTH_TOKEN_CHARS)
                    ?: return AgentdAction.Ignore
                AgentdAction.Hello(
                    machineId = machineId,
                    machineName = json.nullableString("machineName", MAX_HUD_LABEL_CHARS)
                        ?: machineId.take(8),
                    token = token,
                )
            }
            "hello_ack" -> {
                if (json.intOrNull("v") != 1) return AgentdAction.Ignore
                AgentdAction.HelloAcknowledged(
                    json.optJSONObject("server")
                        ?.nullableString("machineName", MAX_HUD_LABEL_CHARS),
                )
            }
            "snapshot" -> {
                val seq = json.longOrNull("seq") ?: return AgentdAction.Ignore
                val sessionsJson = json.opt("sessions") as? JSONArray ?: return gapAction()
                val sessions = sessionsJson.toSessions()
                sessionIds.clear()
                sessions.mapTo(sessionIds, AgentSession::id)
                lastSeq = seq
                awaitingSnapshot = false
                refreshRequested = false
                AgentdAction.Snapshot(seq, sessions)
            }
            "session_upsert" -> {
                val seq = json.longOrNull("seq") ?: return AgentdAction.Ignore
                if (!acceptDelta(seq)) return gapAction()
                val session = json.optJSONObject("session")?.toAgentSession()
                    ?: return AgentdAction.Ignore
                lastSeq = seq
                if (session.id !in sessionIds && sessionIds.size >= MAX_SESSIONS_PER_PROVIDER) {
                    return AgentdAction.Ignore
                }
                sessionIds += session.id
                AgentdAction.Upsert(seq, session)
            }
            "session_removed" -> {
                val seq = json.longOrNull("seq") ?: return AgentdAction.Ignore
                if (!acceptDelta(seq)) return gapAction()
                val sessionId = json.identifierOrNull("sessionId") ?: return AgentdAction.Ignore
                lastSeq = seq
                sessionIds -= sessionId
                detailMessageCounts.remove(sessionId)
                AgentdAction.Removed(seq, sessionId)
            }
            // Conversation payloads carry no seq: they are a reply to the
            // wearer opening a session, never part of the session stream.
            "detail" -> {
                val sessionId = json.identifierOrNull("sessionId")
                    ?: return AgentdAction.Ignore
                if (sessionId !in sessionIds) return AgentdAction.Ignore
                val messagesJson = json.opt("messages") as? JSONArray
                    ?: return AgentdAction.Ignore
                val messages = messagesJson.toMessages()
                detailMessageCounts[sessionId] = messages.size
                AgentdAction.Detail(sessionId, messages)
            }
            "detail_append" -> {
                val sessionId = json.identifierOrNull("sessionId")
                    ?: return AgentdAction.Ignore
                if (sessionId !in sessionIds) return AgentdAction.Ignore
                val messageCount = detailMessageCounts[sessionId]
                    ?: return AgentdAction.Ignore
                if (messageCount >= MAX_DETAIL_MESSAGES) {
                    return AgentdAction.Ignore
                }
                val message = json.optJSONObject("message")?.toAgentMessage()
                    ?: return AgentdAction.Ignore
                detailMessageCounts[sessionId] = messageCount + 1
                AgentdAction.DetailAppend(sessionId, message)
            }
            // Approvals carry no seq either: a held tool call is answered by its
            // own id, and must survive a gap in the session stream.
            "approval_request" -> {
                if (json.intOrNull("v") != 1) return AgentdAction.Ignore
                val requestId = json.identifierOrNull("requestId") ?: return AgentdAction.Ignore
                val sessionId = json.identifierOrNull("sessionId") ?: return AgentdAction.Ignore
                val summary = json.nullableString("summary", MAX_HUD_LABEL_CHARS)
                    ?: return AgentdAction.Ignore
                AgentdAction.ApprovalRequested(
                    AgentApproval(
                        requestId = requestId,
                        sessionId = sessionId,
                        provider = AgentProvider.CLAUDE,
                        tool = json.nullableString("tool", MAX_WIRE_TYPE_CHARS).orEmpty(),
                        summary = summary,
                        detail = json.nullableString("detail", MAX_HUD_TEXT_CHARS),
                        createdAt = json.longOrNull("createdAt"),
                    ),
                )
            }
            "approval_resolved" -> {
                if (json.intOrNull("v") != 1) return AgentdAction.Ignore
                val requestId = json.identifierOrNull("requestId") ?: return AgentdAction.Ignore
                AgentdAction.ApprovalResolved(requestId)
            }
            "ping" -> AgentdAction.Send(
                JSONObject()
                    .put("type", "pong")
                    .put("t", json.opt("t"))
                    .toString(),
            )
            else -> AgentdAction.Ignore
        }
    }

    private fun acceptDelta(seq: Long): Boolean =
        !awaitingSnapshot && lastSeq?.let { seq == it + 1L } == true

    private fun gapAction(): AgentdAction {
        if (!awaitingSnapshot) {
            awaitingSnapshot = true
        }
        return if (!refreshRequested) {
            refreshRequested = true
            AgentdAction.Send(REFRESH)
        } else {
            AgentdAction.Ignore
        }
    }

    companion object {
        const val REFRESH = """{"type":"refresh"}"""
        const val DETAIL_CLOSE = """{"type":"detail_close"}"""

        fun detailOpen(sessionId: String): String = JSONObject()
            .put("type", "detail_open")
            .put("sessionId", sessionId)
            .toString()

        /** The wearer answered a held tool call. */
        fun approvalDecision(requestId: String, decision: ApprovalDecision): String = JSONObject()
            .put("type", "approval_decision")
            .put("v", 1)
            .put("requestId", requestId)
            .put("decision", decision.wireValue)
            .toString()

        /**
         * Said out loud before hanging up on a computer we will not talk to.
         * Closing in silence leaves the operator watching a reconnect loop with
         * no way to know the phone is refusing them on purpose.
         */
        fun helloReject(reason: String): String = JSONObject()
            .put("type", "hello_reject")
            .put("v", 1)
            .put("reason", reason)
            .toString()

        const val REJECT_UNKNOWN_MACHINE = "unknown_machine"
        const val REJECT_BAD_TOKEN = "bad_token"
    }
}

private fun JSONArray?.toMessages(): List<AgentMessage> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until minOf(length(), MAX_DETAIL_MESSAGES)) {
            optJSONObject(index)?.toAgentMessage()?.let(::add)
        }
    }
}

private fun JSONObject.toAgentMessage(): AgentMessage? {
    val text = nullableString("text", MAX_HUD_TEXT_CHARS) ?: return null
    val role = nullableString("role", MAX_WIRE_TYPE_CHARS)?.let { raw ->
        MessageRole.values().firstOrNull { it.wireValue == raw }
    } ?: return null
    return AgentMessage(
        role = role,
        text = text,
        at = longOrNull("at"),
        tool = nullableString("tool", MAX_HUD_LABEL_CHARS),
    )
}

private fun JSONArray?.toSessions(): List<AgentSession> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until minOf(length(), MAX_SESSIONS_PER_PROVIDER)) {
            optJSONObject(index)?.toAgentSession()?.let(::add)
        }
    }
}

private fun JSONObject.toAgentSession(): AgentSession? {
    val id = identifierOrNull("id") ?: return null
    if (nullableString("provider", MAX_WIRE_TYPE_CHARS) != AgentProvider.CLAUDE.wireValue) {
        return null
    }
    val wireStatus = nullableString("status", MAX_WIRE_TYPE_CHARS) ?: return null
    val status = AgentStatus.values().firstOrNull { it.wireValue == wireStatus } ?: AgentStatus.IDLE
    val turnJson = optJSONObject("turn")
    val pendingJson = optJSONObject("pendingRequest")
    val pendingKind = pendingJson?.nullableString("kind", MAX_WIRE_TYPE_CHARS)?.let { raw ->
        PendingRequestKind.values().firstOrNull { it.wireValue == raw }
    }
    val unknownStatusDetail = if (AgentStatus.values().none { it.wireValue == wireStatus }) {
        "Unknown daemon status: $wireStatus"
    } else {
        null
    }
    return AgentSession(
        id = id,
        provider = AgentProvider.CLAUDE,
        machineId = identifierOrNull("machineId"),
        machineName = nullableString("machineName", MAX_HUD_LABEL_CHARS),
        title = nullableString("title", MAX_HUD_LABEL_CHARS),
        cwd = nullableString("cwd", MAX_PATH_CHARS),
        project = nullableString("project", MAX_HUD_LABEL_CHARS),
        status = status,
        statusDetail = nullableString("statusDetail", MAX_STATUS_DETAIL_CHARS)
            ?: unknownStatusDetail,
        stale = booleanOrNull("stale") ?: false,
        lastActivityAt = longOrNull("lastActivityAt"),
        lastAssistantText = nullableString("lastAssistantText", MAX_HUD_TEXT_CHARS),
        turn = turnJson?.let {
            AgentTurn(
                lastTool = it.nullableString("lastTool", MAX_HUD_LABEL_CHARS),
                activeSince = it.longOrNull("activeSince"),
            )
        },
        pendingRequest = if (pendingJson != null && pendingKind != null) {
            AgentPendingRequest(
                kind = pendingKind,
                summary = pendingJson.nullableString("summary", MAX_HUD_LABEL_CHARS),
                createdAt = pendingJson.longOrNull("createdAt"),
            )
        } else {
            null
        },
    )
}

internal const val MAX_SESSIONS_PER_PROVIDER = 200
internal const val MAX_PENDING_APPROVALS = 200
internal const val MAX_DETAIL_MESSAGES = 200
internal const val MAX_TRACKED_DETAIL_SESSIONS = 200
internal const val MAX_IDENTIFIER_CHARS = 256
internal const val MAX_HOST_CHARS = 512
internal const val MAX_AUTH_TOKEN_CHARS = 4_096
internal const val MAX_WIRE_TYPE_CHARS = 64
internal const val MAX_HUD_LABEL_CHARS = 160
internal const val MAX_STATUS_DETAIL_CHARS = 512
internal const val MAX_PATH_CHARS = 1_024
internal const val MAX_HUD_TEXT_CHARS = 4_096

internal fun JSONObject.nullableString(
    key: String,
    maxChars: Int = MAX_HUD_TEXT_CHARS,
): String? {
    val value = opt(key)
    if (value !is String) return null
    return value.trim().take(maxChars).takeIf(String::isNotEmpty)
}

internal fun JSONObject.identifierOrNull(
    key: String,
    maxChars: Int = MAX_IDENTIFIER_CHARS,
): String? {
    val value = opt(key)
    if (value !is String) return null
    val trimmed = value.trim()
    return trimmed.takeIf { it.isNotEmpty() && it.length <= maxChars }
}

internal fun JSONObject.longOrNull(key: String): Long? =
    when (val value = opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

internal fun JSONObject.intOrNull(key: String): Int? =
    when (val value = opt(key)) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

internal fun JSONObject.booleanOrNull(key: String): Boolean? =
    opt(key) as? Boolean
