package com.anezium.rokidbus.shared

import org.json.JSONObject

data class PhoneHubCapabilities(
    val features: Int,
    val cameraConsumerName: String?,
    val activityAlwaysExpanded: Boolean = false,
    val hudTopInsetDp: Int = 0,
)

/** Additive phone-to-glasses hub capabilities payload. Unknown fields remain ignorable. */
object PhoneHubCapabilitiesContract {
    const val VERSION = 1
    const val MAX_CAMERA_CONSUMER_NAME_CHARS = 80
    const val MIN_HUD_TOP_INSET_DP = 0
    const val MAX_HUD_TOP_INSET_DP = 240
    const val DEFAULT_HUD_TOP_INSET_DP = 0

    fun create(
        features: Int,
        cameraConsumerName: String?,
        activityAlwaysExpanded: Boolean = false,
        hudTopInsetDp: Int = DEFAULT_HUD_TOP_INSET_DP,
    ): PhoneHubCapabilities {
        val ready = features and BusCapabilityBits.CAMERA_CONSUMER_READY != 0
        return PhoneHubCapabilities(
            features = features,
            cameraConsumerName = normalizeName(cameraConsumerName).takeIf { ready },
            activityAlwaysExpanded = activityAlwaysExpanded,
            hudTopInsetDp = sanitizeHudTopInsetDp(hudTopInsetDp),
        )
    }

    fun toJson(capabilities: PhoneHubCapabilities): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("features", capabilities.features)
        .put("activityAlwaysExpanded", capabilities.activityAlwaysExpanded)
        .put("hudTopInsetDp", sanitizeHudTopInsetDp(capabilities.hudTopInsetDp))
        .also { payload ->
            capabilities.cameraConsumerName?.let { payload.put("cameraConsumerName", it) }
        }

    fun parse(payload: JSONObject): PhoneHubCapabilities = create(
        features = payload.optInt("features", payload.optInt("capabilities", 0)),
        cameraConsumerName = payload.optString("cameraConsumerName", ""),
        activityAlwaysExpanded = payload.optBoolean("activityAlwaysExpanded", false),
        hudTopInsetDp = parseHudTopInsetDp(payload.opt("hudTopInsetDp")),
    )

    fun sanitizeHudTopInsetDp(value: Int): Int =
        value.coerceIn(MIN_HUD_TOP_INSET_DP, MAX_HUD_TOP_INSET_DP)

    private fun parseHudTopInsetDp(value: Any?): Int = when (value) {
        is Int -> sanitizeHudTopInsetDp(value)
        is Long -> value.coerceIn(
            MIN_HUD_TOP_INSET_DP.toLong(),
            MAX_HUD_TOP_INSET_DP.toLong(),
        ).toInt()
        else -> DEFAULT_HUD_TOP_INSET_DP
    }

    private fun normalizeName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_CAMERA_CONSUMER_NAME_CHARS }
}
