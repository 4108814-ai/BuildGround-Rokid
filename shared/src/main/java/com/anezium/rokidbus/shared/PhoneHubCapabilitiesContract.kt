package com.anezium.rokidbus.shared

import org.json.JSONObject

data class PhoneHubCapabilities(
    val features: Int,
    val cameraConsumerName: String?,
    val activityAlwaysExpanded: Boolean = false,
)

/** Additive phone-to-glasses hub capabilities payload. Unknown fields remain ignorable. */
object PhoneHubCapabilitiesContract {
    const val VERSION = 1
    const val MAX_CAMERA_CONSUMER_NAME_CHARS = 80

    fun create(
        features: Int,
        cameraConsumerName: String?,
        activityAlwaysExpanded: Boolean = false,
    ): PhoneHubCapabilities {
        val ready = features and BusCapabilityBits.CAMERA_CONSUMER_READY != 0
        return PhoneHubCapabilities(
            features = features,
            cameraConsumerName = normalizeName(cameraConsumerName).takeIf { ready },
            activityAlwaysExpanded = activityAlwaysExpanded,
        )
    }

    fun toJson(capabilities: PhoneHubCapabilities): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("features", capabilities.features)
        .put("activityAlwaysExpanded", capabilities.activityAlwaysExpanded)
        .also { payload ->
            capabilities.cameraConsumerName?.let { payload.put("cameraConsumerName", it) }
        }

    fun parse(payload: JSONObject): PhoneHubCapabilities = create(
        features = payload.optInt("features", payload.optInt("capabilities", 0)),
        cameraConsumerName = payload.optString("cameraConsumerName", ""),
        activityAlwaysExpanded = payload.optBoolean("activityAlwaysExpanded", false),
    )

    private fun normalizeName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_CAMERA_CONSUMER_NAME_CHARS }
}
