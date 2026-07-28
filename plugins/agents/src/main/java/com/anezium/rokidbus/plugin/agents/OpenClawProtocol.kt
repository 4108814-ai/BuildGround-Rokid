package com.anezium.rokidbus.plugin.agents

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

data class OpenClawDeviceIdentity(
    val seed: ByteArray,
    val publicKey: ByteArray,
) {
    val deviceId: String
        get() = MessageDigest.getInstance("SHA-256").digest(publicKey)
            .joinToString("") { "%02x".format(it) }
}

data class OpenClawApproval(
    val id: String,
    val sessionKey: String,
    val summary: String,
    val createdAtMs: Long?,
)

sealed interface OpenClawEvent {
    data class ConnectChallenge(val nonce: String) : OpenClawEvent
    data object SessionsChanged : OpenClawEvent
    data class ApprovalRequested(val approval: OpenClawApproval) : OpenClawEvent
    data class ApprovalResolved(val id: String) : OpenClawEvent
    data object Unknown : OpenClawEvent
}

object OpenClawProtocol {
    const val PROTOCOL_VERSION = 4
    const val CONNECT_ID = "connect"
    const val LIST_ID = "sessions-list"
    const val SUBSCRIBE_ID = "sessions-subscribe"

    fun parseEvent(frame: JSONObject): OpenClawEvent {
        if (frame.nullableString("type", MAX_WIRE_TYPE_CHARS) != "event") {
            return OpenClawEvent.Unknown
        }
        val payload = frame.optJSONObject("payload") ?: return OpenClawEvent.Unknown
        return when (frame.nullableString("event", MAX_WIRE_TYPE_CHARS)) {
            "connect.challenge" -> payload.identifierOrNull("nonce", MAX_AUTH_TOKEN_CHARS)
                ?.let(OpenClawEvent::ConnectChallenge)
                ?: OpenClawEvent.Unknown
            "sessions.changed" -> OpenClawEvent.SessionsChanged
            "exec.approval.requested" -> parseApproval(payload)
                ?.let(OpenClawEvent::ApprovalRequested)
                ?: OpenClawEvent.Unknown
            "exec.approval.resolved" -> payload.identifierOrNull("id")
                ?.let(OpenClawEvent::ApprovalResolved)
                ?: OpenClawEvent.Unknown
            else -> OpenClawEvent.Unknown
        }
    }

    fun connectRequest(
        nonce: String,
        token: String,
        identity: OpenClawDeviceIdentity,
        versionName: String,
        deviceToken: String? = null,
        signedAtMs: Long = System.currentTimeMillis(),
    ): String {
        val scopes = listOf("operator.read", "operator.approvals")
        val signaturePayload = listOf(
            "v3",
            identity.deviceId,
            "gateway-client",
            "backend",
            "operator",
            scopes.joinToString(","),
            signedAtMs.toString(),
            token,
            nonce,
            "android",
            "phone",
        ).joinToString("|")
        val signature = base64Url(Ed25519.sign(identity.seed, signaturePayload.toByteArray()))
        val params = JSONObject()
            .put("minProtocol", PROTOCOL_VERSION)
            .put("maxProtocol", PROTOCOL_VERSION)
            .put(
                "client",
                JSONObject()
                    .put("id", "gateway-client")
                    .put("displayName", "Nexus Agents")
                    .put("version", versionName)
                    .put("platform", "android")
                    .put("deviceFamily", "phone")
                    .put("mode", "backend"),
            )
            .put("caps", JSONArray().put("exec-approvals"))
            .put("role", "operator")
            .put("scopes", JSONArray(scopes))
            .put(
                "auth",
                JSONObject()
                    .put("token", token)
                    .apply { deviceToken?.let { put("deviceToken", it) } },
            )
            .put(
                "device",
                JSONObject()
                    .put("id", identity.deviceId)
                    .put("publicKey", base64Url(identity.publicKey))
                    .put("signature", signature)
                    .put("signedAt", signedAtMs)
                    .put("nonce", nonce),
            )
        return request(CONNECT_ID, "connect", params)
    }

    fun sessionsListRequest(): String = request(
        LIST_ID,
        "sessions.list",
        JSONObject()
            .put("limit", 200)
            .put("includeDerivedTitles", true)
            .put("includeLastMessage", true),
    )

    fun sessionsSubscribeRequest(): String =
        request(SUBSCRIBE_ID, "sessions.subscribe", JSONObject())

    fun mapSessions(
        payload: JSONObject,
        approvals: Collection<OpenClawApproval> = emptyList(),
    ): List<AgentSession> = requireNotNull(mapSessionsOrNull(payload, approvals)) {
        "sessions.list payload must contain a sessions array"
    }

    fun mapSessionsOrNull(
        payload: JSONObject,
        approvals: Collection<OpenClawApproval> = emptyList(),
    ): List<AgentSession>? {
        val sessions = payload.opt("sessions") as? JSONArray ?: return null
        val baseSessions = buildList {
            for (index in 0 until minOf(sessions.length(), MAX_SESSIONS_PER_PROVIDER)) {
                val row = sessions.optJSONObject(index) ?: continue
                row.toAgentSession()?.let(::add)
            }
        }
        return applyApprovals(baseSessions, approvals)
    }

    fun applyApprovals(
        sessions: List<AgentSession>,
        approvals: Collection<OpenClawApproval>,
    ): List<AgentSession> {
        val approvalsBySession = approvals.asSequence()
            .take(MAX_PENDING_APPROVALS)
            .groupBy(OpenClawApproval::sessionKey)
            .mapValues { (_, values) ->
                values.minWithOrNull(
                    compareBy<OpenClawApproval> { it.createdAtMs ?: Long.MAX_VALUE }
                        .thenBy(OpenClawApproval::id),
                )
            }
        return sessions.take(MAX_SESSIONS_PER_PROVIDER).map { session ->
            val approval = approvalsBySession[session.id]
            if (approval == null) {
                session
            } else {
                session.copy(
                    status = AgentStatus.NEEDS_YOU,
                    statusDetail = null,
                    pendingRequest = AgentPendingRequest(
                        kind = PendingRequestKind.PERMISSION,
                        summary = approval.summary.singleLine(MAX_HUD_LABEL_CHARS),
                        createdAt = approval.createdAtMs,
                    ),
                )
            }
        }
    }

    fun isAuthError(error: JSONObject?): Boolean {
        val code = error?.nullableString("code")?.uppercase() ?: return false
        return code.contains("AUTH_TOKEN") ||
            code == "UNAUTHORIZED" ||
            code == "AUTH_REQUIRED" ||
            code == "INVALID_TOKEN"
    }

    fun isPairingError(error: JSONObject?): Boolean {
        val code = error?.nullableString("code")?.uppercase().orEmpty()
        val detailCode = error?.optJSONObject("details")?.nullableString("code")?.uppercase().orEmpty()
        return code.contains("PAIR") || detailCode.contains("PAIR")
    }

    private fun parseApproval(payload: JSONObject): OpenClawApproval? {
        val id = payload.identifierOrNull("id") ?: return null
        val request = payload.optJSONObject("request") ?: return null
        val plan = request.optJSONObject("systemRunPlan")
        val sessionKey = request.identifierOrNull("sessionKey")
            ?: plan?.identifierOrNull("sessionKey")
            ?: return null
        val command = request.nullableString("command", MAX_HUD_LABEL_CHARS)
            ?: plan?.nullableString("commandPreview", MAX_HUD_LABEL_CHARS)
            ?: plan?.nullableString("commandText", MAX_HUD_LABEL_CHARS)
            ?: request.optJSONArray("commandArgv")?.joinStrings()
            ?: "Command approval"
        return OpenClawApproval(
            id = id,
            sessionKey = sessionKey,
            summary = command.singleLine(160),
            createdAtMs = payload.longOrNull("createdAtMs"),
        )
    }

    private fun request(id: String, method: String, params: JSONObject): String = JSONObject()
        .put("type", "req")
        .put("id", id)
        .put("method", method)
        .put("params", params)
        .toString()

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun JSONObject.toAgentSession(): AgentSession? {
        val key = identifierOrNull("key") ?: return null
        val gatewayStatus = nullableString("status", MAX_WIRE_TYPE_CHARS)
        val lastRunError = nullableString("lastRunError", MAX_STATUS_DETAIL_CHARS)
        val active = booleanOrNull("hasActiveRun") == true || gatewayStatus == "running"
        val failed = gatewayStatus in setOf("failed", "killed", "timeout") ||
            lastRunError != null
        val status = when {
            active -> AgentStatus.WORKING
            failed -> AgentStatus.ERROR
            else -> AgentStatus.IDLE
        }
        val agentStatus = optJSONObject("agentStatus")
        val observer = optJSONObject("observerDigest")
        val title = sequenceOf("displayName", "label", "derivedTitle")
            .mapNotNull { nullableString(it, MAX_HUD_LABEL_CHARS) }
            .firstOrNull()
            ?: key.take(MAX_HUD_LABEL_CHARS)
        val worktreeRoot = optJSONObject("worktree")
            ?.nullableString("repoRoot", MAX_PATH_CHARS)
        val cwd = sequenceOf("execCwd", "spawnedCwd", "spawnedWorkspaceDir")
            .mapNotNull { nullableString(it, MAX_PATH_CHARS) }
            .firstOrNull()
            ?: worktreeRoot
        return AgentSession(
            id = key,
            provider = AgentProvider.OPENCLAW,
            title = title,
            cwd = cwd,
            project = worktreeRoot
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.take(MAX_HUD_LABEL_CHARS),
            status = status,
            statusDetail = when {
                failed -> lastRunError
                    ?: "Gateway run ${gatewayStatus.orEmpty()}".take(MAX_STATUS_DETAIL_CHARS)
                agentStatus != null ->
                    agentStatus.nullableString("note", MAX_STATUS_DETAIL_CHARS)
                observer != null ->
                    observer.nullableString("headline", MAX_STATUS_DETAIL_CHARS)
                gatewayStatus != null ->
                    "Gateway run $gatewayStatus".take(MAX_STATUS_DETAIL_CHARS)
                else -> null
            },
            lastActivityAt = longOrNull("lastActivityAt") ?: longOrNull("updatedAt"),
        )
    }
}

private fun JSONArray.joinStrings(): String? = buildList {
    for (index in 0 until minOf(length(), MAX_PENDING_APPROVALS)) {
        val value = opt(index)
        if (value is String) {
            value.trim().takeIf(String::isNotBlank)?.take(MAX_HUD_LABEL_CHARS)?.let(::add)
        }
    }
}.takeIf(List<String>::isNotEmpty)?.joinToString(" ")?.take(MAX_HUD_LABEL_CHARS)

internal fun String.singleLine(maxChars: Int): String =
    replace(Regex("\\s+"), " ").trim().take(maxChars)
