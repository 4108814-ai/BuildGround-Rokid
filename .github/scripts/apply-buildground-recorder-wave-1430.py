#!/usr/bin/env python3
"""Keep Rokid native recording active while hiding the recorder waves after three seconds."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "glasses-hub/build.gradle.kts"
CONTROLLER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidNativeRecordingController.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Runs after the complete 1.4.29 SPP Wake Keeper chain.
replace_once(GRADLE, "versionCode = 10429", "versionCode = 10430", "versionCode")
replace_once(GRADLE, 'versionName = "1.4.29"', 'versionName = "1.4.30"', "versionName")

# Keep the proven native Rokid recorder and Stop command unchanged. Only extend the existing
# post-start display-standby delay from 1.2 s to 3.0 s so the stock recording waves are visible
# briefly, then disappear while Rokid continues recording in the background.
replace_once(
    CONTROLLER,
    "private const val DISPLAY_SLEEP_DELAY_MS = 1_200L",
    "private const val DISPLAY_SLEEP_DELAY_MS = 3_000L",
    "recorder wave timeout",
)

# Guard the exact behavior we want: successful Start still schedules standby, Stop does not,
# and the native Start/Stop Binder commands remain intact.
gradle = GRADLE.read_text(encoding="utf-8")
controller = CONTROLLER.read_text(encoding="utf-8")

for required in (
    'versionCode = 10430',
    'versionName = "1.4.30"',
):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.30 version marker: {required}")

for required in (
    'private const val CMD_START = "cmd_start_audio_record"',
    'private const val CMD_STOP = "cmd_stop_audio_record"',
    'private const val DISPLAY_SLEEP_DELAY_MS = 3_000L',
    'if (after) scheduleDisplaySleep()',
    'if (confirmedRecording != true) return@postDelayed',
    'RokidBusAccessibilityService.requestDisplaySleep()',
    'writeRecordingStateLocked(after)',
):
    if required not in controller:
        raise SystemExit(f"Missing recorder behavior marker: {required}")

if 'DISPLAY_SLEEP_DELAY_MS = 1_200L' in controller:
    raise SystemExit("Legacy 1.2-second recorder wave timeout remains")

print("Applied BuildGround Nexus Glasses 1.4.30 recorder wave 3-second timeout.")
