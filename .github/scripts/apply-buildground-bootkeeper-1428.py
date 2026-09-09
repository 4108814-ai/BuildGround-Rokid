#!/usr/bin/env python3
"""Forward-version the already-applied 1.4.27 Direct Boot Keeper to 1.4.28."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "glasses-hub/build.gradle.kts"

text = GRADLE.read_text(encoding="utf-8-sig")
if text.count("versionCode = 10427") != 1:
    raise SystemExit("Expected exact 1.4.27 versionCode once")
if text.count('versionName = "1.4.27"') != 1:
    raise SystemExit("Expected exact 1.4.27 versionName once")
text = text.replace("versionCode = 10427", "versionCode = 10428", 1)
text = text.replace('versionName = "1.4.27"', 'versionName = "1.4.28"', 1)
GRADLE.write_text(text, encoding="utf-8")
print("Forward-versioned BuildGround Nexus Glasses Direct Boot Keeper to 1.4.28.")
