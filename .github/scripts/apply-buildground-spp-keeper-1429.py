#!/usr/bin/env python3
"""Make Nexus Glasses SPP recovery independent of manually opening the launcher."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "glasses-hub/build.gradle.kts"
MANIFEST = ROOT / "glasses-hub/src/main/AndroidManifest.xml"
LIFECYCLE = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesLifecycleReceiver.kt"
ACCESSIBILITY = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt"
IME = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NexusRemoteInputMethodService.kt"
MAIN = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/MainActivity.kt"
WAKE_RECEIVER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/TransportWakeReceiver.kt"
WAKE_TEST = ROOT / "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/TransportWakeSignalTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Runs after the complete 1.4.28 Direct Boot Keeper chain.
replace_once(GRADLE, "versionCode = 10428", "versionCode = 10429", "versionCode")
replace_once(GRADLE, 'versionName = "1.4.28"', 'versionName = "1.4.29"', "versionName")

# Make task removal semantics explicit. The foreground core is not tied to the visible Nexus task.
replace_once(
    MANIFEST,
    '''        <service\n            android:name=".BusHubService"\n            android:directBootAware="true"\n            android:exported="true">''',
    '''        <service\n            android:name=".BusHubService"\n            android:directBootAware="true"\n            android:exported="true"\n            android:stopWithTask="false">''',
    "BusHubService stopWithTask",
)

# A Bluetooth reconnect is the transport event we actually need. It gives Nexus a headless
# process-entry edge when the phone/glasses link comes back, without opening any UI. USER_PRESENT
# is an additional post-sleep edge on Rokid firmware. These only ensure the existing foreground
# owner; they do not alter Hi Rokid, native Assistant, Wi-Fi or VPN routing.
replace_once(
    MANIFEST,
    '''        <receiver\n            android:name=".CameraOverlayVisibilityReceiver"\n            android:exported="false" />''',
    '''        <receiver\n            android:name=".TransportWakeReceiver"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />\n                <action android:name="android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED" />\n                <action android:name="android.bluetooth.adapter.action.STATE_CHANGED" />\n                <action android:name="android.intent.action.USER_PRESENT" />\n            </intent-filter>\n        </receiver>\n\n        <receiver\n            android:name=".CameraOverlayVisibilityReceiver"\n            android:exported="false" />''',
    "transport wake receiver manifest",
)

WAKE_RECEIVER.write_text(
    '''package com.anezium.rokidbus.glasses\n\nimport android.bluetooth.BluetoothAdapter\nimport android.bluetooth.BluetoothDevice\nimport android.content.BroadcastReceiver\nimport android.content.Context\nimport android.content.Intent\n\n/**\n * Re-establishes the headless Nexus owner when the glasses transport wakes.\n *\n * Physical trigger for this keeper: after link loss/reboot the phone showed CXR-L Up but SPP\n * Down; manually opening Nexus on the glasses immediately made SPP Up. This receiver supplies\n * the missing headless entry edge from Bluetooth/user-wake events and never opens an Activity.\n */\nclass TransportWakeReceiver : BroadcastReceiver() {\n    override fun onReceive(context: Context, intent: Intent) {\n        val reason = TransportWakeSignal.reason(\n            action = intent.action,\n            adapterState = if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {\n                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)\n            } else {\n                null\n            },\n            connectionState = if (intent.action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) {\n                intent.getIntExtra(\n                    BluetoothAdapter.EXTRA_CONNECTION_STATE,\n                    BluetoothAdapter.STATE_DISCONNECTED,\n                )\n            } else {\n                null\n            },\n        ) ?: return\n\n        val appContext = context.applicationContext\n        log("Transport wake reason=$reason; ensuring Nexus foreground core")\n        runCatching { BusHubService.ensureRunning(appContext) }\n            .onFailure { error ->\n                // Target/Rokid builds can reject a background FGS start during a narrow firmware\n                // transition. Starting the existing hub directly is a best-effort fallback; the\n                // next system-owned Accessibility/IME/lifecycle edge will establish FGS ownership.\n                log("Transport wake foreground start failed ${error.javaClass.simpleName}; direct hub fallback")\n                GlassesHub.start(appContext)\n            }\n    }\n}\n\ninternal object TransportWakeSignal {\n    fun reason(\n        action: String?,\n        adapterState: Int? = null,\n        connectionState: Int? = null,\n    ): String? = when (action) {\n        BluetoothDevice.ACTION_ACL_CONNECTED -> "bluetooth_acl_connected"\n        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED ->\n            if (connectionState == BluetoothAdapter.STATE_CONNECTED) "bluetooth_adapter_connected" else null\n        BluetoothAdapter.ACTION_STATE_CHANGED ->\n            if (adapterState == BluetoothAdapter.STATE_ON) "bluetooth_adapter_on" else null\n        Intent.ACTION_USER_PRESENT -> "user_present"\n        else -> null\n    }\n}\n''',
    encoding="utf-8",
)

# Rokid wear/temple broadcasts are valuable even when the vendor state extra is missing or uses a
# value we have not seen. Any broadcast from these two transport lifecycle actions now reasserts
# the headless core; only the known "resume" state proceeds into accessibility repair.
old_lifecycle = '''        val reason = GlassesLifecycleSignal.resumeReason(intent.action, state)\n        if (reason == null) {\n            log("Glasses lifecycle ignored action=${intent.action} state=${state ?: "none"}")\n            return\n        }\n\n        val appContext = context.applicationContext\n        log("Glasses lifecycle resume action=${intent.action} reason=$reason")\n        runCatching { BusHubService.ensureRunning(appContext) }\n            .onFailure { error ->\n                log("Glasses lifecycle foreground core start failed ${error.javaClass.simpleName}; using direct hub fallback")\n            }\n        GlassesHub.start(appContext)\n\n        val pendingResult = goAsync()'''
new_lifecycle = '''        val reason = GlassesLifecycleSignal.resumeReason(intent.action, state)\n        if (intent.action != GlassesLifecycleSignal.ACTION_LEG_STATUS &&\n            intent.action != GlassesLifecycleSignal.ACTION_TAKE_STATUS\n        ) {\n            return\n        }\n\n        val appContext = context.applicationContext\n        log("Glasses lifecycle transport edge action=${intent.action} state=${state ?: "none"} reason=${reason ?: "transport_edge"}")\n        runCatching { BusHubService.ensureRunning(appContext) }\n            .onFailure { error ->\n                log("Glasses lifecycle foreground core start failed ${error.javaClass.simpleName}; using direct hub fallback")\n            }\n        GlassesHub.start(appContext)\n\n        if (reason == null) return\n        val pendingResult = goAsync()'''
replace_once(LIFECYCLE, old_lifecycle, new_lifecycle, "lifecycle all-edge core recovery")

# Android itself owns Accessibility and IME lifecycles. Whenever either system service is created,
# convert that system-managed process entry into durable BusHubService ownership before starting
# the singleton hub. This removes the visible MainActivity as the only reliable recovery path.
replace_once(
    ACCESSIBILITY,
    '''        StatusBadgeOverlayRenderer.onServiceConnected(this)\n        GlassesHub.start(applicationContext)\n        displayStandbyWatchdog.start()''',
    '''        StatusBadgeOverlayRenderer.onServiceConnected(this)\n        runCatching { BusHubService.ensureRunning(applicationContext) }\n            .onFailure { error ->\n                log("Accessibility foreground core start failed ${error.javaClass.simpleName}; direct hub fallback")\n            }\n        GlassesHub.start(applicationContext)\n        displayStandbyWatchdog.start()''',
    "accessibility persistent core anchor",
)

replace_once(
    IME,
    '''    override fun onCreate() {\n        super.onCreate()\n        GlassesHub.start(applicationContext)\n    }''',
    '''    override fun onCreate() {\n        super.onCreate()\n        runCatching { BusHubService.ensureRunning(applicationContext) }\n            .onFailure { error ->\n                log("Remote IME foreground core start failed ${error.javaClass.simpleName}; direct hub fallback")\n            }\n        GlassesHub.start(applicationContext)\n    }''',
    "IME persistent core anchor",
)

replace_once(
    MAIN,
    '''        buildUi()\n        requestBluetoothConnectIfNeeded()\n        GlassesHub.start(applicationContext)''',
    '''        buildUi()\n        requestBluetoothConnectIfNeeded()\n        // The launcher remains a recovery edge, but no longer the special edge required for SPP.\n        runCatching { BusHubService.ensureRunning(applicationContext) }\n            .onFailure { error ->\n                log("Launcher foreground core start failed ${error.javaClass.simpleName}; direct hub fallback")\n            }\n        GlassesHub.start(applicationContext)''',
    "launcher persistent core anchor",
)

WAKE_TEST.parent.mkdir(parents=True, exist_ok=True)
WAKE_TEST.write_text(
    '''package com.anezium.rokidbus.glasses\n\nimport android.bluetooth.BluetoothAdapter\nimport android.bluetooth.BluetoothDevice\nimport android.content.Intent\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Test\n\nclass TransportWakeSignalTest {\n    @Test\n    fun `acl connected always wakes core`() {\n        assertEquals(\n            "bluetooth_acl_connected",\n            TransportWakeSignal.reason(BluetoothDevice.ACTION_ACL_CONNECTED),\n        )\n    }\n\n    @Test\n    fun `adapter state only wakes when on`() {\n        assertEquals(\n            "bluetooth_adapter_on",\n            TransportWakeSignal.reason(\n                BluetoothAdapter.ACTION_STATE_CHANGED,\n                adapterState = BluetoothAdapter.STATE_ON,\n            ),\n        )\n        assertNull(\n            TransportWakeSignal.reason(\n                BluetoothAdapter.ACTION_STATE_CHANGED,\n                adapterState = BluetoothAdapter.STATE_OFF,\n            ),\n        )\n    }\n\n    @Test\n    fun `adapter connection only wakes when connected`() {\n        assertEquals(\n            "bluetooth_adapter_connected",\n            TransportWakeSignal.reason(\n                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,\n                connectionState = BluetoothAdapter.STATE_CONNECTED,\n            ),\n        )\n        assertNull(\n            TransportWakeSignal.reason(\n                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,\n                connectionState = BluetoothAdapter.STATE_DISCONNECTED,\n            ),\n        )\n    }\n\n    @Test\n    fun `user present is a post sleep recovery edge`() {\n        assertEquals("user_present", TransportWakeSignal.reason(Intent.ACTION_USER_PRESENT))\n    }\n}\n''',
    encoding="utf-8",
)

# Generator guard: this release changes only the glasses application and its release machinery.
gradle = GRADLE.read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")
lifecycle = LIFECYCLE.read_text(encoding="utf-8")
accessibility = ACCESSIBILITY.read_text(encoding="utf-8")
ime = IME.read_text(encoding="utf-8")
main = MAIN.read_text(encoding="utf-8")
wake = WAKE_RECEIVER.read_text(encoding="utf-8")

for required in ('versionCode = 10429', 'versionName = "1.4.29"'):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.29 version marker: {required}")
for required in (
    'android:name=".TransportWakeReceiver"',
    'android.bluetooth.device.action.ACL_CONNECTED',
    'android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED',
    'android.intent.action.USER_PRESENT',
    'android:stopWithTask="false"',
    'android.intent.action.LOCKED_BOOT_COMPLETED',
):
    if required not in manifest:
        raise SystemExit(f"Missing SPP keeper manifest marker: {required}")
for text, label in (
    (lifecycle, "lifecycle"),
    (accessibility, "accessibility"),
    (ime, "ime"),
    (main, "launcher"),
):
    if 'BusHubService.ensureRunning' not in text:
        raise SystemExit(f"Missing durable core anchor in {label}")
for required in (
    'BluetoothDevice.ACTION_ACL_CONNECTED',
    'BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED',
    'BluetoothAdapter.ACTION_STATE_CHANGED',
    'Intent.ACTION_USER_PRESENT',
    'BusHubService.ensureRunning(appContext)',
):
    if required not in wake:
        raise SystemExit(f"Missing transport wake marker: {required}")

print("Applied BuildGround Nexus Glasses 1.4.29 SPP wake keeper patch.")
