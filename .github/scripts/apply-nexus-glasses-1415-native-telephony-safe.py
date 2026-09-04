from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LauncherOverlayRenderer.kt"
GRADLE = ROOT / "glasses-hub/build.gradle.kts"
SERVICE = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:160]!r}; got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Keep native Rokid display timeout: Nexus launcher must not hold the display awake.
replace_once(
    LAUNCHER,
    """                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
""",
    """                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
""",
)

# Field-validated compact launcher only. No launcher ownership or call-state logic is added.
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

# Forward-only Android update number; runtime remains the released Glasses 1.4.4 line plus UI/sleep changes above.
replace_once(GRADLE, "versionCode = 10404", "versionCode = 10415")
replace_once(GRADLE, 'versionName = "1.4.4"', 'versionName = "1.4.15"')

launcher_text = LAUNCHER.read_text(encoding="utf-8")
service_text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,",
    "dp(18), dp(18)",
    "minimumHeight = dp(38)",
    "scroll.smoothScrollTo",
):
    if marker not in launcher_text:
        raise SystemExit(f"Missing Glasses 1.4.15 launcher marker: {marker}")
if "FLAG_KEEP_SCREEN_ON" in launcher_text:
    raise SystemExit("Launcher still keeps the display awake")

# Hard negative checks: 1.4.15 must not contain any of the later ownership/call experiments.
for forbidden in (
    "launcherAutoRestoreCompleted",
    "scheduleLauncherAutoRestore",
    "remotePhoneCallActive",
    "onRemotePhoneCallState",
    "NativeRokidCallWindowPolicy",
    "registerNativeCallModeObserver",
):
    if forbidden in service_text:
        raise SystemExit(f"Forbidden experimental Glasses marker present: {forbidden}")

for forbidden_file in (
    ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LauncherAutoRestorePolicy.kt",
    ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NativeRokidCallWindowPolicy.kt",
):
    if forbidden_file.exists():
        raise SystemExit(f"Forbidden experimental file present: {forbidden_file}")
