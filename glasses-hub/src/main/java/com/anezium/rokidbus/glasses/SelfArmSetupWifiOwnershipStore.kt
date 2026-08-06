package com.anezium.rokidbus.glasses

import android.content.Context

internal data class SelfArmSetupWifiOwnershipRecord(
    val sessionId: String,
    val wifiWasEnabledBeforeSetup: Boolean,
    val enableIssued: Boolean,
    val enableRequestInFlight: Boolean,
    val recordedAtMillis: Long,
)

/** Durable evidence that setup found Wi-Fi off before Nexus attempted to turn it on. */
internal object SelfArmSetupWifiOwnershipStore {
    fun recordBeforeEnable(
        context: Context,
        sessionId: String,
        wifiCurrentlyEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (sessionId.isBlank() || wifiCurrentlyEnabled) return false
        val existing = read(context)
        if (existing?.enableRequestInFlight == true &&
            isEnableRequestInFlight(context, nowMillis)
        ) {
            return false
        }
        return prefs(context).edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putBoolean(KEY_WIFI_WAS_ENABLED_BEFORE_SETUP, false)
            .putBoolean(KEY_ENABLE_ISSUED, false)
            .putBoolean(KEY_ENABLE_REQUEST_IN_FLIGHT, false)
            .putLong(KEY_RECORDED_AT_MILLIS, nowMillis)
            .commit()
    }

    fun markEnableIssued(
        context: Context,
        sessionId: String,
        requestInFlight: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (sessionId.isBlank()) return false
        val existing = read(context) ?: return false
        if (existing.sessionId != sessionId) return false
        return prefs(context).edit()
            .putBoolean(KEY_ENABLE_ISSUED, true)
            .putBoolean(KEY_ENABLE_REQUEST_IN_FLIGHT, requestInFlight)
            .putLong(KEY_RECORDED_AT_MILLIS, nowMillis)
            .commit()
    }

    fun markEnableRequestFinished(context: Context, sessionId: String): Boolean {
        val existing = read(context) ?: return false
        if (existing.sessionId != sessionId || !existing.enableRequestInFlight) return false
        return prefs(context).edit()
            .putBoolean(KEY_ENABLE_REQUEST_IN_FLIGHT, false)
            .commit()
    }

    fun discardUnissued(context: Context, sessionId: String? = null): Boolean {
        val record = read(context) ?: return false
        if (record.enableIssued || sessionId != null && record.sessionId != sessionId) return false
        clear(context)
        return true
    }

    fun clearIfRadioObservedOff(context: Context, wifiEnabled: Boolean): Boolean {
        if (wifiEnabled || read(context) == null) return false
        clear(context)
        return true
    }

    fun clearAfterRadioDisabled(context: Context) = clear(context)

    fun isNexusOwned(context: Context): Boolean = read(context)?.let { record ->
        !record.wifiWasEnabledBeforeSetup && record.enableIssued
    } == true

    fun isPreparedForEnable(context: Context, sessionId: String): Boolean =
        read(context)?.sessionId == sessionId

    fun isEnableRequestInFlight(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = read(context)?.let { record ->
        record.enableRequestInFlight &&
            nowMillis >= record.recordedAtMillis &&
            nowMillis - record.recordedAtMillis < ENABLE_REQUEST_STALE_MS
    } == true

    fun read(context: Context): SelfArmSetupWifiOwnershipRecord? {
        val preferences = prefs(context)
        val sessionId = preferences.getString(KEY_SESSION_ID, "").orEmpty()
        val recordedAtMillis = preferences.getLong(KEY_RECORDED_AT_MILLIS, 0L)
        if (sessionId.isBlank() || recordedAtMillis <= 0L) return null
        return SelfArmSetupWifiOwnershipRecord(
            sessionId = sessionId,
            wifiWasEnabledBeforeSetup = preferences.getBoolean(
                KEY_WIFI_WAS_ENABLED_BEFORE_SETUP,
                true,
            ),
            enableIssued = preferences.getBoolean(KEY_ENABLE_ISSUED, false),
            enableRequestInFlight = preferences.getBoolean(KEY_ENABLE_REQUEST_IN_FLIGHT, false),
            recordedAtMillis = recordedAtMillis,
        )
    }

    private fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_WIFI_WAS_ENABLED_BEFORE_SETUP)
            .remove(KEY_ENABLE_ISSUED)
            .remove(KEY_ENABLE_REQUEST_IN_FLIGHT)
            .remove(KEY_RECORDED_AT_MILLIS)
            .commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "selfarm_wireless"
    private const val KEY_SESSION_ID = "setup_wifi_owner_session_id"
    private const val KEY_WIFI_WAS_ENABLED_BEFORE_SETUP = "setup_wifi_was_enabled_before_setup"
    private const val KEY_ENABLE_ISSUED = "setup_wifi_enable_issued"
    private const val KEY_ENABLE_REQUEST_IN_FLIGHT = "setup_wifi_enable_request_in_flight"
    private const val KEY_RECORDED_AT_MILLIS = "setup_wifi_owner_recorded_at_millis"
    private const val ENABLE_REQUEST_STALE_MS = 30_000L
}
