package com.anezium.rokidbus.shared

import java.util.Locale
import org.json.JSONObject

/**
 * The vocabulary of things worth writing down while setup runs.
 *
 * A stage says where the run is. A note says what went wrong on the way there, and those are the
 * lines that matter when an owner reports "it stopped working" a week later: the automation could
 * not scroll the developer options list, the direct route to wireless debugging was redirected,
 * the channel directory refused to be created. None of that survives anywhere today -- it exists
 * only in the lens's logcat, which nobody can reach from a phone.
 *
 * Codes are stable wire values, deliberately short and boring, so an old lens talking to a new
 * phone still says something the phone can file rather than nothing at all.
 */
object SetupNote {
    /** Settings accepted the intent but landed somewhere else; the traversal has to take over. */
    const val DIRECT_ROUTE_REDIRECTED = "direct_route_redirected"

    /** The accessibility gesture could not move a Settings list, so the target stayed off-screen. */
    const val SETTINGS_SCROLL_STUCK = "settings_scroll_stuck"

    /** A Settings screen we asked for never appeared. */
    const val SETTINGS_SURFACE_MISSING = "settings_surface_missing"

    /** Staging the phone-driven arm scripts failed. Navigation carries on without them. */
    const val MANUAL_ASSETS_FAILED = "manual_assets_failed"

    /** The wearer was asked to do something the automation could not. */
    const val HANDED_TO_WEARER = "handed_to_wearer"

    /** A run gave up for good. */
    const val RUN_ABANDONED = "run_abandoned"

    val ALL: List<String> = listOf(
        DIRECT_ROUTE_REDIRECTED,
        SETTINGS_SCROLL_STUCK,
        SETTINGS_SURFACE_MISSING,
        MANUAL_ASSETS_FAILED,
        HANDED_TO_WEARER,
        RUN_ABANDONED,
    )

    fun normalize(value: String?): String {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
        return normalized.takeIf(ALL::contains).orEmpty()
    }
}

/** One note as it travels from the lens to the phone. */
data class SetupNoteMessage(
    val code: String,
    val stage: String,
    val detail: String,
)

object SetupNoteContract {
    const val VERSION = 1

    /** Detail is a short human hint, never a payload: it is shown to owners and shared as text. */
    const val MAX_DETAIL = 160

    fun toJson(message: SetupNoteMessage): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("code", message.code)
        .put("stage", message.stage)
        .put("detail", message.detail.take(MAX_DETAIL))

    /** Returns null for anything this build does not recognise, rather than filing noise. */
    fun fromJson(payload: JSONObject?): SetupNoteMessage? {
        val json = payload ?: return null
        val code = SetupNote.normalize(json.optString("code"))
        if (code.isEmpty()) return null
        return SetupNoteMessage(
            code = code,
            stage = SetupStage.normalize(json.optString("stage")),
            detail = json.optString("detail").orEmpty().trim().take(MAX_DETAIL),
        )
    }
}
