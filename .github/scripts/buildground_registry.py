#!/usr/bin/env python3
"""Maintain the BuildGround Nexus plugin registry.

The public RokidBrew registry remains the catalogue source for third-party plugins.
BuildGround-owned releases are overlaid by plugin id and keep their own artifact,
release history, and Nexus contract so a later upstream sync cannot replace a
BuildGround-signed APK with an upstream-signed APK.
"""

from __future__ import annotations

import argparse
import copy
import json
import re
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

SCHEMA_VERSION = 1
MANAGED_FIELD = "xBuildGroundManaged"
SOURCE_FIELD = "xBuildGroundSourceRepo"
BUILDGROUND_REPOSITORY = "4108814-ai/BuildGround-Rokid"
HEX_256 = re.compile(r"^[0-9a-f]{64}$")


class RegistryError(ValueError):
    pass


def _load(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RegistryError(f"Could not read registry {path}: {exc}") from exc
    if not isinstance(data, dict) or data.get("version") != SCHEMA_VERSION:
        raise RegistryError(f"Registry {path} must use version {SCHEMA_VERSION}")
    plugins = data.get("plugins")
    if not isinstance(plugins, list):
        raise RegistryError(f"Registry {path} must contain a plugins array")
    return data


def _write(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _plugin_id(plugin: dict[str, Any]) -> str:
    value = plugin.get("id")
    if not isinstance(value, str) or not value:
        raise RegistryError("Every plugin must have a non-empty id")
    return value


def merge_upstream(current: dict[str, Any], upstream: dict[str, Any]) -> dict[str, Any]:
    """Refresh the public catalogue while preserving BuildGround-managed entries."""
    managed: dict[str, dict[str, Any]] = {}
    for plugin in current["plugins"]:
        if not isinstance(plugin, dict):
            raise RegistryError("Current registry contains a non-object plugin")
        plugin_id = _plugin_id(plugin)
        if plugin.get(MANAGED_FIELD) is True:
            managed[plugin_id] = copy.deepcopy(plugin)

    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for plugin in upstream["plugins"]:
        if not isinstance(plugin, dict):
            raise RegistryError("Upstream registry contains a non-object plugin")
        plugin_id = _plugin_id(plugin)
        if plugin_id in seen:
            raise RegistryError(f"Duplicate upstream plugin id: {plugin_id}")
        seen.add(plugin_id)
        result.append(copy.deepcopy(managed.pop(plugin_id, plugin)))

    for plugin_id in sorted(managed):
        result.append(managed[plugin_id])

    return {"version": SCHEMA_VERSION, "plugins": result}


def _require_https(url: str, field: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise RegistryError(f"{field} must be an absolute HTTPS URL")


def publish(
    current: dict[str, Any],
    upstream: dict[str, Any],
    *,
    plugin_id: str,
    version_name: str,
    version_code: int,
    package_name: str,
    artifact_url: str,
    artifact_sha256: str,
    signer_sha256: str,
    size_bytes: int,
    published_at: str,
    release_notes: str,
) -> dict[str, Any]:
    if version_code <= 0:
        raise RegistryError("versionCode must be positive")
    if size_bytes <= 0:
        raise RegistryError("Artifact size must be positive")
    if not package_name.strip():
        raise RegistryError("Package name must not be empty")
    if not HEX_256.fullmatch(artifact_sha256):
        raise RegistryError("Artifact SHA-256 must be 64 lowercase hex characters")
    if not HEX_256.fullmatch(signer_sha256):
        raise RegistryError("Signer SHA-256 must be 64 lowercase hex characters")
    _require_https(artifact_url, "Artifact URL")

    merged = merge_upstream(current, upstream)
    matches = [plugin for plugin in merged["plugins"] if _plugin_id(plugin) == plugin_id]
    if len(matches) != 1:
        raise RegistryError(
            f"Plugin '{plugin_id}' must exist exactly once in the upstream/current registry; found {len(matches)}",
        )
    plugin = matches[0]
    nexus = plugin.get("nexus")
    artifact = plugin.get("artifact")
    if not isinstance(nexus, dict) or nexus.get("pluginId") != plugin_id:
        raise RegistryError(f"Plugin '{plugin_id}' has an invalid Nexus pluginId")
    if not isinstance(artifact, dict):
        raise RegistryError(f"Plugin '{plugin_id}' has no artifact object")
    existing_package = artifact.get("packageName")
    if existing_package and existing_package != package_name:
        raise RegistryError(
            f"Plugin '{plugin_id}' package changed from '{existing_package}' to '{package_name}'",
        )

    plugin["artifact"] = {
        "target": "phone",
        "url": artifact_url,
        "sha256": artifact_sha256,
        "signerSha256": signer_sha256,
        "sizeBytes": size_bytes,
        "packageName": package_name,
        "versionCode": version_code,
        "versionName": version_name,
    }

    releases = plugin.get("releases")
    if not isinstance(releases, list):
        releases = []
    releases = [
        release
        for release in releases
        if not (isinstance(release, dict) and release.get("version") == version_name)
    ]
    releases.insert(
        0,
        {
            "version": version_name,
            "date": published_at,
            "notes": release_notes,
        },
    )
    plugin["releases"] = releases
    plugin[MANAGED_FIELD] = True
    plugin[SOURCE_FIELD] = BUILDGROUND_REPOSITORY
    return merged


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    sync = subparsers.add_parser("sync", help="Merge the latest upstream catalogue")
    sync.add_argument("--current", type=Path, required=True)
    sync.add_argument("--upstream", type=Path, required=True)
    sync.add_argument("--output", type=Path, required=True)

    release = subparsers.add_parser("publish", help="Publish one BuildGround plugin release")
    release.add_argument("--current", type=Path, required=True)
    release.add_argument("--upstream", type=Path, required=True)
    release.add_argument("--output", type=Path, required=True)
    release.add_argument("--plugin-id", required=True)
    release.add_argument("--version-name", required=True)
    release.add_argument("--version-code", type=int, required=True)
    release.add_argument("--package-name", required=True)
    release.add_argument("--artifact-url", required=True)
    release.add_argument("--artifact-sha256", required=True)
    release.add_argument("--signer-sha256", required=True)
    release.add_argument("--size-bytes", type=int, required=True)
    release.add_argument("--published-at", required=True)
    release.add_argument("--release-notes-file", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    current = _load(args.current)
    upstream = _load(args.upstream)
    if args.command == "sync":
        result = merge_upstream(current, upstream)
    else:
        notes = args.release_notes_file.read_text(encoding="utf-8").strip()
        result = publish(
            current,
            upstream,
            plugin_id=args.plugin_id,
            version_name=args.version_name,
            version_code=args.version_code,
            package_name=args.package_name,
            artifact_url=args.artifact_url,
            artifact_sha256=args.artifact_sha256,
            signer_sha256=args.signer_sha256,
            size_bytes=args.size_bytes,
            published_at=args.published_at,
            release_notes=notes,
        )
    _write(args.output, result)


if __name__ == "__main__":
    main()
