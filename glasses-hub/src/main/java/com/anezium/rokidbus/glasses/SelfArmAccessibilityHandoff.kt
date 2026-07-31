package com.anezium.rokidbus.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Sends the wearer to the one switch Nexus is not allowed to flip for them.
 *
 * Enabling an accessibility service is, by design, something only a human can do from Settings.
 * All this does is land them as close to it as the firmware allows: the service's own detail page
 * when the ROM honours it, the plain Accessibility list when it does not. Never a coordinate, and
 * never the toggle itself.
 */
internal object SelfArmAccessibilityHandoff {
    /** Result of the hand-off, so callers can tell a precise landing from a rough one. */
    enum class Landing { DETAILS, LIST, UNAVAILABLE }

    fun open(context: Context): Landing {
        val details = detailsIntent()
        // Ask the package manager instead of inferring from Build.VERSION: the action is not in
        // the public SDK, so whether this ROM's Settings registered it is a fact about the
        // firmware, not about the platform level. Resolving first also stops us reporting a
        // precise landing for an intent that only threw once it was too late to tell the user.
        if (resolves(context, details) && start(context, details)) return Landing.DETAILS
        if (start(context, listIntent().setPackage(SETTINGS_PACKAGE))) return Landing.LIST
        if (start(context, listIntent())) return Landing.LIST
        return Landing.UNAVAILABLE
    }

    /**
     * The service's own page. Both the action and the extra are AOSP-internal — they exist on
     * Android 12+ builds but were never promoted to the public SDK — so they are spelled out here
     * and the caller always keeps the plain list as a fallback.
     */
    private fun detailsIntent(): Intent {
        val component = serviceComponent().flattenToString()
        return Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .setPackage(SETTINGS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME, component)
            // Some Settings builds route the detail page through SubSettings and read the
            // highlight key instead; harmless when the other extra is the one honoured.
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
    }

    private fun resolves(context: Context, intent: Intent): Boolean =
        runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)

    private fun listIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun serviceComponent(): ComponentName = ComponentName(
        SelfArmConstants.CLIENT_PACKAGE,
        "${SelfArmConstants.CLIENT_PACKAGE}.RokidBusAccessibilityService",
    )

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME =
        "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
}
