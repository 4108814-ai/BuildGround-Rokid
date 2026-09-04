from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses"
TEST = ROOT / "glasses-hub/src/test/java/com/anezium/rokidbus/glasses"
SERVICE = SRC / "RokidBusAccessibilityService.kt"

# Build 1.4.8 strictly on top of the released 1.4.7 launcher-restore patch.
runpy.run_path(str(ROOT / ".github/scripts/apply-nexus-glasses-147-launcher-restore.py"), run_name="__main__")


def replace_once(old: str, new: str) -> None:
    text = SERVICE.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one glasses service match, found {count}: {old[:180]!r}")
    SERVICE.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "import android.content.Intent\nimport android.net.ConnectivityManager\n",
    "import android.content.Intent\nimport android.media.AudioManager\nimport android.net.ConnectivityManager\n",
)

replace_once(
    "    private var launcherAutoRestoreAttempted = false\n",
    "    private var launcherAutoRestoreAttempted = false\n"
    "    private var nativeCallActive = false\n"
    "    private var callYieldedLauncher = false\n"
    "    private var callAudioManager: AudioManager? = null\n"
    "    private var callModeListener: AudioManager.OnModeChangedListener? = null\n"
    "    private val restoreLauncherAfterNativeCall = Runnable {\n"
    "        if (nativeCallActive || !callYieldedLauncher) return@Runnable\n"
    "        callYieldedLauncher = false\n"
    "        val setupComplete = SelfArmOnboardingStateMachine\n"
    "            .evaluate(SelfArmOnboardingStore.snapshot(applicationContext))\n"
    "            .stage == SelfArmOnboardingState.Stage.COMPLETE\n"
    "        val idle = !LauncherOverlayRenderer.isShown() &&\n"
    "            SurfaceController.activeSurface() == null &&\n"
    "            !ActivityController.isPresenting() &&\n"
    "            NoticeController.visibleNotice() == null\n"
    "        if (setupComplete && idle) {\n"
    "            val shown = LauncherOverlayRenderer.show(this)\n"
    "            log(\"Launcher restore after native call: ${if (shown) \"shown\" else \"show failed\"}\")\n"
    "        } else {\n"
    "            log(\"Launcher restore after native call skipped: setupComplete=$setupComplete idle=$idle\")\n"
    "        }\n"
    "    }\n",
)

replace_once(
    "        LauncherOverlayRenderer.onServiceConnected(this)\n        StatusBadgeOverlayRenderer.onServiceConnected(this)\n",
    "        LauncherOverlayRenderer.onServiceConnected(this)\n"
    "        registerNativeCallModeObserver()\n"
    "        StatusBadgeOverlayRenderer.onServiceConnected(this)\n",
)

# A service recreation during a live call must not immediately cover the native call UI again.
replace_once(
    "                val shouldRestore = LauncherAutoRestorePolicy.shouldRestore(\n",
    "                val shouldRestore = !nativeCallActive && LauncherAutoRestorePolicy.shouldRestore(\n",
)

replace_once(
    "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n",
    "    private fun registerNativeCallModeObserver() {\n"
    "        val manager = getSystemService(AudioManager::class.java) ?: return\n"
    "        callAudioManager = manager\n"
    "        val listener = AudioManager.OnModeChangedListener { mode ->\n"
    "            onNativeCallAudioModeChanged(mode)\n"
    "        }\n"
    "        callModeListener = listener\n"
    "        manager.addOnModeChangedListener(mainExecutor, listener)\n"
    "        onNativeCallAudioModeChanged(manager.mode)\n"
    "    }\n\n"
    "    private fun unregisterNativeCallModeObserver() {\n"
    "        main.removeCallbacks(restoreLauncherAfterNativeCall)\n"
    "        val listener = callModeListener\n"
    "        if (listener != null) {\n"
    "            runCatching { callAudioManager?.removeOnModeChangedListener(listener) }\n"
    "        }\n"
    "        callModeListener = null\n"
    "        callAudioManager = null\n"
    "        nativeCallActive = false\n"
    "        callYieldedLauncher = false\n"
    "    }\n\n"
    "    private fun onNativeCallAudioModeChanged(mode: Int) {\n"
    "        val active = NativeCallUiPolicy.isCallMode(mode)\n"
    "        if (active == nativeCallActive) return\n"
    "        nativeCallActive = active\n"
    "        if (active) {\n"
    "            main.removeCallbacks(restoreLauncherAfterNativeCall)\n"
    "            val launcherShown = LauncherOverlayRenderer.isShown()\n"
    "            val setupComplete = SelfArmOnboardingStateMachine\n"
    "                .evaluate(SelfArmOnboardingStore.snapshot(applicationContext))\n"
    "                .stage == SelfArmOnboardingState.Stage.COMPLETE\n"
    "            // Before the one-shot startup restore has run, a live call is the only reason\n"
    "            // Nexus is not visible yet. Remember that it should return when the call ends.\n"
    "            callYieldedLauncher = launcherShown || (!launcherAutoRestoreAttempted && setupComplete)\n"
    "            if (launcherShown) LauncherOverlayRenderer.hide()\n"
    "            log(\"Native call UI priority entered audioMode=$mode yieldedLauncher=$callYieldedLauncher\")\n"
    "            return\n"
    "        }\n"
    "        if (callYieldedLauncher) {\n"
    "            main.removeCallbacks(restoreLauncherAfterNativeCall)\n"
    "            main.postDelayed(restoreLauncherAfterNativeCall, NATIVE_CALL_RETURN_DELAY_MS)\n"
    "            log(\"Native call UI priority exited audioMode=$mode; scheduling Nexus restore\")\n"
    "        }\n"
    "    }\n\n"
    "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n",
)

# While the native call UI owns the display, all touchpad/ring classifications pass through.
replace_once(
    "    override fun onKeyEvent(event: KeyEvent): Boolean {\n        displayStandbyWatchdog.noteKeyEvent(event)\n",
    "    override fun onKeyEvent(event: KeyEvent): Boolean {\n"
    "        displayStandbyWatchdog.noteKeyEvent(event)\n"
    "        if (nativeCallActive) {\n"
    "            main.removeCallbacks(tapExpiry)\n"
    "            consumedDownKeys.remove(event.keyCode)\n"
    "            return false\n"
    "        }\n",
)

replace_once(
    "        pauseManualNavigationIfActive(\"manual_pairing_service_restarting\")\n        wirelessDebuggingAutomator = null\n",
    "        pauseManualNavigationIfActive(\"manual_pairing_service_restarting\")\n"
    "        unregisterNativeCallModeObserver()\n"
    "        wirelessDebuggingAutomator = null\n",
)

replace_once(
    "        private const val NATIVE_ASSISTANT_BACK_DEBOUNCE_MS = 120L\n",
    "        private const val NATIVE_ASSISTANT_BACK_DEBOUNCE_MS = 120L\n"
    "        private const val NATIVE_CALL_RETURN_DELAY_MS = 450L\n",
)

(SRC / "NativeCallUiPolicy.kt").write_text(
    '''package com.anezium.rokidbus.glasses\n\nimport android.media.AudioManager\n\n/** Telephone-only audio modes that must temporarily reveal the native Rokid call UI. */\ninternal object NativeCallUiPolicy {\n    fun isCallMode(mode: Int): Boolean = mode == AudioManager.MODE_RINGTONE ||\n        mode == AudioManager.MODE_IN_CALL ||\n        mode == AudioManager.MODE_CALL_SCREENING ||\n        mode == AudioManager.MODE_CALL_REDIRECT\n}\n''',
    encoding="utf-8",
)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "NativeCallUiPolicyTest.kt").write_text(
    '''package com.anezium.rokidbus.glasses\n\nimport android.media.AudioManager\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass NativeCallUiPolicyTest {\n    @Test fun ringtoneYieldsToNativeCallUi() {\n        assertTrue(NativeCallUiPolicy.isCallMode(AudioManager.MODE_RINGTONE))\n    }\n\n    @Test fun inCallYieldsToNativeCallUi() {\n        assertTrue(NativeCallUiPolicy.isCallMode(AudioManager.MODE_IN_CALL))\n    }\n\n    @Test fun callScreeningYieldsToNativeCallUi() {\n        assertTrue(NativeCallUiPolicy.isCallMode(AudioManager.MODE_CALL_SCREENING))\n    }\n\n    @Test fun callRedirectYieldsToNativeCallUi() {\n        assertTrue(NativeCallUiPolicy.isCallMode(AudioManager.MODE_CALL_REDIRECT))\n    }\n\n    @Test fun ordinaryAudioDoesNotYield() {\n        assertFalse(NativeCallUiPolicy.isCallMode(AudioManager.MODE_NORMAL))\n    }\n\n    @Test fun assistantCommunicationModeDoesNotYield() {\n        assertFalse(NativeCallUiPolicy.isCallMode(AudioManager.MODE_IN_COMMUNICATION))\n    }\n}\n''',
    encoding="utf-8",
)

text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "registerNativeCallModeObserver()",
    "NativeCallUiPolicy.isCallMode(mode)",
    "Native call UI priority entered",
    "if (nativeCallActive) {",
    "unregisterNativeCallModeObserver()",
    "NATIVE_CALL_RETURN_DELAY_MS = 450L",
    "!nativeCallActive && LauncherAutoRestorePolicy.shouldRestore(",
):
    if marker not in text:
        raise SystemExit(f"Missing Nexus Glasses 1.4.8 call-yield marker: {marker}")
