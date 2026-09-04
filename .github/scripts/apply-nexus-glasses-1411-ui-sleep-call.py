from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses"
TEST = ROOT / "glasses-hub/src/test/java/com/anezium/rokidbus/glasses"
SERVICE = SRC / "RokidBusAccessibilityService.kt"
LAUNCHER = SRC / "LauncherOverlayRenderer.kt"

# Build strictly on the exact released 1.4.10 chain (which already includes 1.4.9 wake ownership).
runpy.run_path(str(ROOT / ".github/scripts/apply-nexus-glasses-1410-call-yield.py"), run_name="__main__")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path.name}, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

# 1) Let Rokid's own display timeout work again while Nexus launcher is visible.
replace_once(
    LAUNCHER,
    """                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
""",
    """                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
""",
)

# 2) Make the launcher denser and ensure selection always scrolls into view after layout.
replace_once(LAUNCHER, "monoText(24f, BusTheme.text, bold = true)", "monoText(18f, BusTheme.text, bold = true)")
replace_once(LAUNCHER, "addView(gap(18))", "addView(gap(10))")
replace_once(LAUNCHER, "addView(gap(10))\n            addView(scroll", "addView(gap(6))\n            addView(scroll")
replace_once(LAUNCHER, "dp(24), dp(24)", "dp(18), dp(18)")
replace_once(LAUNCHER, "marginEnd = dp(14)", "marginEnd = dp(10)")
replace_once(LAUNCHER, "monoText(18f, if (selected)", "monoText(14f, if (selected)")
replace_once(LAUNCHER, "minimumHeight = dp(52)", "minimumHeight = dp(38)")
replace_once(LAUNCHER, "setPadding(dp(12), 0, dp(12), 0)", "setPadding(dp(9), 0, dp(9), 0)")
replace_once(LAUNCHER, "topMargin = if (index == 0) 0 else dp(8)", "topMargin = if (index == 0) 0 else dp(5)")
replace_once(
    LAUNCHER,
    """            selectedRow?.let { row ->
                row.post {
                    row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
                }
            }
""",
    """            selectedRow?.let { row ->
                scroll.post {
                    val rowTop = row.top
                    val rowBottom = row.bottom
                    val viewportTop = scroll.scrollY
                    val viewportBottom = viewportTop + scroll.height
                    when {
                        rowTop < viewportTop -> scroll.smoothScrollTo(0, rowTop.coerceAtLeast(0))
                        rowBottom > viewportBottom -> scroll.smoothScrollTo(0, (rowBottom - scroll.height).coerceAtLeast(0))
                    }
                    row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
                }
            }
""",
)

# 3) AudioManager modes do not change on RV101 for paired-phone calls. Use Accessibility window
# events as the primary signal: native Rokid system/assist/launcher windows with phone/call/telecom
# class/package markers temporarily take priority over the Nexus overlay.
replace_once(
    SERVICE,
    """    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
""",
    """    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val packageName = event.packageName?.toString().orEmpty()
            val className = event.className?.toString().orEmpty()
            val windowCall = NativeRokidCallWindowPolicy.isCallWindow(packageName, className)
            if (windowCall) {
                onNativeCallWindowDetected(packageName, className)
            } else if (nativeCallActive && NativeRokidCallWindowPolicy.isRokidSystemWindow(packageName)) {
                scheduleNativeCallWindowExitCheck()
            }
        }
""",
)

replace_once(
    SERVICE,
    """    private fun registerNativeCallModeObserver() {
""",
    """    private fun onNativeCallWindowDetected(packageName: String, className: String) {
        main.removeCallbacks(nativeCallWindowExitCheck)
        if (!nativeCallActive) {
            nativeCallActive = true
            val launcherShown = LauncherOverlayRenderer.isShown()
            callYieldedLauncher = launcherShown
            if (launcherShown) LauncherOverlayRenderer.hide()
        }
        log("Native Rokid call window detected package=$packageName class=$className yieldedLauncher=$callYieldedLauncher")
    }

    private val nativeCallWindowExitCheck = Runnable {
        if (!nativeCallActive) return@Runnable
        nativeCallActive = false
        if (callYieldedLauncher) {
            main.removeCallbacks(restoreLauncherAfterNativeCall)
            main.postDelayed(restoreLauncherAfterNativeCall, NATIVE_CALL_RETURN_DELAY_MS)
        }
        log("Native Rokid call window no longer observed; releasing call priority")
    }

    private fun scheduleNativeCallWindowExitCheck() {
        main.removeCallbacks(nativeCallWindowExitCheck)
        main.postDelayed(nativeCallWindowExitCheck, NATIVE_CALL_WINDOW_EXIT_GRACE_MS)
    }

    private fun registerNativeCallModeObserver() {
""",
)

replace_once(
    SERVICE,
    """    private fun unregisterNativeCallModeObserver() {
        main.removeCallbacks(restoreLauncherAfterNativeCall)
""",
    """    private fun unregisterNativeCallModeObserver() {
        main.removeCallbacks(restoreLauncherAfterNativeCall)
        main.removeCallbacks(nativeCallWindowExitCheck)
""",
)

replace_once(
    SERVICE,
    """        private const val NATIVE_CALL_RETURN_DELAY_MS = 450L
""",
    """        private const val NATIVE_CALL_RETURN_DELAY_MS = 450L
        private const val NATIVE_CALL_WINDOW_EXIT_GRACE_MS = 1_200L
""",
)

(SRC / "NativeRokidCallWindowPolicy.kt").write_text(
    '''package com.anezium.rokidbus.glasses

/** Conservative matching for the native Rokid phone-call UI exposed through Accessibility. */
internal object NativeRokidCallWindowPolicy {
    private val rokidPackages = setOf(
        "com.rokid.os.sprite.launcher",
        "com.rokid.os.sprite.assistserver",
    )

    private val callMarkers = listOf(
        "phone",
        "call",
        "telecom",
        "dial",
        "incoming",
        "outgoing",
        "ring",
    )

    fun isRokidSystemWindow(packageName: String): Boolean = packageName in rokidPackages

    fun isCallWindow(packageName: String, className: String): Boolean {
        if (!isRokidSystemWindow(packageName)) return false
        val haystack = (packageName + "." + className).lowercase()
        return callMarkers.any(haystack::contains)
    }
}
''',
    encoding="utf-8",
)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "NativeRokidCallWindowPolicyTest.kt").write_text(
    '''package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRokidCallWindowPolicyTest {
    @Test fun rokidAssistPhoneWindowMatches() {
        assertTrue(NativeRokidCallWindowPolicy.isCallWindow(
            "com.rokid.os.sprite.assistserver", "com.rokid.phone.PhoneActivity"))
    }

    @Test fun rokidLauncherIncomingWindowMatches() {
        assertTrue(NativeRokidCallWindowPolicy.isCallWindow(
            "com.rokid.os.sprite.launcher", "IncomingCallActivity"))
    }

    @Test fun ordinaryLauncherWindowDoesNotMatch() {
        assertFalse(NativeRokidCallWindowPolicy.isCallWindow(
            "com.rokid.os.sprite.launcher", "com.rokid.os.sprite.launcher.main.SpriteMainActivity"))
    }

    @Test fun unrelatedAppCannotTriggerCallYield() {
        assertFalse(NativeRokidCallWindowPolicy.isCallWindow(
            "com.example.app", "PhoneActivity"))
    }
}
''',
    encoding="utf-8",
)

launcher_text = LAUNCHER.read_text(encoding="utf-8")
service_text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,",
    "dp(18), dp(18)",
    "minimumHeight = dp(38)",
    "scroll.smoothScrollTo",
):
    if marker not in launcher_text:
        raise SystemExit(f"Missing Nexus Glasses 1.4.11 launcher marker: {marker}")
if "FLAG_KEEP_SCREEN_ON" in launcher_text:
    raise SystemExit("Launcher still keeps the display awake")
for marker in (
    "NativeRokidCallWindowPolicy.isCallWindow",
    "onNativeCallWindowDetected",
    "NATIVE_CALL_WINDOW_EXIT_GRACE_MS",
):
    if marker not in service_text:
        raise SystemExit(f"Missing Nexus Glasses 1.4.11 call marker: {marker}")
