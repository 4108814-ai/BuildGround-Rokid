package com.anezium.rokidbus.plugin.agents

import org.json.JSONArray
import org.json.JSONObject

sealed interface AgentdAction {
    data class HelloAcknowledged(val machineName: String?) : AgentdAction
    data class Snapshot(val seq: Long, val sessions: List<AgentSession>) : AgentdAction
    data class Upsert(val seq: Long, val session: AgentSession) : AgentdAction
    data class Removed(val seq: Long, val sessionId: String) : AgentdAction
    data class Detail(val sessionId: String, val messages: List<AgentMessage>) : AgentdAction
    data class DetailAppend(val sessionId: String, val message: AgentMessage) : AgentdAction
    data class Send(val text: String) : AgentdAction
    data object Ignore : AgentdAction
}

class AgentdProtocolCodec {
    private var lastSeq: Long? = null
    private var awaitingSnapshot = true
    private var refreshRequested = false

    @Synchronized
    fun reset() {
        lastSeq = null
        awaitingSnapshot = true
        refreshRequested = false
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
        return when (json.optString("type")) {
            "hello_ack" -> {
                if (json.optInt("v", -1) != 1) return AgentdAction.Ignore
                AgentdAction.HelloAcknowledged(
                    json.optJSONObject("server")?.nullableString("machineName"),
                )
            }
            "snapshot" -> {
                val seq = json.longOrNull("seq") ?: return AgentdAction.Ignore
                val sessions = json.optJSONArray("sessions").toSessions()
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
                AgentdAction.Upsert(seq, session)
            }
            "session_removed" -> {
                val seq = json.longOrNull("seq") ?: return AgentdAction.Ignore
                if (!acceptDelta(seq)) return gapAction()
                val sessionId = json.nullableString("sessionId") ?: return AgentdAction.Ignore
                lastSeq = seq
                AgentdAction.Removed(seq, sessionId)
            }
            // Conversation payloads carry no seq: they are a reply to the
            // wearer opening a session, never part of the session stream.
            "detail" -> {
                val sessionId = json.nullableString("sessionId") ?: return AgentdAction.Ignore
                AgentdAction.Detail(sessionId, json.optJSONArray("messages").toMessages())
            }
            "detail_append" -> {
                val sessionId = json.nullableString("sessionId") ?: return AgentdAction.Ignore
                val message = json.optJSONObject("message")?.toAgentMessage()
                    ?: return AgentdAction.Ignore
                AgentdAction.DetailAppend(sessionId, message)
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
    }
}

private fun JSONArray?.toMessages(): List<AgentMessage> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.toAgentMessage()?.let(::add)
        }
    }
}

private fun JSONObject.toAgentMessage(): AgentMessage? {
    val text = nullableString("text") ?: return null
    val role = nullableString("role")?.let { raw ->
        MessageRole.values().firstOrNull { it.wireValue == raw }
    } ?: return null
    return AgentMessage(
        role = role,
        text = text,
        at = longOrNull("at"),
        tool = nullableString("tool"),
    )
}

private fun JSONArray?.toSessions(): List<AgentSession> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.toAgentSession()?.let(::add)
        }
    }
}

private fun JSONObject.toAgentSession(): AgentSession? {
    val id = nullableString("id") ?: return null
    if (nullableString("provider") != AgentProvider.CLAUDE.wireValue) return null
    val wireStatus = nullableString("status") ?: return null
    val status = AgentStatus.values().firstOrNull { it.wireValue == wireStatus } ?: AgentStatus.IDLE
    val turnJson = optJSONObject("turn")
    val pendingJson = optJSONObject("pendingRequest")
    val pendingKind = pendingJson?.nullableString("kind")?.let { raw ->
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
        machineId = nullableString("machineId"),
        machineName = nullableString("machineName"),
        title = nullableString("title"),
        cwd = nullableString("cwd"),
        project = nullableString("project"),
        status = status,
        statusDetail = nullableString("statusDetail") ?: unknownStatusDetail,
        stale = booleanOrNull("stale") ?: false,
        lastActivityAt = longOrNull("lastActivityAt"),
        lastAssistantText = nullableString("lastAssistantText"),
        turn = turnJson?.let {
            AgentTurn(
                lastTool = it.nullableString("lastTool"),
                activeSince = it.longOrNull("activeSince"),
            )
        },
        pendingRequest = if (pendingJson != null && pendingKind != null) {
            AgentPendingRequest(
                kind = pendingKind,
                summary = pendingJson.nullableString("summary"),
                createdAt = pendingJson.longOrNull("createdAt"),
            )
        } else {
            null
        },
    )
}

internal fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)

internal fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else runCatching { getLong(key) }.getOrNull()

internal fun JSONObject.booleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else runCatching { getBoolean(key) }.getOrNull()
