package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject

enum class WirelessAdbAction(val wireValue: String) {
    STATUS("status"),
    ENABLE("enable"),
    START_PAIRING("start_pairing"),
    CANCEL_PAIRING("cancel_pairing"),
    DISABLE("disable"),
    ;

    companion object {
        fun fromWireValue(value: String): WirelessAdbAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class WirelessAdbReply(
    val action: WirelessAdbAction,
    val success: Boolean,
    val wifiConnected: Boolean,
    val enabled: Boolean,
    val pairingActive: Boolean,
    val host: String? = null,
    val connectPort: Int? = null,
    val pairingPort: Int? = null,
    val pairingCode: String? = null,
    val expiresAtMillis: Long? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

object WirelessAdbContract {
    const val VERSION = 1
    private val pairingCodePattern = Regex("[0-9]{6}")

    fun request(action: WirelessAdbAction): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("action", action.wireValue)

    fun requestAction(payload: JSONObject): WirelessAdbAction? {
        if (payload.optInt("version", -1) != VERSION) return null
        return WirelessAdbAction.fromWireValue(payload.optString("action"))
    }

    fun stampedRequest(payload: JSONObject, pluginId: String): JSONObject? {
        if (requestAction(payload) == null || !PluginDescriptor.isValidId(pluginId)) return null
        return JSONObject(payload.toString()).put("pluginId", pluginId)
    }

    fun pluginId(payload: JSONObject): String? =
        payload.optString("pluginId").takeIf(PluginDescriptor::isValidId)

    fun reply(pluginId: String, value: WirelessAdbReply): JSONObject {
        require(PluginDescriptor.isValidId(pluginId)) { "Invalid plugin id" }
        return JSONObject()
            .put("version", VERSION)
            .put("pluginId", pluginId)
            .put("action", value.action.wireValue)
            .put("success", value.success)
            .put("wifiConnected", value.wifiConnected)
            .put("enabled", value.enabled)
            .put("pairingActive", value.pairingActive)
            .putOpt("host", value.host)
            .putOpt("connectPort", value.connectPort)
            .putOpt("pairingPort", value.pairingPort)
            .putOpt("pairingCode", value.pairingCode)
            .putOpt("expiresAtMillis", value.expiresAtMillis)
            .putOpt("errorCode", value.errorCode)
            .putOpt("message", value.message)
    }

    fun parseReply(payload: JSONObject): WirelessAdbReply? {
        if (payload.optInt("version", -1) != VERSION || pluginId(payload) == null) return null
        val action = WirelessAdbAction.fromWireValue(payload.optString("action")) ?: return null
        val success = payload.opt("success") as? Boolean ?: return null
        val wifiConnected = payload.opt("wifiConnected") as? Boolean ?: return null
        val enabled = payload.opt("enabled") as? Boolean ?: return null
        val pairingActive = payload.opt("pairingActive") as? Boolean ?: return null
        val host = payload.optString("host").takeIf { it.isNotBlank() }
        if (host != null && !isValidIpv4(host)) return null
        val connectPort = payload.optionalPort("connectPort") ?: if (payload.has("connectPort")) return null else null
        val pairingPort = payload.optionalPort("pairingPort") ?: if (payload.has("pairingPort")) return null else null
        val pairingCode = payload.optString("pairingCode").takeIf { it.isNotBlank() }
        if (pairingCode != null && !pairingCodePattern.matches(pairingCode)) return null
        val expiresAtMillis = if (payload.has("expiresAtMillis")) {
            payload.optLong("expiresAtMillis", -1L).takeIf { it > 0L } ?: return null
        } else {
            null
        }
        if (success && action == WirelessAdbAction.START_PAIRING &&
            (host == null || pairingPort == null || pairingCode == null || connectPort == null)
        ) {
            return null
        }
        return WirelessAdbReply(
            action = action,
            success = success,
            wifiConnected = wifiConnected,
            enabled = enabled,
            pairingActive = pairingActive,
            host = host,
            connectPort = connectPort,
            pairingPort = pairingPort,
            pairingCode = pairingCode,
            expiresAtMillis = expiresAtMillis,
            errorCode = payload.optString("errorCode").takeIf { it.isNotBlank() },
            message = payload.optString("message").takeIf { it.isNotBlank() },
        )
    }

    private fun JSONObject.optionalPort(key: String): Int? =
        optInt(key, -1).takeIf { it in 1..65535 }

    private fun isValidIpv4(value: String): Boolean {
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.length in 1..3 && octet.all { it in '0'..'9' } && octet.toInt() in 0..255
        }
    }
}
