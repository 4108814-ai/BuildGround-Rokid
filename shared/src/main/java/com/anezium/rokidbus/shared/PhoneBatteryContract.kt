package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * The tethered phone's charge, as it appears in the glasses' own status row.
 *
 * **Hub-owned, deliberately not a plugin API.** The phone's battery is system
 * status in the same sense as the glasses' own — it belongs next to the ROM's
 * indicators, not to a third party. Opening a permanent status slot to plugins
 * is the inversion plan 012 refuses: given the choice, every plugin would take
 * one and the HUD becomes a billboard. So there is no capability bit and no
 * ownership arbitration here; the phone hub is the only sender, and if a second
 * badge is ever justified it gets designed then, not left open now.
 *
 * The payload is a level and a flag because that is all the badge draws. There
 * is no health field: Android exposes `BATTERY_PROPERTY_*` health as a coarse
 * enum (GOOD / OVERHEAT / DEAD) with no state-of-charge or cycle count, so a
 * "battery health" badge would be reporting a constant.
 */
object PhoneBatteryContract {

    const val VERSION = 1

    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 100

    const val ERROR_INVALID = "INVALID_PHONE_BATTERY"

    const val KEY_LEVEL = "level"
    const val KEY_CHARGING = "charging"
    const val KEY_SEQ = "seq"
    const val KEY_HIDDEN = "hidden"

    data class Reading(val level: Int, val charging: Boolean)

    sealed interface ValidationResult {
        /** [reading] is null when the phone says the chip should not be shown. */
        data class Valid(val reading: Reading?, val seq: Long) : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    fun toJson(reading: Reading, seq: Long): JSONObject =
        JSONObject()
            .put(KEY_LEVEL, reading.level)
            .put(KEY_CHARGING, reading.charging)
            .put(KEY_SEQ, seq)

    /**
     * The wearer turned the badge off, as a message rather than as silence.
     *
     * Silence cannot retract a chip: a reading deliberately never expires (an
     * old percentage is stale, not wrong, and blanking on a link drop would
     * read as the phone dying), so the glasses hold the last value until told
     * otherwise. This is the telling-otherwise.
     */
    fun toHiddenJson(seq: Long): JSONObject =
        JSONObject()
            .put(KEY_HIDDEN, true)
            .put(KEY_SEQ, seq)

    /**
     * A level outside 0..100 is rejected rather than clamped.
     *
     * Clamping would turn a phone-side bug into a badge that quietly reads 100
     * forever. Rejecting keeps the last good value on screen, which is both more
     * honest and what the wearer would prefer: a stale number is recoverable,
     * a confidently wrong one is not.
     */
    fun validate(payload: JSONObject?): ValidationResult {
        val json = payload ?: return ValidationResult.Invalid(ERROR_INVALID)
        val seq = json.optLong(KEY_SEQ, Long.MIN_VALUE)
        if (json.optBoolean(KEY_HIDDEN, false)) {
            return ValidationResult.Valid(reading = null, seq = seq)
        }
        if (!json.has(KEY_LEVEL)) return ValidationResult.Invalid(ERROR_INVALID)
        val level = json.optInt(KEY_LEVEL, Int.MIN_VALUE)
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            return ValidationResult.Invalid(ERROR_INVALID)
        }
        return ValidationResult.Valid(
            reading = Reading(level = level, charging = json.optBoolean(KEY_CHARGING, false)),
            seq = seq,
        )
    }

    /**
     * What the badge draws next to the glyph.
     *
     * The charging suffix is a character rather than a colour because the optics
     * are green-only in practice: a second hue is either invisible or reads as
     * the same green. It is also why there is no low-battery tint — the number
     * is the warning, and "8" says it better than a shade would.
     */
    fun label(reading: Reading): String =
        reading.level.toString() + if (reading.charging) "+" else ""
}
