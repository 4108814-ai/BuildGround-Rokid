package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Boot repair of the glasses' privileged helper, steered from the phone.
 *
 * The helper is a shell-uid process no reboot spares, and this ROM boots with Wi-Fi off, so the
 * radio edge that would revive it in the background never comes on its own. The glasses can open
 * their Settings and flip Wi-Fi themselves, but whether they may do that unprompted is the
 * owner's call: the phone holds the switch, the glasses hold the behaviour. The config write is
 * persisted glasses-side because the phone is exactly the thing that is absent at boot.
 */
object GlassesRepairContract {
    const val VERSION = 1

    /** An owner who never touched the switch gets the repair: absent config reads as enabled. */
    const val DEFAULT_AUTO_REPAIR = true

    /** The helper was gone and the repair brought it back. */
    const val RESULT_REPAIRED = "repaired"

    /** Nothing on record says the helper is missing; no Settings run was spent proving it. */
    const val RESULT_ALREADY_HEALTHY = "already_healthy"

    /** The Settings automation could not turn the glasses' Wi-Fi on. */
    const val RESULT_WIFI_UNAVAILABLE = "wifi_unavailable"

    /** Wi-Fi came up but the helper could not be restored over it. */
    const val RESULT_ARM_FAILED = "arm_failed"

    /** A repair or a setup flow already owns the machinery this one needs. */
    const val RESULT_BUSY = "busy"

    val RESULTS: List<String> = listOf(
        RESULT_REPAIRED,
        RESULT_ALREADY_HEALTHY,
        RESULT_WIFI_UNAVAILABLE,
        RESULT_ARM_FAILED,
        RESULT_BUSY,
    )

    fun configToJson(autoRepairEnabled: Boolean): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("autoRepair", autoRepairEnabled)

    /**
     * Null for anything this build does not recognise — the glasses' stored setting then stands,
     * which beats letting a malformed write silently re-enable or disable the popup.
     */
    fun autoRepairFromConfig(payload: JSONObject?): Boolean? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        return json.opt("autoRepair") as? Boolean
    }

    fun requestToJson(): JSONObject = JSONObject().put("version", VERSION)

    fun replyToJson(result: String): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("result", result)

    /** Null for a result this build does not know, so version skew reads as "no answer". */
    fun resultFromReply(payload: JSONObject?): String? =
        payload?.optString("result")?.takeIf(RESULTS::contains)
}
