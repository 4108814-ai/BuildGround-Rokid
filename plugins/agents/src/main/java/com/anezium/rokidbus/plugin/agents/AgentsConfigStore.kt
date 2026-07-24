package com.anezium.rokidbus.plugin.agents

import android.content.Context
import org.json.JSONObject

data class AgentdConfig(
    val host: String,
    val port: Int,
    val token: String,
    val name: String,
) {
    val configured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()
}

data class OpenClawConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val token: String,
) {
    val configured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()

    companion object {
        const val DEFAULT_PORT = 18789
    }
}

data class AgentsConfig(
    val agentdEnabled: Boolean,
    val agentd: AgentdConfig?,
    val openClawEnabled: Boolean,
    val openClaw: OpenClawConfig?,
) {
    val shouldMonitor: Boolean
        get() = agentdEnabled && agentd?.configured == true ||
            openClawEnabled && openClaw?.configured == true
}

sealed interface AgentdPairingParseResult {
    data class Valid(val config: AgentdConfig) : AgentdPairingParseResult
    data class Invalid(val reason: String) : AgentdPairingParseResult
}

object AgentdPairingParser {
    fun parse(raw: String): AgentdPairingParseResult {
        val json = runCatching { JSONObject(raw.trim()) }.getOrNull()
            ?: return AgentdPairingParseResult.Invalid("Pairing data is not valid JSON.")
        if (json.optInt("v", -1) != 1) {
            return AgentdPairingParseResult.Invalid("Unsupported pairing version.")
        }
        if (json.optString("kind") != "nexus-agentd") {
            return AgentdPairingParseResult.Invalid("Pairing kind must be nexus-agentd.")
        }
        val host = json.optString("host").trim()
        val port = json.optInt("port", -1)
        val token = json.optString("token").trim()
        val name = json.optString("name").trim().ifBlank { host }
        if (host.isBlank()) return AgentdPairingParseResult.Invalid("Host is missing.")
        if (port !in 1..65535) return AgentdPairingParseResult.Invalid("Port is invalid.")
        if (token.isBlank()) return AgentdPairingParseResult.Invalid("Token is missing.")
        return AgentdPairingParseResult.Valid(AgentdConfig(host, port, token, name))
    }
}

class AgentsConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AgentsConfig {
        val agentdHost = prefs.getString(KEY_AGENTD_HOST, null)
        val agentd = agentdHost?.let {
            AgentdConfig(
                host = it,
                port = prefs.getInt(KEY_AGENTD_PORT, 8792),
                token = prefs.getString(KEY_AGENTD_TOKEN, "").orEmpty(),
                name = prefs.getString(KEY_AGENTD_NAME, it).orEmpty(),
            )
        }?.takeIf(AgentdConfig::configured)
        val openClawHost = prefs.getString(KEY_OPENCLAW_HOST, null)
        val openClaw = openClawHost?.let {
            OpenClawConfig(
                host = it,
                port = prefs.getInt(KEY_OPENCLAW_PORT, OpenClawConfig.DEFAULT_PORT),
                token = prefs.getString(KEY_OPENCLAW_TOKEN, "").orEmpty(),
            )
        }?.takeIf(OpenClawConfig::configured)
        return AgentsConfig(
            agentdEnabled = prefs.getBoolean(KEY_AGENTD_ENABLED, false),
            agentd = agentd,
            openClawEnabled = prefs.getBoolean(KEY_OPENCLAW_ENABLED, false),
            openClaw = openClaw,
        )
    }

    fun saveAgentd(config: AgentdConfig?, enabled: Boolean) {
        prefs.edit().apply {
            putBoolean(KEY_AGENTD_ENABLED, enabled)
            if (config == null) {
                remove(KEY_AGENTD_HOST)
                remove(KEY_AGENTD_PORT)
                remove(KEY_AGENTD_TOKEN)
                remove(KEY_AGENTD_NAME)
            } else {
                putString(KEY_AGENTD_HOST, config.host)
                putInt(KEY_AGENTD_PORT, config.port)
                putString(KEY_AGENTD_TOKEN, config.token)
                putString(KEY_AGENTD_NAME, config.name)
            }
        }.apply()
    }

    fun saveOpenClaw(config: OpenClawConfig?, enabled: Boolean) {
        val previous = load().openClaw
        prefs.edit().apply {
            putBoolean(KEY_OPENCLAW_ENABLED, enabled)
            if (config == null) {
                remove(KEY_OPENCLAW_HOST)
                remove(KEY_OPENCLAW_PORT)
                remove(KEY_OPENCLAW_TOKEN)
            } else {
                putString(KEY_OPENCLAW_HOST, config.host)
                putInt(KEY_OPENCLAW_PORT, config.port)
                putString(KEY_OPENCLAW_TOKEN, config.token)
            }
            if (config != previous) {
                remove(KEY_OPENCLAW_DEVICE_TOKEN)
            }
        }.apply()
    }

    fun openClawSeed(): ByteArray? = prefs.getString(KEY_OPENCLAW_SEED, null)
        ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
        ?.takeIf { it.size == 32 }

    fun saveOpenClawSeed(seed: ByteArray) {
        require(seed.size == 32)
        prefs.edit().putString(
            KEY_OPENCLAW_SEED,
            android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP),
        ).apply()
    }

    fun openClawDeviceToken(): String? =
        prefs.getString(KEY_OPENCLAW_DEVICE_TOKEN, null)?.takeIf(String::isNotBlank)

    fun saveOpenClawDeviceToken(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString(KEY_OPENCLAW_DEVICE_TOKEN, token).apply()
    }

    fun notificationFingerprint(sessionKey: String): String? =
        prefs.getString("$KEY_NOTIFICATION_PREFIX$sessionKey", null)

    fun saveNotificationFingerprint(sessionKey: String, fingerprint: String) {
        prefs.edit().putString("$KEY_NOTIFICATION_PREFIX$sessionKey", fingerprint).apply()
    }

    private companion object {
        const val PREFS = "nexus_plugin_agents"
        const val KEY_AGENTD_ENABLED = "agentd.enabled"
        const val KEY_AGENTD_HOST = "agentd.host"
        const val KEY_AGENTD_PORT = "agentd.port"
        const val KEY_AGENTD_TOKEN = "agentd.token"
        const val KEY_AGENTD_NAME = "agentd.name"
        const val KEY_OPENCLAW_ENABLED = "openclaw.enabled"
        const val KEY_OPENCLAW_HOST = "openclaw.host"
        const val KEY_OPENCLAW_PORT = "openclaw.port"
        const val KEY_OPENCLAW_TOKEN = "openclaw.token"
        const val KEY_OPENCLAW_SEED = "openclaw.device_seed"
        const val KEY_OPENCLAW_DEVICE_TOKEN = "openclaw.device_token"
        const val KEY_NOTIFICATION_PREFIX = "notification."
    }
}
