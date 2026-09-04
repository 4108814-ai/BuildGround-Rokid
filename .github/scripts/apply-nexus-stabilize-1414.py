from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
GLASSES_SRC = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses"
LAUNCHER = GLASSES_SRC / "LauncherOverlayRenderer.kt"
SERVICE = GLASSES_SRC / "RokidBusAccessibilityService.kt"
PHONE_MANIFEST = ROOT / "phone-hub/src/main/AndroidManifest.xml"
BUS_CONSTANTS = ROOT / "shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}: {old[:180]!r}; got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Reconstruct only the last stable phone transport and the last pre-wake-ownership
# Glasses launcher behavior. Deliberately do NOT run 1.4.9+ wake/call patches or
# the Phone call-state bridge.
runpy.run_path(str(ROOT / ".github/scripts/apply-meeting-audio-transport.py"), run_name="__main__")
runpy.run_path(str(ROOT / ".github/scripts/apply-nexus-glasses-148-launcher-retry.py"), run_name="__main__")

# Keep only the two field-validated 1.4.11 launcher improvements:
# 1) obey Rokid's native display timeout; 2) compact, scrollable plugin list.
replace_once(
    LAUNCHER,
    """                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
""",
    """                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
""",
)
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

launcher_text = LAUNCHER.read_text(encoding="utf-8")
service_text = SERVICE.read_text(encoding="utf-8")
manifest_text = PHONE_MANIFEST.read_text(encoding="utf-8")
bus_text = BUS_CONSTANTS.read_text(encoding="utf-8")

# Positive markers for the stable build.
for marker in (
    "launcherAutoRestoreCompleted = false",
    "snapshot.coreReady",
    "LAUNCHER_AUTO_RESTORE_MAX_RETRIES",
):
    if marker not in service_text:
        raise SystemExit(f"Missing stable 1.4.8 launcher marker: {marker}")
for marker in (
    "WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,",
    "dp(18), dp(18)",
    "minimumHeight = dp(38)",
    "scroll.smoothScrollTo",
):
    if marker not in launcher_text:
        raise SystemExit(f"Missing stable UI/sleep marker: {marker}")
if "FLAG_KEEP_SCREEN_ON" in launcher_text:
    raise SystemExit("Launcher still keeps the display awake")

# Negative markers: fail closed if any experimental telephony/wake-ownership code leaks in.
for marker in (
    "remotePhoneCallActive",
    "onRemotePhoneCallState",
    "NativeRokidCallWindowPolicy",
    "restoreLauncherAfterRemotePhoneCall",
    "onScreenOff =",
    "onScreenOn =",
):
    if marker in service_text:
        raise SystemExit(f"Experimental Glasses marker leaked into stabilization build: {marker}")
if "android.permission.READ_PHONE_STATE" in manifest_text:
    raise SystemExit("Phone call-state permission leaked into stabilization build")
if "PHONE_CALL_STATE" in bus_text or "/phone/call/state" in bus_text:
    raise SystemExit("Phone call-state bus contract leaked into stabilization build")

print("Nexus 1.4.14 stabilization patch applied: Phone stable transport + Glasses 1.4.8 shell + UI/sleep only")
