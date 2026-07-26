package com.anezium.rokidbus.plugin.agents

enum class AgentProvider(
    val wireValue: String,
    val marker: String,
) {
    CLAUDE("claude", "CC"),
    OPENCLAW("openclaw", "OC"),
}

enum class AgentStatus(val wireValue: String) {
    WORKING("working"),
    NEEDS_YOU("needs_you"),
    IDLE("idle"),
    DONE("done"),
    ERROR("error"),
}

enum class PendingRequestKind(val wireValue: String) {
    PERMISSION("permission"),
    QUESTION("question"),
    IDLE_PROMPT("idle_prompt"),
}

data class AgentTurn(
    val lastTool: String? = null,
    val activeSince: Long? = null,
)

data class AgentPendingRequest(
    val kind: PendingRequestKind,
    val summary: String? = null,
    val createdAt: Long? = null,
)

data class AgentSession(
    val id: String,
    val provider: AgentProvider,
    val machineId: String? = null,
    val machineName: String? = null,
    val title: String? = null,
    val cwd: String? = null,
    val project: String? = null,
    val status: AgentStatus,
    val statusDetail: String? = null,
    val stale: Boolean = false,
    val lastActivityAt: Long? = null,
    val lastAssistantText: String? = null,
    val turn: AgentTurn? = null,
    val pendingRequest: AgentPendingRequest? = null,
) {
    val key: String
        get() = "${provider.wireValue}:$id"

    val displayTitle: String
        get() = title?.takeIf(String::isNotBlank)
            ?: project?.takeIf(String::isNotBlank)
            ?: cwd?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank)
            ?: id
}

enum class MessageRole(val wireValue: String, val label: String) {
    USER("user", "YOU"),
    ASSISTANT("assistant", "CC"),
    TOOL("tool", "RUN"),
}

data class AgentMessage(
    val role: MessageRole,
    val text: String,
    val at: Long? = null,
    val tool: String? = null,
)

/** The conversation the wearer currently has open, as served by the daemon. */
data class AgentConversation(
    val sessionKey: String,
    val sessionId: String,
    val provider: AgentProvider,
    val loading: Boolean = true,
    val messages: List<AgentMessage> = emptyList(),
) {
    companion object {
        const val MAX_MESSAGES = 60
    }
}

enum class ConnectionState(val wireValue: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    AUTH_FAILED("auth_failed"),
}

data class ProviderConnectionState(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val detail: String? = null,
)
