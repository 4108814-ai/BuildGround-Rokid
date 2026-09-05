package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        log("BootReceiver received ${intent.action}; asking glasses hub to start")
        val appContext = context.applicationContext
        GlassesHub.start(appContext)
        // An APK self-update strips our accessibility service from the secure setting and
        // force-stops the app, so nothing would re-arm it until the next manual launch. This
        // broadcast wakes us right after our own update; ensureWatchdog repairs accessibility
        // directly via WRITE_SECURE_SETTINGS, which needs neither ADB nor a reboot.
        //
        // Rokid's system services reliably use USER_UNLOCKED as their own post-boot start edge.
        // Treat it as a second, later recovery opportunity because BOOT_COMPLETED can arrive while
        // vendor services are still coming up (and some firmware builds suppress it for third-party
        // packages entirely).
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
            Intent.ACTION_USER_UNLOCKED -> "user_unlocked"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            else -> null
        }
        if (reason != null) {
            val pendingResult = goAsync()
            AccessibilityRearmWatcher.ensureWatchdog(appContext, reason) {
                pendingResult.finish()
            }
        }
    }
}
