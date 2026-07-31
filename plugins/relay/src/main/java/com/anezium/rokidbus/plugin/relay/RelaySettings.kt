package com.anezium.rokidbus.plugin.relay

import android.content.Context

internal class RelaySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun imagePreviewsEnabled(): Boolean =
        prefs.getBoolean(KEY_IMAGE_PREVIEWS, DEFAULT_IMAGE_PREVIEWS)

    fun setImagePreviewsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IMAGE_PREVIEWS, enabled).apply()
    }

    fun messagesPerThread(): Int =
        prefs.getInt(KEY_MESSAGES_PER_THREAD, DEFAULT_MESSAGES_PER_THREAD)
            .coerceIn(MIN_MESSAGES_PER_THREAD, MAX_MESSAGES_PER_THREAD)

    fun setMessagesPerThread(value: Int) {
        prefs.edit()
            .putInt(KEY_MESSAGES_PER_THREAD, value.coerceIn(MIN_MESSAGES_PER_THREAD, MAX_MESSAGES_PER_THREAD))
            .apply()
    }

    fun pauseWhilePhoneScreenOn(): Boolean =
        prefs.getBoolean(KEY_PAUSE_SCREEN_ON, DEFAULT_PAUSE_SCREEN_ON)

    fun setPauseWhilePhoneScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_SCREEN_ON, enabled).apply()
    }

    fun clearAfterReply(): Boolean =
        prefs.getBoolean(KEY_CLEAR_AFTER_REPLY, DEFAULT_CLEAR_AFTER_REPLY)

    fun setClearAfterReply(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLEAR_AFTER_REPLY, enabled).apply()
    }

    fun seenPackages(): Set<String> =
        prefs.getStringSet(KEY_SEEN_PACKAGES, emptySet()).orEmpty().toSet()

    /**
     * Apps the wearer silenced. It gets its own key rather than reusing the old
     * allowlist's: the two mean opposite things, and reading one as the other
     * would mute precisely the apps that had been let through.
     */
    fun blockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toSet()

    fun observeRepliablePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val updated = seenPackages() + packageName
        if (updated.size == seenPackages().size) return false
        prefs.edit().putStringSet(KEY_SEEN_PACKAGES, updated).apply()
        return true
    }

    fun isPackageAllowed(packageName: String): Boolean = packageName !in blockedPackages()

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        if (packageName.isBlank()) return
        val updated = blockedPackages().toMutableSet().apply {
            if (allowed) remove(packageName) else add(packageName)
        }
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, updated).apply()
    }

    fun admits(packageName: String): Boolean =
        NotificationAdmission.appIsAdmitted(enabled(), blockedPackages(), packageName)

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_IMAGE_PREVIEWS = false
        const val DEFAULT_MESSAGES_PER_THREAD = 20
        const val MIN_MESSAGES_PER_THREAD = 4
        const val MAX_MESSAGES_PER_THREAD = 40
        const val DEFAULT_PAUSE_SCREEN_ON = false
        const val DEFAULT_CLEAR_AFTER_REPLY = true

        private const val PREFS = "relay_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_IMAGE_PREVIEWS = "image_previews"
        private const val KEY_MESSAGES_PER_THREAD = "messages_per_thread"
        private const val KEY_PAUSE_SCREEN_ON = "pause_screen_on"
        private const val KEY_CLEAR_AFTER_REPLY = "clear_after_reply"
        private const val KEY_SEEN_PACKAGES = "seen_repliable_packages"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    }
}
