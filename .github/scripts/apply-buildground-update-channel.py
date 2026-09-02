#!/usr/bin/env python3
"""Apply the BuildGround-owned app and plugin update channel.

This script is intentionally idempotent. The feature-branch CI runs it before the
Android build so the exact source edits are tested before they are committed.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_CERT = "55:B2:21:85:39:2B:8C:1F:08:4C:3D:EC:58:BB:0F:34:2E:CE:71:02:28:95:56:AE:23:CA:DE:E6:CC:EC:20:97"


def replace_once(path: str, old: str, new: str) -> None:
    file_path = ROOT / path
    text = file_path.read_text(encoding="utf-8-sig")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old!r}")
    file_path.write_text(text.replace(old, new), encoding="utf-8")


def insert_before_once(path: str, anchor: str, addition: str, marker: str) -> None:
    file_path = ROOT / path
    text = file_path.read_text(encoding="utf-8-sig")
    if marker in text:
        return
    count = text.count(anchor)
    if count != 1:
        raise RuntimeError(f"Expected one anchor in {path}, found {count}: {anchor!r}")
    file_path.write_text(text.replace(anchor, addition + anchor), encoding="utf-8")


def insert_after_once(path: str, anchor: str, addition: str, marker: str) -> None:
    file_path = ROOT / path
    text = file_path.read_text(encoding="utf-8-sig")
    if marker in text:
        return
    count = text.count(anchor)
    if count != 1:
        raise RuntimeError(f"Expected one anchor in {path}, found {count}: {anchor!r}")
    file_path.write_text(text.replace(anchor, anchor + addition), encoding="utf-8")


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


def add_release_signing_guards() -> None:
    app_step = f'''      - name: Verify permanent BuildGround signing certificate\n        shell: bash\n        run: |\n          set -euo pipefail\n          expected='{EXPECTED_CERT}'\n          details="$(keytool -list -v \\\n            -keystore "$NEXUS_RELEASE_KEYSTORE" \\\n            -storepass "$NEXUS_RELEASE_KEYSTORE_PASSWORD" \\\n            -alias "$NEXUS_RELEASE_KEY_ALIAS")"\n          actual="$(printf '%s\\n' "$details" | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1)"\n          test -n "$actual"\n          echo "certificate SHA256=$actual"\n          test "$actual" = "$expected"\n\n'''
    insert_before_once(
        ".github/workflows/app-release.yml",
        "      - name: Build signed release APKs\n",
        app_step,
        "Verify permanent BuildGround signing certificate",
    )

    plugin_step = f'''      - name: Verify permanent BuildGround signing certificate\n        shell: bash\n        run: |\n          set -euo pipefail\n          expected='{EXPECTED_CERT}'\n          details="$(keytool -list -v \\\n            -keystore "$NEXUS_RELEASE_KEYSTORE" \\\n            -storepass "$NEXUS_RELEASE_KEYSTORE_PASSWORD" \\\n            -alias "$NEXUS_RELEASE_KEY_ALIAS")"\n          actual="$(printf '%s\\n' "$details" | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1)"\n          test -n "$actual"\n          echo "certificate SHA256=$actual"\n          test "$actual" = "$expected"\n          normalized="$(printf '%s' "$actual" | tr -d ':' | tr 'A-F' 'a-f')"\n          echo "ARTIFACT_SIGNER_SHA256=$normalized" >> "$GITHUB_ENV"\n\n'''
    insert_before_once(
        ".github/workflows/plugin-release.yml",
        "      - name: Build signed release APK\n",
        plugin_step,
        "ARTIFACT_SIGNER_SHA256",
    )


def extend_plugin_release_metadata() -> None:
    replace_once(
        ".github/workflows/plugin-release.yml",
        '          sha256="$(sha256sum "$artifact_path" | cut -d \' \' -f 1)"\n\n          notes_path=',
        '''          sha256="$(sha256sum "$artifact_path" | cut -d ' ' -f 1)"\n          size_bytes="$(stat -c '%s' "$artifact_path")"\n          metadata_path="$apk_dir/output-metadata.json"\n          if [[ ! -f "$metadata_path" ]]; then\n            echo "::error::Android output metadata is missing at '$metadata_path'."\n            exit 1\n          fi\n          package_name="$(python -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["applicationId"])' "$metadata_path")"\n          version_code="$(python -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["elements"][0]["versionCode"])' "$metadata_path")"\n          built_version="$(python -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["elements"][0]["versionName"])' "$metadata_path")"\n          if [[ "$built_version" != "$PLUGIN_VERSION" ]]; then\n            echo "::error::Built APK version '$built_version' does not match '$PLUGIN_VERSION'."\n            exit 1\n          fi\n\n          notes_path=''',
    )
    replace_once(
        ".github/workflows/plugin-release.yml",
        '''            echo "ARTIFACT_NAME=$artifact_name"\n            echo "ARTIFACT_PATH=$artifact_path"\n            echo "RELEASE_NOTES_PATH=$notes_path"\n''',
        '''            echo "ARTIFACT_NAME=$artifact_name"\n            echo "ARTIFACT_PATH=$artifact_path"\n            echo "ARTIFACT_SHA256=$sha256"\n            echo "ARTIFACT_SIZE_BYTES=$size_bytes"\n            echo "ARTIFACT_PACKAGE_NAME=$package_name"\n            echo "ARTIFACT_VERSION_CODE=$version_code"\n            echo "RELEASE_NOTES_PATH=$notes_path"\n''',
    )


def add_plugin_registry_publication() -> None:
    workflow = ROOT / ".github/workflows/plugin-release.yml"
    text = workflow.read_text(encoding="utf-8-sig")
    if "group: buildground-plugin-registry" not in text:
        anchor = "permissions:\n  contents: write\n\n"
        if text.count(anchor) != 1:
            raise RuntimeError("plugin-release.yml permissions anchor is not unique")
        text = text.replace(
            anchor,
            anchor + "concurrency:\n  group: buildground-plugin-registry\n  cancel-in-progress: false\n\n",
        )
        workflow.write_text(text, encoding="utf-8")

    publish_step = '''\n      - name: Publish release to BuildGround plugin registry\n        shell: bash\n        run: |\n          set -euo pipefail\n          git fetch origin main\n          registry_worktree="$RUNNER_TEMP/buildground-registry"\n          rm -rf "$registry_worktree"\n          git worktree add "$registry_worktree" origin/main\n\n          current="$registry_worktree/registry/nexus-plugins.v1.json"\n          mkdir -p "$(dirname "$current")"\n          if [[ ! -f "$current" ]]; then\n            printf '{"version":1,"plugins":[]}\\n' > "$current"\n          fi\n\n          upstream="$RUNNER_TEMP/upstream-registry.json"\n          curl --fail --silent --show-error --location \\\n            'https://raw.githubusercontent.com/Anezium/RokidBrew-Registry/main/dist/nexus-plugins.v1.json' \\\n            --output "$upstream"\n\n          artifact_url="https://github.com/$GITHUB_REPOSITORY/releases/download/$GITHUB_REF_NAME/$ARTIFACT_NAME"\n          published_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"\n          python "$GITHUB_WORKSPACE/.github/scripts/buildground_registry.py" publish \\\n            --current "$current" \\\n            --upstream "$upstream" \\\n            --output "$current" \\\n            --plugin-id "$PLUGIN_ID" \\\n            --version-name "$PLUGIN_VERSION" \\\n            --version-code "$ARTIFACT_VERSION_CODE" \\\n            --package-name "$ARTIFACT_PACKAGE_NAME" \\\n            --artifact-url "$artifact_url" \\\n            --artifact-sha256 "$ARTIFACT_SHA256" \\\n            --signer-sha256 "$ARTIFACT_SIGNER_SHA256" \\\n            --size-bytes "$ARTIFACT_SIZE_BYTES" \\\n            --published-at "$published_at" \\\n            --release-notes-file "$RELEASE_NOTES_PATH"\n          python -m json.tool "$current" >/dev/null\n\n          cd "$registry_worktree"\n          if git diff --quiet -- registry/nexus-plugins.v1.json; then\n            echo 'BuildGround registry already contains this release.'\n            exit 0\n          fi\n          git config user.name 'BuildGround Release Bot'\n          git config user.email '4108814-ai@users.noreply.github.com'\n          git add registry/nexus-plugins.v1.json\n          git commit -m "Publish $PLUGIN_ID $PLUGIN_VERSION to BuildGround registry"\n          git push origin HEAD:main\n'''
    insert_after_once(
        ".github/workflows/plugin-release.yml",
        '''      - name: Create non-latest GitHub release\n        shell: bash\n        env:\n          GH_TOKEN: ${{ github.token }}\n        run: |\n          set -euo pipefail\n          gh release create "$GITHUB_REF_NAME" "$ARTIFACT_PATH" \\\n            --title "$RELEASE_NAME $PLUGIN_VERSION" \\\n            --notes-file "$RELEASE_NOTES_PATH" \\\n            --verify-tag \\\n            --latest=false\n''',
        publish_step,
        "Publish release to BuildGround plugin registry",
    )


def main() -> None:
    configure_update_sources()
    bump_app_version()
    add_release_signing_guards()
    extend_plugin_release_metadata()
    add_plugin_registry_publication()
    print("BuildGround update channel applied.")


if __name__ == "__main__":
    main()
