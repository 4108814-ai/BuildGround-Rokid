#!/usr/bin/env python3
"""Apply BuildGround ownership metadata and version Nexus lifecycle recovery as 1.4.18."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHONE_GRADLE = ROOT / "phone-hub/build.gradle.kts"
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"
UPDATE_CHECKER = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/NexusUpdateChecker.kt"
REGISTRY_CLIENT = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/RegistryClient.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}: {old!r}; got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


for gradle in (PHONE_GRADLE, GLASSES_GRADLE):
    replace_once(gradle, "versionCode = 10405", "versionCode = 10418")
    replace_once(gradle, 'versionName = "1.4.5"', 'versionName = "1.4.18"')

replace_once(
    UPDATE_CHECKER,
    '"https://api.github.com/repos/Anezium/Rokid-Nexus/releases?per_page=100"',
    '"https://api.github.com/repos/4108814-ai/BuildGround-Rokid/releases?per_page=100"',
)
replace_once(
    REGISTRY_CLIENT,
    '"https://raw.githubusercontent.com/Anezium/RokidBrew-Registry/main/dist/nexus-plugins.v1.json"',
    '"https://raw.githubusercontent.com/4108814-ai/BuildGround-Rokid/main/registry/nexus-plugins.v1.json"',
)

print("Applied BuildGround Nexus 1.4.18 lifecycle recovery ownership/version patch.")
