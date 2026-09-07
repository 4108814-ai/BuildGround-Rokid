#!/usr/bin/env python3
"""Forward-version the persistent-autostart generated build to Nexus Glasses 1.4.26."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "glasses-hub/build.gradle.kts"

text = GRADLE.read_text(encoding="utf-8-sig")
for old, new, label in (
    ("versionCode = 10425", "versionCode = 10426", "versionCode"),
    ('versionName = "1.4.25"', 'versionName = "1.4.26"', "versionName"),
):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one {old!r}, found {count}")
    text = text.replace(old, new, 1)
GRADLE.write_text(text, encoding="utf-8")

check = GRADLE.read_text(encoding="utf-8")
for required in ('versionCode = 10426', 'versionName = "1.4.26"'):
    if required not in check:
        raise SystemExit(f"Missing 1.4.26 marker: {required}")

print("Forward-versioned BuildGround Nexus Glasses persistent autostart to 1.4.26.")
