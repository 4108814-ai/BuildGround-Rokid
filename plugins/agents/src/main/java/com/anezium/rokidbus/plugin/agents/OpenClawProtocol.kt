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
        if (frame.optString("type") != "event") return OpenClawEvent.Unknown
        val payload = frame.optJSONObject("payload") ?: return OpenClawEvent.Unknown
        return when (frame.optString("event")) {
            "connect.challenge" -> payload.nullableString("nonce")
                ?.let(OpenClawEvent::ConnectChallenge)
                ?: OpenClawEvent.Unknown
            "sessions.changed" -> OpenClawEvent.SessionsChanged
            "exec.approval.requested" -> parseApproval(payload)
                ?.let(OpenClawEvent::ApprovalRequested)
                ?: OpenClawEvent.Unknown
            "exec.approval.resolved" -> payload.nullableString("id")
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
    ): List<AgentSession> {
        val approvalsBySession = approvals.groupBy(OpenClawApproval::sessionKey)
            .mapValues { (_, values) ->
                values.minWithOrNull(
                    compareBy<OpenClawApproval> { it.createdAtMs ?: Long.MAX_VALUE }
                        .thenBy(OpenClawApproval::id),
                )
            }
        val sessions = payload.optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (index in 0 until sessions.length()) {
                val row = sessions.optJSONObject(index) ?: continue
                val key = row.nullableString("key") ?: continue
                val approval = approvalsBySession[key]
                val gatewayStatus = row.nullableString("status")
                val lastRunError = row.nullableString("lastRunError")
                val active = row.optBoolean("hasActiveRun", false) || gatewayStatus == "running"
                val failed = gatewayStatus in setOf("failed", "killed", "timeout") ||
                    lastRunError != null
                val status = when {
                    approval != null -> AgentStatus.NEEDS_YOU
                    active -> AgentStatus.WORKING
                    failed -> AgentStatus.ERROR
                    else -> AgentStatus.IDLE
                }
                val agentStatus = row.optJSONObject("agentStatus")
                val observer = row.optJSONObject("observerDigest")
                val title = sequenceOf("displayName", "label", "derivedTitle")
                    .mapNotNull(row::nullableString)
                    .firstOrNull()
                    ?: key
                val worktreeRoot = row.optJSONObject("worktree")?.nullableString("repoRoot")
                val cwd = sequenceOf("execCwd", "spawnedCwd", "spawnedWorkspaceDir")
                    .mapNotNull(row::nullableString)
                    .firstOrNull()
                    ?: worktreeRoot
                add(
                    AgentSession(
                        id = key,
                        provider = AgentProvider.OPENCLAW,
                        title = title,
                        cwd = cwd,
                        project = worktreeRoot?.substringAfterLast('/')?.substringAfterLast('\\'),
                        status = status,
                        statusDetail = when {
                            approval != null -> null
                            failed -> lastRunError ?: "Gateway run $gatewayStatus"
                            agentStatus != null -> agentStatus.nullableString("note")
                            observer != null -> observer.nullableString("headline")
                            gatewayStatus != null -> "Gateway run $gatewayStatus"
                            else -> null
                        },
                        lastActivityAt = row.longOrNull("lastActivityAt")
                            ?: row.longOrNull("updatedAt"),
                        pendingRequest = approval?.let {
                            AgentPendingRequest(
                                kind = PendingRequestKind.PERMISSION,
                                summary = it.summary,
                                createdAt = it.createdAtMs,
                            )
                        },
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
        val id = payload.nullableString("id") ?: return null
        val request = payload.optJSONObject("request") ?: return null
        val plan = request.optJSONObject("systemRunPlan")
        val sessionKey = request.nullableString("sessionKey")
            ?: plan?.nullableString("sessionKey")
            ?: return null
        val command = request.nullableString("command")
            ?: plan?.nullableString("commandPreview")
            ?: plan?.nullableString("commandText")
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
}

private fun JSONArray.joinStrings(): String? = buildList {
    for (index in 0 until length()) {
        optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}.takeIf(List<String>::isNotEmpty)?.joinToString(" ")

internal fun String.singleLine(maxChars: Int): String =
    replace(Regex("\\s+"), " ").trim().take(maxChars)
