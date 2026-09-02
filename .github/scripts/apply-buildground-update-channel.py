#!/usr/bin/env python3
"""Apply the BuildGround-owned Nexus update sources and app version."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str) -> None:
    file_path = ROOT / path
    text = file_path.read_text(encoding="utf-8-sig")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old!r}")
    file_path.write_text(text.replace(old, new), encoding="utf-8")


def configure_update_sources() -> None:
    replace_once(
        "phone-hub/src/main/java/com/anezium/rokidbus/phone/NexusUpdateChecker.kt",
        '"https://api.github.com/repos/Anezium/Rokid-Nexus/releases?per_page=100"',
        '"https://api.github.com/repos/4108814-ai/BuildGround-Rokid/releases?per_page=100"',
    )
    replace_once(
        "phone-hub/src/main/java/com/anezium/rokidbus/phone/RegistryClient.kt",
        '"https://raw.githubusercontent.com/Anezium/RokidBrew-Registry/main/dist/nexus-plugins.v1.json"',
        '"https://raw.githubusercontent.com/4108814-ai/BuildGround-Rokid/main/registry/nexus-plugins.v1.json"',
    )


def bump_app_version() -> None:
    for path in ("phone-hub/build.gradle.kts", "glasses-hub/build.gradle.kts"):
        replace_once(path, "versionCode = 10403", "versionCode = 10404")
        replace_once(path, 'versionName = "1.4.3"', 'versionName = "1.4.4"')

    changelog = ROOT / "CHANGELOG.md"
    text = changelog.read_text(encoding="utf-8-sig")
    if "## 1.4.4" not in text:
        anchor = "## Unreleased\n"
        if text.count(anchor) != 1:
            raise RuntimeError("CHANGELOG.md has no unique Unreleased section")
        addition = (
            "\n## 1.4.4\n\n"
            "- **BuildGround owns the Nexus update channel.** The phone app now checks signed app releases from the BuildGround repository instead of the upstream Anezium release stream, so a BuildGround-signed installation is never offered an incompatible upstream APK.\n"
            "- **Plugin updates use the BuildGround registry.** The registry mirrors the public RokidBrew catalogue while preserving BuildGround-managed plugin artifacts, hashes, signer certificates, and release history.\n"
            "- **Release signing is fail-closed.** App and plugin release jobs verify the permanent BuildGround certificate before publishing anything that Nexus can install.\n"
        )
        changelog.write_text(text.replace(anchor, anchor + addition), encoding="utf-8")


def main() -> None:
    configure_update_sources()
    bump_app_version()
    print("BuildGround update channel applied.")


if __name__ == "__main__":
    main()
