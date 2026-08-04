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

    fun noticeDisplaySeconds(): Int =
        coerceNoticeDisplaySeconds(
            prefs.getInt(KEY_NOTICE_DISPLAY_SECONDS, DEFAULT_NOTICE_DISPLAY_SECONDS),
        )

    fun setNoticeDisplaySeconds(value: Int) {
        prefs.edit()
            .putInt(KEY_NOTICE_DISPLAY_SECONDS, coerceNoticeDisplaySeconds(value))
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

    fun noticeBackdrop(): Boolean =
        prefs.getBoolean(KEY_NOTICE_BACKDROP, DEFAULT_NOTICE_BACKDROP)

    fun setNoticeBackdrop(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTICE_BACKDROP, enabled).apply()
    }

    fun readAloud(): Boolean =
        prefs.getBoolean(KEY_READ_ALOUD, DEFAULT_READ_ALOUD)

    fun setReadAloud(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READ_ALOUD, enabled).apply()
    }

    fun admits(): Boolean = NotificationAdmission.appIsAdmitted(enabled())

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_IMAGE_PREVIEWS = false
        const val DEFAULT_MESSAGES_PER_THREAD = 20
        const val MIN_MESSAGES_PER_THREAD = 4
        const val MAX_MESSAGES_PER_THREAD = 40
        const val DEFAULT_NOTICE_DISPLAY_SECONDS = 0
        const val MIN_NOTICE_DISPLAY_SECONDS = 5
        const val MAX_NOTICE_DISPLAY_SECONDS = 45
        const val STEP_NOTICE_DISPLAY_SECONDS = 5
        const val DEFAULT_PAUSE_SCREEN_ON = false
        const val DEFAULT_CLEAR_AFTER_REPLY = true
        const val DEFAULT_NOTICE_BACKDROP = false
        const val DEFAULT_READ_ALOUD = false

        fun coerceNoticeDisplaySeconds(value: Int): Int {
            if (value == DEFAULT_NOTICE_DISPLAY_SECONDS) return DEFAULT_NOTICE_DISPLAY_SECONDS
            val bounded = value.coerceIn(MIN_NOTICE_DISPLAY_SECONDS, MAX_NOTICE_DISPLAY_SECONDS)
            return ((bounded + STEP_NOTICE_DISPLAY_SECONDS / 2) / STEP_NOTICE_DISPLAY_SECONDS) *
                STEP_NOTICE_DISPLAY_SECONDS
        }

        fun stepNoticeDisplaySeconds(value: Int, steps: Int): Int {
            val currentIndex = coerceNoticeDisplaySeconds(value) / STEP_NOTICE_DISPLAY_SECONDS
            val maximumIndex = MAX_NOTICE_DISPLAY_SECONDS / STEP_NOTICE_DISPLAY_SECONDS
            val nextIndex = (currentIndex.toLong() + steps)
                .coerceIn(0L, maximumIndex.toLong())
            return nextIndex.toInt() * STEP_NOTICE_DISPLAY_SECONDS
        }

        private const val PREFS = "relay_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_IMAGE_PREVIEWS = "image_previews"
        private const val KEY_MESSAGES_PER_THREAD = "messages_per_thread"
        private const val KEY_NOTICE_DISPLAY_SECONDS = "notice_display_seconds"
        private const val KEY_PAUSE_SCREEN_ON = "pause_screen_on"
        private const val KEY_CLEAR_AFTER_REPLY = "clear_after_reply"
        private const val KEY_NOTICE_BACKDROP = "notice_backdrop"
        private const val KEY_READ_ALOUD = "read_aloud"
    }
}
