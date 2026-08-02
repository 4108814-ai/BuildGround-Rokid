package com.anezium.rokidbus.plugin.agents

/** A session that has earned an interruption, and the state that earned it. */
data class AgentAttention(
    val session: AgentSession,
    val fingerprint: String,
)

/**
 * Decides which sessions deserve to interrupt the wearer.
 *
 * Two properties matter more than the rules themselves:
 *
 * 1. **[pending] has no side effects.** The fingerprint is committed by
 *    [commit], and only once the alert has actually reached the glasses. The
 *    previous design consumed the fingerprint first, so an alert that failed to
 *    deliver was marked as delivered forever — the one failure this product
 *    cannot afford.
 * 2. **The fingerprint is the whole memory.** No before/after list to keep in
 *    sync: a session deserves attention while its current state is unannounced,
 *    which means an undelivered alert is naturally retried on the next update
 *    instead of needing a queue.
 */
class AttentionDecisionEngine(
    private val readFingerprint: (String) -> String?,
    private val writeFingerprint: (String, String) -> Unit,
) {
    fun pending(sessions: List<AgentSession>): List<AgentAttention> =
        sessions.mapNotNull { session ->
            if (!session.status.deservesAttention) return@mapNotNull null
            val fingerprint = fingerprint(session)
            if (readFingerprint(session.key) == fingerprint) return@mapNotNull null
            AgentAttention(session, fingerprint)
        }

    /** Call once the alert is on its way to the glasses, never before. */
    fun commit(attention: AgentAttention) {
        writeFingerprint(attention.session.key, attention.fingerprint)
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

    private val AgentStatus.deservesAttention: Boolean
        get() = this == AgentStatus.NEEDS_YOU || this == AgentStatus.ERROR
}
