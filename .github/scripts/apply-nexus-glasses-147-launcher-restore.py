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


# Nexus used to be the wearer's primary visible shell. The launcher overlay itself is intentionally
# in-memory, so a ROM/accessibility service restart used to erase that foreground state and expose
# the native Rokid launcher underneath. Restore the Nexus shell once per service lifetime after a
# completed setup; a deliberate user exit remains respected until the next service/boot restart.
replace_once(
    "    private var lastNativeAssistantBackAtMs = 0L\n",
    "    private var lastNativeAssistantBackAtMs = 0L\n"
    "    private var launcherAutoRestoreAttempted = false\n",
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
    "        scheduleLauncherAutoRestore()\n"
    "    }\n\n"
    "    private fun scheduleLauncherAutoRestore() {\n"
    "        if (launcherAutoRestoreAttempted) return\n"
    "        launcherAutoRestoreAttempted = true\n"
    "        // Let the ROM finish bringing up its own launcher first. An Accessibility overlay\n"
    "        // shown after that point remains the stable Nexus foreground without replacing HOME.\n"
    "        main.postDelayed({\n"
    "            if (liveInstance === this) {\n"
    "                val setupComplete = SelfArmOnboardingStateMachine\n"
    "                    .evaluate(SelfArmOnboardingStore.snapshot(applicationContext))\n"
    "                    .stage == SelfArmOnboardingState.Stage.COMPLETE\n"
    "                val shouldRestore = LauncherAutoRestorePolicy.shouldRestore(\n"
    "                    setupComplete = setupComplete,\n"
    "                    launcherShown = LauncherOverlayRenderer.isShown(),\n"
    "                    surfaceActive = SurfaceController.activeSurface() != null,\n"
    "                    activityPresenting = ActivityController.isPresenting(),\n"
    "                    noticeVisible = NoticeController.visibleNotice() != null,\n"
    "                )\n"
    "                if (shouldRestore) {\n"
    "                    val shown = LauncherOverlayRenderer.show(this)\n"
    "                    log(\"Launcher auto-restore after service connect: ${if (shown) \"shown\" else \"show failed\"}\")\n"
    "                } else {\n"
    "                    log(\"Launcher auto-restore skipped: setupComplete=$setupComplete launcherShown=${LauncherOverlayRenderer.isShown()} surfaceActive=${SurfaceController.activeSurface() != null} activityPresenting=${ActivityController.isPresenting()} noticeVisible=${NoticeController.visibleNotice() != null}\")\n"
    "                }\n"
    "            }\n"
    "        }, 650L)\n"
    "    }\n\n"
    "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n",
)

(SRC / "LauncherAutoRestorePolicy.kt").write_text(
    r'''package com.anezium.rokidbus.glasses

/** Pure guard for restoring Nexus as the primary visible shell after service/process recreation. */
internal object LauncherAutoRestorePolicy {
    fun shouldRestore(
        setupComplete: Boolean,
        launcherShown: Boolean,
        surfaceActive: Boolean,
        activityPresenting: Boolean,
        noticeVisible: Boolean,
    ): Boolean =
        setupComplete &&
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
    fun completedIdleNexusRestoresLauncher() {
        assertTrue(shouldRestore())
    }

    @Test
    fun onboardingNeverGetsCovered() {
        assertFalse(shouldRestore(setupComplete = false))
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
        setupComplete: Boolean = true,
        launcherShown: Boolean = false,
        surfaceActive: Boolean = false,
        activityPresenting: Boolean = false,
        noticeVisible: Boolean = false,
    ): Boolean = LauncherAutoRestorePolicy.shouldRestore(
        setupComplete = setupComplete,
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
    "private var launcherAutoRestoreAttempted = false",
    "scheduleLauncherAutoRestore()",
    "LauncherAutoRestorePolicy.shouldRestore(",
    "}, 650L)",
    "Launcher auto-restore after service connect",
):
    if marker not in text:
        raise SystemExit(f"Missing Nexus Glasses 1.4.7 launcher-restore marker: {marker}")
