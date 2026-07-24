package com.anezium.rokidbus.plugin.agents

data class AgentNotificationDecision(
    val session: AgentSession,
    val fingerprint: String,
)

class NotificationDecisionEngine(
    private val readFingerprint: (String) -> String? = { null },
    private val writeFingerprint: (String, String) -> Unit = { _, _ -> },
) {
    fun transitions(
        previous: List<AgentSession>,
        current: List<AgentSession>,
    ): List<AgentNotificationDecision> {
        val previousByKey = previous.associateBy(AgentSession::key)
        return current.mapNotNull { session ->
            if (session.status != AgentStatus.NEEDS_YOU && session.status != AgentStatus.ERROR) {
                return@mapNotNull null
            }
            if (previousByKey[session.key]?.status == session.status) return@mapNotNull null
            val fingerprint = fingerprint(session)
            if (readFingerprint(session.key) == fingerprint) return@mapNotNull null
            writeFingerprint(session.key, fingerprint)
            AgentNotificationDecision(session, fingerprint)
        }
    }

    private fun fingerprint(session: AgentSession): String = when (session.status) {
        AgentStatus.NEEDS_YOU -> "needs_you:" + (
            session.pendingRequest?.createdAt?.toString()
                ?: session.pendingRequest?.summary?.singleLine(160)
                ?: "unknown"
            )
        AgentStatus.ERROR -> "error:" + (
            session.statusDetail?.singleLine(200)
                ?: session.lastAssistantText?.singleLine(200)
                ?: "unknown"
            )
        else -> session.status.wireValue
    }
}
