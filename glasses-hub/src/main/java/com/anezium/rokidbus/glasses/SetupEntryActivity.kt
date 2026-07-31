package com.anezium.rokidbus.glasses

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The far end of "Start setup" on the phone.
 *
 * The phone cannot reach into Settings on the glasses, and at first run the bus may not be up yet,
 * so it starts this component over CXR instead. It draws nothing: it opens a setup session and
 * hands the wearer straight to the accessibility switch, which is the only thing they have to do.
 * Everything after that runs on its own.
 */
class SetupEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = applicationContext
        val snapshot = SelfArmOnboardingStore.snapshot(context)

        if (snapshot.accessibilityEnabled) {
            // Nothing to ask for. Show the wearer where setup is happening and let the service
            // pick it up from the state it observes.
            if (!SelfArmOnboardingStore.isSetupRequested(context)) {
                SelfArmOnboardingStore.beginSession(context)
            }
            SelfArmOnboardingStore.requestSetup(context)
            RokidBusAccessibilityService.requestWirelessBootstrap(context)
            openOnboarding()
            finish()
            return
        }

        val sessionId = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markAwaitingAccessibility(context)
        val landing = SelfArmAccessibilityHandoff.open(this)
        if (landing == SelfArmAccessibilityHandoff.Landing.UNAVAILABLE) {
            // Settings would not open at all: say so on the lens rather than leaving the phone
            // waiting on a step that can never arrive.
            SelfArmOnboardingStore.finish(
                context = context,
                sessionId = sessionId,
                setupState = "accessibility_settings_unavailable",
                success = false,
            )
            openOnboarding()
        }
        finish()
    }

    private fun openOnboarding() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
