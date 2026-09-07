#!/usr/bin/env python3
"""Keep the BuildGround Nexus glasses core alive after boot/lifecycle recovery."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"
MANIFEST = ROOT / "glasses-hub/src/main/AndroidManifest.xml"
BOOT = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/BootReceiver.kt"
LIFECYCLE = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesLifecycleReceiver.kt"
SERVICE = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/BusHubService.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Runs after the BuildGround 1.4.24 chain.
replace_once(GLASSES_GRADLE, "versionCode = 10424", "versionCode = 10425", "versionCode")
replace_once(GLASSES_GRADLE, 'versionName = "1.4.24"', 'versionName = "1.4.25"', "versionName")

replace_once(
    MANIFEST,
    '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n',
    '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n'
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n',
    "foreground service permission",
)

replace_once(
    BOOT,
    '''        val appContext = context.applicationContext\n        GlassesHub.start(appContext)''',
    '''        val appContext = context.applicationContext\n        // BootReceiver itself is transient. Keep the process owned by the existing hub service\n        // so the SPP/CXR core is not left running only on receiver-created threads.\n        runCatching { BusHubService.ensureRunning(appContext) }\n            .onFailure { error ->\n                log("BootReceiver foreground core start failed ${error.javaClass.simpleName}; using direct hub fallback")\n            }\n        GlassesHub.start(appContext)''',
    "boot persistent core",
)

replace_once(
    LIFECYCLE,
    '''        log("Glasses lifecycle resume action=${intent.action} reason=$reason")\n        GlassesHub.start(appContext)''',
    '''        log("Glasses lifecycle resume action=${intent.action} reason=$reason")\n        runCatching { BusHubService.ensureRunning(appContext) }\n            .onFailure { error ->\n                log("Glasses lifecycle foreground core start failed ${error.javaClass.simpleName}; using direct hub fallback")\n            }\n        GlassesHub.start(appContext)''',
    "lifecycle persistent core",
)

SERVICE.write_text(
    '''package com.anezium.rokidbus.glasses\n\nimport android.app.Notification\nimport android.app.NotificationChannel\nimport android.app.NotificationManager\nimport android.app.Service\nimport android.content.Context\nimport android.content.Intent\nimport android.os.IBinder\n\nclass BusHubService : Service() {\n    companion object {\n        private const val CHANNEL_ID = "nexus_core"\n        private const val NOTIFICATION_ID = 410825\n\n        fun ensureRunning(context: Context) {\n            val appContext = context.applicationContext\n            appContext.startForegroundService(Intent(appContext, BusHubService::class.java))\n        }\n    }\n\n    override fun onCreate() {\n        super.onCreate()\n        ensureForeground()\n        GlassesHub.start(applicationContext)\n        AccessibilityRearmWatcher.start(applicationContext, "bus_hub_service")\n    }\n\n    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        GlassesHub.start(applicationContext)\n        AccessibilityRearmWatcher.start(applicationContext, "bus_hub_service_start")\n        return START_STICKY\n    }\n\n    override fun onBind(intent: Intent?): IBinder {\n        GlassesHub.start(applicationContext)\n        return GlassesHub.binder(applicationContext)\n    }\n\n    private fun ensureForeground() {\n        val manager = getSystemService(NotificationManager::class.java)\n        manager.createNotificationChannel(\n            NotificationChannel(\n                CHANNEL_ID,\n                "NEXUS core",\n                NotificationManager.IMPORTANCE_MIN,\n            ).apply {\n                description = "Keeps the NEXUS glasses transport available after reboot"\n                setShowBadge(false)\n            },\n        )\n        val notification = Notification.Builder(this, CHANNEL_ID)\n            .setSmallIcon(applicationInfo.icon)\n            .setContentTitle("NEXUS")\n            .setContentText("Glasses core active")\n            .setOngoing(true)\n            .setShowWhen(false)\n            .build()\n        startForeground(NOTIFICATION_ID, notification)\n    }\n}\n''',
    encoding="utf-8",
)

# Guard the generated build. Keep the already-physical-tested 1.4.23 viewport and 1.4.24 wake logic.
gradle = GLASSES_GRADLE.read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")
boot = BOOT.read_text(encoding="utf-8")
lifecycle = LIFECYCLE.read_text(encoding="utf-8")
service = SERVICE.read_text(encoding="utf-8")

for required in ('versionCode = 10425', 'versionName = "1.4.25"'):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.25 version marker: {required}")
for required in (
    'android.permission.FOREGROUND_SERVICE',
    'android.intent.action.BOOT_COMPLETED',
    'android.intent.action.USER_UNLOCKED',
):
    if required not in manifest:
        raise SystemExit(f"Missing autostart manifest marker: {required}")
for text, label in (
    (boot, "boot"),
    (lifecycle, "lifecycle"),
):
    if 'BusHubService.ensureRunning(appContext)' not in text:
        raise SystemExit(f"Missing persistent core start in {label}")
for required in (
    'startForegroundService',
    'startForeground(NOTIFICATION_ID, notification)',
    'return START_STICKY',
):
    if required not in service:
        raise SystemExit(f"Missing foreground owner marker: {required}")

print("Applied BuildGround Nexus Glasses 1.4.25 persistent autostart patch.")
