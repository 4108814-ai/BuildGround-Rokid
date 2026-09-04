from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses"
TEST = ROOT / "glasses-hub/src/test/java/com/anezium/rokidbus/glasses"
SERVICE = SRC / "RokidBusAccessibilityService.kt"


def replace_once(old: str, new: str) -> None:
    text = SERVICE.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one glasses service match, found {count}: {old[:180]!r}")
    SERVICE.write_text(text.replace(old, new, 1), encoding="utf-8")


# Nexus is intentionally an Accessibility overlay rather than Android HOME. After an accessibility
# service/process recreation, restore that overlay once Nexus core prerequisites are actually ready.
# 1.4.7 incorrectly gated this on the stricter onboarding COMPLETE state, which additionally depends
# on the legacyAdbSafe maintenance marker and can remain false on an already working upgraded device.
# It also made only one attempt. 1.4.8 gates on snapshot.coreReady and retries briefly while the ROM
# launcher or another transient Nexus surface is still settling. Once the launcher has been shown,
# retries stop permanently for this service lifetime, so an explicit user exit remains respected.
replace_once(
    "    private var lastNativeAssistantBackAtMs = 0L\n",
    "    private var lastNativeAssistantBackAtMs = 0L\n"
    "    private var launcherAutoRestoreCompleted = false\n"
    "    private var launcherAutoRestoreGeneration = 0\n",
)

replace_once(
    "        if (isNativeAssistantDismissArmed()) {\n"
    "            scheduleNativeAssistantDismissChecks(\"service_connected\")\n"
    "        }\n"
    "    }\n\n"
    "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n",
    "        if (isNativeAssistantDismissArmed()) {\n"
    "            scheduleNativeAssistantDismissChecks(\"service_connected\")\n"
    "        }\n"
    "        launcherAutoRestoreGeneration += 1\n"
    "        scheduleLauncherAutoRestore(attempt = 0, generation = launcherAutoRestoreGeneration)\n"
    "    }\n\n"
    "    private fun scheduleLauncherAutoRestore(attempt: Int, generation: Int) {\n"
    "        if (launcherAutoRestoreCompleted || generation != launcherAutoRestoreGeneration) return\n"
    "        val delayMs = if (attempt == 0) 650L else LAUNCHER_AUTO_RESTORE_RETRY_MS\n"
    "        main.postDelayed({\n"
    "            if (liveInstance !== this || launcherAutoRestoreCompleted || generation != launcherAutoRestoreGeneration) {\n"
    "                return@postDelayed\n"
    "            }\n"
    "            val snapshot = SelfArmOnboardingStore.snapshot(applicationContext)\n"
    "            val launcherShown = LauncherOverlayRenderer.isShown()\n"
    "            val surfaceActive = SurfaceController.activeSurface() != null\n"
    "            val activityPresenting = ActivityController.isPresenting()\n"
    "            val noticeVisible = NoticeController.visibleNotice() != null\n"
    "            val shouldRestore = LauncherAutoRestorePolicy.shouldRestore(\n"
    "                coreReady = snapshot.coreReady,\n"
    "                launcherShown = launcherShown,\n"
    "                surfaceActive = surfaceActive,\n"
    "                activityPresenting = activityPresenting,\n"
    "                noticeVisible = noticeVisible,\n"
    "            )\n"
    "            if (launcherShown) {\n"
    "                launcherAutoRestoreCompleted = true\n"
    "                log(\"Launcher auto-restore already satisfied\")\n"
    "                return@postDelayed\n"
    "            }\n"
    "            if (shouldRestore) {\n"
    "                val shown = LauncherOverlayRenderer.show(this)\n"
    "                if (shown || LauncherOverlayRenderer.isShown()) {\n"
    "                    launcherAutoRestoreCompleted = true\n"
    "                    log(\"Launcher auto-restore after service connect: shown attempt=$attempt\")\n"
    "                    return@postDelayed\n"
    "                }\n"
    "                log(\"Launcher auto-restore show failed attempt=$attempt\")\n"
    "            } else {\n"
    "                log(\"Launcher auto-restore blocked attempt=$attempt coreReady=${snapshot.coreReady} surfaceActive=$surfaceActive activityPresenting=$activityPresenting noticeVisible=$noticeVisible\")\n"
    "            }\n"
    "            if (attempt < LAUNCHER_AUTO_RESTORE_MAX_RETRIES) {\n"
    "                scheduleLauncherAutoRestore(attempt + 1, generation)\n"
    "            } else {\n"
    "                log(\"Launcher auto-restore exhausted retries coreReady=${snapshot.coreReady}\")\n"
    "            }\n"
    "        }, delayMs)\n"
    "    }\n\n"
    "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n",
)

# Add constants inside the existing companion object using the current stable source marker.
replace_once(
    "    companion object {\n"
    "        private const val KEYCODE_PROG_BLUE = 186\n",
    "    companion object {\n"
    "        private const val KEYCODE_PROG_BLUE = 186\n"
    "        private const val LAUNCHER_AUTO_RESTORE_RETRY_MS = 1_000L\n"
    "        private const val LAUNCHER_AUTO_RESTORE_MAX_RETRIES = 12\n",
)

(SRC / "LauncherAutoRestorePolicy.kt").write_text(
    r'''package com.anezium.rokidbus.glasses

/** Pure guard for restoring Nexus as the primary visible shell after service/process recreation. */
internal object LauncherAutoRestorePolicy {
    fun shouldRestore(
        coreReady: Boolean,
        launcherShown: Boolean,
        surfaceActive: Boolean,
        activityPresenting: Boolean,
        noticeVisible: Boolean,
    ): Boolean =
        coreReady &&
            !launcherShown &&
            !surfaceActive &&
            !activityPresenting &&
            !noticeVisible
}
''',
    encoding="utf-8",
)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "LauncherAutoRestorePolicyTest.kt").write_text(
    r'''package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAutoRestorePolicyTest {
    @Test
    fun coreReadyIdleNexusRestoresLauncher() {
        assertTrue(shouldRestore())
    }

    @Test
    fun maintenanceMarkerIsNotPartOfLauncherReadiness() {
        // coreReady is deliberately the only setup input: a working upgraded install may not carry
        // the legacy maintenance marker that made onboarding Stage.COMPLETE in 1.4.7.
        assertTrue(shouldRestore(coreReady = true))
    }

    @Test
    fun coreNotReadyNeverGetsCovered() {
        assertFalse(shouldRestore(coreReady = false))
    }

    @Test
    fun existingLauncherIsNotReopened() {
        assertFalse(shouldRestore(launcherShown = true))
    }

    @Test
    fun activePluginSurfaceWins() {
        assertFalse(shouldRestore(surfaceActive = true))
    }

    @Test
    fun activeActivityWins() {
        assertFalse(shouldRestore(activityPresenting = true))
    }

    @Test
    fun visibleNoticeWins() {
        assertFalse(shouldRestore(noticeVisible = true))
    }

    private fun shouldRestore(
        coreReady: Boolean = true,
        launcherShown: Boolean = false,
        surfaceActive: Boolean = false,
        activityPresenting: Boolean = false,
        noticeVisible: Boolean = false,
    ): Boolean = LauncherAutoRestorePolicy.shouldRestore(
        coreReady = coreReady,
        launcherShown = launcherShown,
        surfaceActive = surfaceActive,
        activityPresenting = activityPresenting,
        noticeVisible = noticeVisible,
    )
}
''',
    encoding="utf-8",
)

text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "private var launcherAutoRestoreCompleted = false",
    "snapshot.coreReady",
    "LAUNCHER_AUTO_RESTORE_MAX_RETRIES",
    "scheduleLauncherAutoRestore(attempt + 1, generation)",
    "Launcher auto-restore exhausted retries",
):
    if marker not in text:
        raise SystemExit(f"Missing Nexus Glasses 1.4.8 launcher marker: {marker}")
