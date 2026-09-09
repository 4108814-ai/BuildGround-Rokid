#!/usr/bin/env python3
"""Add direct-boot-safe boot ownership for BuildGround Nexus Glasses 1.4.27."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "glasses-hub/build.gradle.kts"
MANIFEST = ROOT / "glasses-hub/src/main/AndroidManifest.xml"
BOOT = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/BootReceiver.kt"
SERVICE = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/BusHubService.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(GRADLE, "versionCode = 10426", "versionCode = 10427", "versionCode")
replace_once(GRADLE, 'versionName = "1.4.26"', 'versionName = "1.4.27"', "versionName")

replace_once(
    MANIFEST,
    '''        <service\n            android:name=".BusHubService"\n            android:exported="true">''',
    '''        <service\n            android:name=".BusHubService"\n            android:directBootAware="true"\n            android:exported="true">''',
    "BusHubService directBootAware",
)

replace_once(
    MANIFEST,
    '''        <receiver\n            android:name=".BootReceiver"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="android.intent.action.BOOT_COMPLETED" />''',
    '''        <receiver\n            android:name=".BootReceiver"\n            android:directBootAware="true"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />\n                <action android:name="android.intent.action.BOOT_COMPLETED" />''',
    "BootReceiver direct boot filter",
)

# 1.4.26 starts GlassesHub directly from BootReceiver. During Direct Boot that may touch
# credential-protected state before Android has unlocked the user. Start only the direct-boot-aware
# foreground owner here; it will wait for UserManager.isUserUnlocked before starting GlassesHub.
replace_once(
    BOOT,
    '''        GlassesHub.start(appContext)\n        // An APK self-update strips our accessibility service from the secure setting''',
    '''        // BusHubService is direct-boot-aware and owns the process from LOCKED_BOOT_COMPLETED.\n        // It deliberately waits for UserManager.isUserUnlocked before touching GlassesHub state.\n        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {\n            GlassesHub.start(appContext)\n        }\n        // An APK self-update strips our accessibility service from the secure setting''',
    "defer direct GlassesHub start during locked boot",
)

replace_once(
    BOOT,
    '''        val reason = when (intent.action) {\n            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"''',
    '''        val reason = when (intent.action) {\n            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked_boot_completed"\n            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"''',
    "locked boot reason",
)

replace_once(
    BOOT,
    '''        if (reason != null) {\n            val pendingResult = goAsync()''',
    '''        if (reason != null && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {\n            val pendingResult = goAsync()''',
    "skip credential watchdog during locked boot",
)

SERVICE.write_text(
    '''package com.anezium.rokidbus.glasses\n\nimport android.app.Notification\nimport android.app.NotificationChannel\nimport android.app.NotificationManager\nimport android.app.Service\nimport android.content.Context\nimport android.content.Intent\nimport android.os.Handler\nimport android.os.IBinder\nimport android.os.Looper\nimport android.os.UserManager\n\nclass BusHubService : Service() {\n    companion object {\n        private const val CHANNEL_ID = "nexus_core"\n        private const val NOTIFICATION_ID = 410825\n        private const val UNLOCK_RETRY_MS = 5_000L\n\n        fun ensureRunning(context: Context) {\n            val appContext = context.applicationContext\n            appContext.startForegroundService(Intent(appContext, BusHubService::class.java))\n        }\n    }\n\n    private val mainHandler = Handler(Looper.getMainLooper())\n    private val unlockRetry = object : Runnable {\n        override fun run() {\n            if (isUserUnlocked()) {\n                startCore("unlock_retry")\n            } else {\n                mainHandler.postDelayed(this, UNLOCK_RETRY_MS)\n            }\n        }\n    }\n\n    override fun onCreate() {\n        super.onCreate()\n        ensureForeground()\n        startWhenUnlocked("service_create")\n    }\n\n    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        startWhenUnlocked("service_start")\n        return START_STICKY\n    }\n\n    override fun onBind(intent: Intent?): IBinder {\n        if (isUserUnlocked()) {\n            GlassesHub.start(applicationContext)\n        }\n        return GlassesHub.binder(applicationContext)\n    }\n\n    override fun onDestroy() {\n        mainHandler.removeCallbacks(unlockRetry)\n        super.onDestroy()\n    }\n\n    private fun startWhenUnlocked(reason: String) {\n        mainHandler.removeCallbacks(unlockRetry)\n        if (isUserUnlocked()) {\n            startCore(reason)\n        } else {\n            log("BusHubService waiting for Android user unlock reason=$reason")\n            mainHandler.postDelayed(unlockRetry, UNLOCK_RETRY_MS)\n        }\n    }\n\n    private fun startCore(reason: String) {\n        mainHandler.removeCallbacks(unlockRetry)\n        log("BusHubService starting glasses core reason=$reason")\n        GlassesHub.start(applicationContext)\n        AccessibilityRearmWatcher.start(applicationContext, "bus_hub_$reason")\n    }\n\n    private fun isUserUnlocked(): Boolean {\n        val userManager = getSystemService(UserManager::class.java)\n        return userManager?.isUserUnlocked != false\n    }\n\n    private fun ensureForeground() {\n        val manager = getSystemService(NotificationManager::class.java)\n        manager.createNotificationChannel(\n            NotificationChannel(\n                CHANNEL_ID,\n                "NEXUS core",\n                NotificationManager.IMPORTANCE_MIN,\n            ).apply {\n                description = "Keeps the NEXUS glasses transport available after reboot"\n                setShowBadge(false)\n            },\n        )\n        val notification = Notification.Builder(this, CHANNEL_ID)\n            .setSmallIcon(applicationInfo.icon)\n            .setContentTitle("NEXUS")\n            .setContentText("Glasses core active")\n            .setOngoing(true)\n            .setShowWhen(false)\n            .build()\n        startForeground(NOTIFICATION_ID, notification)\n    }\n}\n''',
    encoding="utf-8",
)

# Guard the generated build.
gradle = GRADLE.read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")
boot = BOOT.read_text(encoding="utf-8")
service = SERVICE.read_text(encoding="utf-8")

for required in ('versionCode = 10427', 'versionName = "1.4.27"'):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.27 marker: {required}")
for required in (
    'android.intent.action.LOCKED_BOOT_COMPLETED',
    'android:directBootAware="true"',
):
    if required not in manifest:
        raise SystemExit(f"Missing direct-boot manifest marker: {required}")
for required in (
    'Intent.ACTION_LOCKED_BOOT_COMPLETED',
    'intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED',
):
    if required not in boot:
        raise SystemExit(f"Missing direct-boot receiver marker: {required}")
for required in (
    'UserManager::class.java',
    'isUserUnlocked()',
    'UNLOCK_RETRY_MS = 5_000L',
    'return START_STICKY',
    'startForeground(NOTIFICATION_ID, notification)',
):
    if required not in service:
        raise SystemExit(f"Missing boot keeper service marker: {required}")

print("Applied BuildGround Nexus Glasses 1.4.27 direct-boot keeper patch.")
