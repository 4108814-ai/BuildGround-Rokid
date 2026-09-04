from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
SHARED = ROOT / "shared/src/main/java/com/anezium/rokidbus/shared"
PHONE = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone"
GLASSES = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses"

# Reconstruct the exact current released baselines first.
runpy.run_path(str(ROOT / ".github/scripts/apply-meeting-audio-transport.py"), run_name="__main__")
runpy.run_path(str(ROOT / ".github/scripts/apply-nexus-glasses-1411-ui-sleep-call.py"), run_name="__main__")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:180]!r}; got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

# ---------------------------------------------------------------------------
# Shared private hub-to-hub call-state contract.
# ---------------------------------------------------------------------------
bus_constants = SHARED / "BusConstants.kt"
replace_once(
    bus_constants,
    '    /** Phone hub to glasses hub only; see [PhoneBatteryContract] for why it is not a plugin path. */\n    const val PHONE_BATTERY = "/phone/battery"\n',
    '    /** Phone hub to glasses hub only; never exposed as a plugin control path. */\n'
    '    const val PHONE_CALL_STATE = "/phone/call/state"\n\n'
    '    /** Phone hub to glasses hub only; see [PhoneBatteryContract] for why it is not a plugin path. */\n'
    '    const val PHONE_BATTERY = "/phone/battery"\n',
)

(SHARED / "PhoneCallStateContract.kt").write_text(
    '''package com.anezium.rokidbus.shared

import org.json.JSONObject

/** Authoritative paired-phone cellular call state, transported hub-to-hub only. */
object PhoneCallStateContract {
    const val IDLE = "idle"
    const val RINGING = "ringing"
    const val OFFHOOK = "offhook"

    fun toJson(state: String): JSONObject = JSONObject().put("state", normalize(state))

    fun fromJson(payload: JSONObject): String? {
        val raw = payload.optString("state").lowercase()
        return when (raw) {
            IDLE, RINGING, OFFHOOK -> raw
            else -> null
        }
    }

    fun isActive(state: String): Boolean = state == RINGING || state == OFFHOOK

    private fun normalize(state: String): String = when (state.lowercase()) {
        RINGING -> RINGING
        OFFHOOK -> OFFHOOK
        else -> IDLE
    }
}
''',
    encoding="utf-8",
)

# ---------------------------------------------------------------------------
# Phone: permission + TelephonyCallback + publication over existing bus.
# ---------------------------------------------------------------------------
manifest = ROOT / "phone-hub/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n',
    '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n'
    '    <uses-permission android:name="android.permission.READ_PHONE_STATE" />\n',
)

(PHONE / "PhoneCallStateBridge.kt").write_text(
    '''package com.anezium.rokidbus.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.anezium.rokidbus.shared.PhoneCallStateContract

/** Observes the actual phone call state. No caller identity or phone number is read or transported. */
internal class PhoneCallStateBridge(
    context: Context,
    private val onState: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private var callback: CallStateCallback? = null
    @Volatile private var state: String = PhoneCallStateContract.IDLE

    fun start(): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            state = PhoneCallStateContract.IDLE
            return false
        }
        if (callback != null) return true
        val listener = CallStateCallback(::accept)
        callback = listener
        telephony.registerTelephonyCallback(appContext.mainExecutor, listener)
        @Suppress("DEPRECATION")
        accept(mapState(telephony.callState), force = true)
        return true
    }

    fun stop() {
        val current = callback ?: return
        runCatching { telephony.unregisterTelephonyCallback(current) }
        callback = null
    }

    fun currentState(): String = state

    private fun accept(next: String, force: Boolean = false) {
        if (!force && next == state) return
        state = next
        onState(next)
    }

    private class CallStateCallback(
        private val sink: (String) -> Unit,
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            sink(mapState(state))
        }
    }

    companion object {
        private fun mapState(state: Int): String = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> PhoneCallStateContract.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> PhoneCallStateContract.OFFHOOK
            else -> PhoneCallStateContract.IDLE
        }
    }
}
''',
    encoding="utf-8",
)

main_activity = PHONE / "MainActivity.kt"
replace_once(
    main_activity,
    'private const val NOTIFICATION_PERMISSION_REQUEST = 22\nprivate const val PREF_NOTIFICATIONS_ANSWERED = "onboarding_notifications_answered"\n',
    'private const val NOTIFICATION_PERMISSION_REQUEST = 22\n'
    'private const val PHONE_STATE_PERMISSION_REQUEST = 24\n'
    'private const val PREF_NOTIFICATIONS_ANSWERED = "onboarding_notifications_answered"\n'
    'private const val PREF_PHONE_STATE_ANSWERED = "phone_call_state_permission_answered"\n',
)
replace_once(
    main_activity,
    '        super.onResume()\n        resumeRecoveredNexusUpdateInstall()\n',
    '        super.onResume()\n'
    '        ensurePhoneCallStatePermission()\n'
    '        resumeRecoveredNexusUpdateInstall()\n',
)
replace_once(
    main_activity,
    '        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {\n            recordNotificationsAnswered()\n            rebuildSetupSection()\n        }\n',
    '        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {\n'
    '            recordNotificationsAnswered()\n'
    '            rebuildSetupSection()\n'
    '        }\n'
    '        if (requestCode == PHONE_STATE_PERMISSION_REQUEST) {\n'
    '            getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)\n'
    '                .edit().putBoolean(PREF_PHONE_STATE_ANSWERED, true).apply()\n'
    '            BusHubService.refreshPhoneCallStatePermission(this)\n'
    '        }\n',
)
# Insert helper before buildUi, a stable early method marker.
replace_once(
    main_activity,
    '    private fun buildUi() {\n',
    '''    private fun ensurePhoneCallStatePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            BusHubService.refreshPhoneCallStatePermission(this)
            return
        }
        val answered = getSharedPreferences(NexusPhoneState.PREFS, MODE_PRIVATE)
            .getBoolean(PREF_PHONE_STATE_ANSWERED, false)
        if (!answered) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), PHONE_STATE_PERMISSION_REQUEST)
        }
    }

    private fun buildUi() {
''',
)

hub = PHONE / "BusHubService.kt"
replace_once(
    hub,
    '    private var remotePhoneCapabilities = PhoneHubCapabilities(0, null)\n',
    '    private var remotePhoneCapabilities = PhoneHubCapabilities(0, null)\n'
    '    private var phoneCallStateBridge: PhoneCallStateBridge? = null\n',
)
replace_once(
    hub,
    '        NexusPhoneState.restore(applicationContext)\n        activeInstance = this\n',
    '        NexusPhoneState.restore(applicationContext)\n'
    '        activeInstance = this\n'
    '        restartPhoneCallStateBridge()\n',
)
replace_once(
    hub,
    '    override fun onDestroy() {\n        stopPeriodicUpdateChecks()\n',
    '    override fun onDestroy() {\n'
    '        phoneCallStateBridge?.stop()\n'
    '        phoneCallStateBridge = null\n'
    '        stopPeriodicUpdateChecks()\n',
)
# Publish current state whenever glasses re-announce capabilities on a transport-up edge.
replace_once(
    hub,
    '            updateRemoteCapabilities(envelope.payload)\n            // The glasses re-announce on every transport-up, including after a hub restart that\n',
    '            updateRemoteCapabilities(envelope.payload)\n'
    '            publishPhoneCallState(phoneCallStateBridge?.currentState() ?: com.anezium.rokidbus.shared.PhoneCallStateContract.IDLE)\n'
    '            // The glasses re-announce on every transport-up, including after a hub restart that\n',
)
# Add service methods immediately before sendRemote.
replace_once(
    hub,
    '    private fun sendRemote(envelope: BusEnvelope): String? {\n',
    '''    private fun restartPhoneCallStateBridge() {
        phoneCallStateBridge?.stop()
        val bridge = PhoneCallStateBridge(applicationContext) { state ->
            publishPhoneCallState(state)
        }
        phoneCallStateBridge = bridge
        bridge.start()
    }

    private fun publishPhoneCallState(state: String) {
        val envelope = BusEnvelope(
            path = BusPaths.PHONE_CALL_STATE,
            payload = com.anezium.rokidbus.shared.PhoneCallStateContract.toJson(state),
        )
        val error = sendRemote(envelope)
        log("phone call state TX state=$state error=${error ?: "none"}")
    }

    private fun sendRemote(envelope: BusEnvelope): String? {
''',
)
# Public companion refresh after runtime permission grant.
replace_once(
    hub,
    '    companion object {\n        @Volatile private var activeInstance: BusHubService? = null\n',
    '''    companion object {
        @Volatile private var activeInstance: BusHubService? = null

        fun refreshPhoneCallStatePermission(context: Context) {
            val live = activeInstance
            if (live != null) {
                live.restartPhoneCallStateBridge()
            } else if (isEnabled(context)) {
                start(context)
            }
        }
''',
)

# ---------------------------------------------------------------------------
# Glasses: authoritative remote call gate. Existing 1.4.10/1.4.11 heuristics
# may observe the same UI but are no longer allowed to restore over this gate.
# ---------------------------------------------------------------------------
glasses_hub = GLASSES / "GlassesHub.kt"
replace_once(
    glasses_hub,
    '        if (envelope.path == BusPaths.HUB_CAPABILITIES) {\n            updateRemotePhoneCapabilities(envelope.payload)\n            return\n        }\n',
    '''        if (envelope.path == BusPaths.PHONE_CALL_STATE) {
            val state = com.anezium.rokidbus.shared.PhoneCallStateContract.fromJson(envelope.payload)
            if (state == null || envelope.binary != null) {
                log("phone call state ignored reason=INVALID_PAYLOAD")
                return
            }
            val dispatched = RokidBusAccessibilityService.onRemotePhoneCallState(state)
            log("phone call state RX state=$state serviceConnected=$dispatched")
            return
        }
        if (envelope.path == BusPaths.HUB_CAPABILITIES) {
            updateRemotePhoneCapabilities(envelope.payload)
            return
        }
''',
)

service = GLASSES / "RokidBusAccessibilityService.kt"
replace_once(
    service,
    '    private val launcherAutoRestoreSession = LauncherAutoRestoreSession()\n',
    '''    private val launcherAutoRestoreSession = LauncherAutoRestoreSession()
    private var remotePhoneCallActive = false
    private var remotePhoneCallYieldedLauncher = false
    private val restoreLauncherAfterRemotePhoneCall = Runnable {
        if (remotePhoneCallActive || !remotePhoneCallYieldedLauncher) return@Runnable
        remotePhoneCallYieldedLauncher = false
        val snapshot = SelfArmOnboardingStore.snapshot(applicationContext)
        val idle = !LauncherOverlayRenderer.isShown() &&
            SurfaceController.activeSurface() == null &&
            !ActivityController.isPresenting() &&
            NoticeController.visibleNotice() == null
        if (snapshot.coreReady && idle) {
            val shown = LauncherOverlayRenderer.show(this)
            if (shown || LauncherOverlayRenderer.isShown()) launcherAutoRestoreSession.markCompleted()
            log("Launcher restore after remote phone call: ${if (shown) "shown" else "show failed"}")
        } else {
            log("Launcher restore after remote phone call skipped: coreReady=${snapshot.coreReady} idle=$idle")
        }
    }
''',
)
# No auto-restore path may cover a remote active call.
replace_once(
    service,
    '            val shouldRestore = !nativeCallActive && LauncherAutoRestorePolicy.shouldRestore(\n',
    '            val shouldRestore = !remotePhoneCallActive && !nativeCallActive && LauncherAutoRestorePolicy.shouldRestore(\n',
)
replace_once(
    service,
    '        if (nativeCallActive || !callYieldedLauncher) return@Runnable\n',
    '        if (remotePhoneCallActive || nativeCallActive || !callYieldedLauncher) return@Runnable\n',
)
replace_once(
    service,
    '        if (nativeCallActive) {\n            main.removeCallbacks(tapExpiry)\n',
    '        if (remotePhoneCallActive || nativeCallActive) {\n            main.removeCallbacks(tapExpiry)\n',
)
replace_once(
    service,
    '        main.removeCallbacks(restoreLauncherAfterNativeCall)\n        main.removeCallbacks(nativeCallWindowExitCheck)\n',
    '        main.removeCallbacks(restoreLauncherAfterNativeCall)\n'
    '        main.removeCallbacks(restoreLauncherAfterRemotePhoneCall)\n'
    '        main.removeCallbacks(nativeCallWindowExitCheck)\n',
)
# Companion entry point from GlassesHub.
replace_once(
    service,
    '        /** True while the AccessibilityService is connected and able to drive Settings. */\n        internal fun isLive(): Boolean = liveInstance != null\n',
    '''        /** True while the AccessibilityService is connected and able to drive Settings. */
        internal fun isLive(): Boolean = liveInstance != null

        internal fun onRemotePhoneCallState(state: String): Boolean {
            val service = liveInstance ?: return false
            service.main.post {
                val active = com.anezium.rokidbus.shared.PhoneCallStateContract.isActive(state)
                if (active == service.remotePhoneCallActive) return@post
                service.remotePhoneCallActive = active
                if (active) {
                    service.main.removeCallbacks(service.restoreLauncherAfterNativeCall)
                    service.main.removeCallbacks(service.restoreLauncherAfterRemotePhoneCall)
                    val launcherShown = LauncherOverlayRenderer.isShown()
                    service.remotePhoneCallYieldedLauncher =
                        launcherShown || service.callYieldedLauncher
                    if (launcherShown) LauncherOverlayRenderer.hide()
                    log("Remote phone call priority entered state=$state yieldedLauncher=${service.remotePhoneCallYieldedLauncher}")
                } else {
                    service.nativeCallActive = false
                    service.callYieldedLauncher = false
                    service.main.removeCallbacks(service.nativeCallWindowExitCheck)
                    if (service.remotePhoneCallYieldedLauncher) {
                        service.main.removeCallbacks(service.restoreLauncherAfterRemotePhoneCall)
                        service.main.postDelayed(
                            service.restoreLauncherAfterRemotePhoneCall,
                            NATIVE_CALL_RETURN_DELAY_MS,
                        )
                    }
                    log("Remote phone call priority exited state=$state")
                }
            }
            return true
        }
''',
)

# Pure contract tests.
test_shared = ROOT / "shared/src/test/java/com/anezium/rokidbus/shared"
test_shared.mkdir(parents=True, exist_ok=True)
(test_shared / "PhoneCallStateContractTest.kt").write_text(
    '''package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallStateContractTest {
    @Test fun activeStatesAreRingingAndOffhook() {
        assertTrue(PhoneCallStateContract.isActive(PhoneCallStateContract.RINGING))
        assertTrue(PhoneCallStateContract.isActive(PhoneCallStateContract.OFFHOOK))
        assertFalse(PhoneCallStateContract.isActive(PhoneCallStateContract.IDLE))
    }
    @Test fun parserRejectsUnknownState() {
        assertNull(PhoneCallStateContract.fromJson(JSONObject().put("state", "mystery")))
    }
    @Test fun roundTripIdle() {
        assertEquals(PhoneCallStateContract.IDLE, PhoneCallStateContract.fromJson(PhoneCallStateContract.toJson("idle")))
    }
}
''',
    encoding="utf-8",
)

# Fail closed on the critical markers.
checks = {
    bus_constants: ['PHONE_CALL_STATE = "/phone/call/state"'],
    manifest: ['android.permission.READ_PHONE_STATE'],
    main_activity: ['PHONE_STATE_PERMISSION_REQUEST = 24', 'ensurePhoneCallStatePermission()', 'refreshPhoneCallStatePermission(this)'],
    hub: ['PhoneCallStateBridge(applicationContext)', 'BusPaths.PHONE_CALL_STATE', 'publishPhoneCallState'],
    glasses_hub: ['BusPaths.PHONE_CALL_STATE', 'onRemotePhoneCallState(state)'],
    service: ['remotePhoneCallActive', '!remotePhoneCallActive && !nativeCallActive', 'restoreLauncherAfterRemotePhoneCall'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"Missing call-state bridge marker in {path}: {marker}")
