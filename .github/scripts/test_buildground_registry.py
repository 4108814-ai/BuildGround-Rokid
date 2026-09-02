#!/usr/bin/env python3

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).with_name("buildground_registry.py")
SPEC = importlib.util.spec_from_file_location("buildground_registry", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def plugin(plugin_id: str, package_name: str, version_code: int = 7) -> dict:
    return {
        "id": plugin_id,
        "kind": "nexus-plugin",
        "name": plugin_id.title(),
        "category": "Tools",
        "summary": "Summary",
        "description": "Description",
        "author": "Anezium",
        "sourceUrl": "https://github.com/Anezium/Rokid-Nexus",
        "publishedAt": "2026-08-01T00:00:00Z",
        "iconAsset": f"{plugin_id}.png",
        "screenshotAssets": [],
        "listing": {"descriptionMarkdown": "Details"},
        "releases": [{"version": "1.0.0", "date": "2026-08-01T00:00:00Z", "notes": "Initial"}],
        "nexus": {
            "pluginId": plugin_id,
            "apiVersion": 3,
            "capabilities": ["surfaces"],
            "launchable": True,
            "settingsActivity": None,
            "minHostVersionCode": 10200,
        },
        "artifact": {
            "target": "phone",
            "url": f"https://github.com/Anezium/Rokid-Nexus/releases/download/{plugin_id}-v1.0.0/{plugin_id}-phone-release.apk",
            "sha256": "aa" * 32,
            "signerSha256": "bb" * 32,
            "sizeBytes": 100,
            "packageName": package_name,
            "versionCode": version_code,
            "versionName": "1.0.0",
        },
    }


class BuildGroundRegistryTest(unittest.TestCase):
    def test_sync_refreshes_upstream_and_preserves_managed_entry(self):
        upstream = {"version": 1, "plugins": [plugin("assistant", "com.example.assistant"), plugin("agenda", "com.example.agenda")]}
        managed = plugin("assistant", "com.example.assistant", version_code=11)
        managed[MODULE.MANAGED_FIELD] = True
        managed["artifact"]["url"] = "https://github.com/4108814-ai/BuildGround-Rokid/releases/download/assistant-v1.5.1/assistant-phone-release.apk"
        current = {"version": 1, "plugins": [managed]}

        result = MODULE.merge_upstream(current, upstream)

        self.assertEqual(["assistant", "agenda"], [item["id"] for item in result["plugins"]])
        assistant = result["plugins"][0]
        self.assertEqual(11, assistant["artifact"]["versionCode"])
        self.assertTrue(assistant[MODULE.MANAGED_FIELD])

    def test_publish_overlays_signed_buildground_artifact(self):
        upstream = {"version": 1, "plugins": [plugin("assistant", "com.example.assistant")]}
        current = {"version": 1, "plugins": []}

        result = MODULE.publish(
            current,
            upstream,
            plugin_id="assistant",
            version_name="1.5.1",
            version_code=11,
            package_name="com.example.assistant",
            artifact_url="https://github.com/4108814-ai/BuildGround-Rokid/releases/download/assistant-v1.5.1/assistant-phone-release.apk",
            artifact_sha256="11" * 32,
            signer_sha256="22" * 32,
            size_bytes=123456,
            published_at="2026-09-02T12:00:00Z",
            release_notes="Diagnostics.",
        )

        assistant = result["plugins"][0]
        self.assertTrue(assistant[MODULE.MANAGED_FIELD])
        self.assertEqual(MODULE.BUILDGROUND_REPOSITORY, assistant[MODULE.SOURCE_FIELD])
        self.assertEqual(11, assistant["artifact"]["versionCode"])
        self.assertEqual("22" * 32, assistant["artifact"]["signerSha256"])
        self.assertEqual("1.5.1", assistant["releases"][0]["version"])

    def test_publish_rejects_package_change(self):
        upstream = {"version": 1, "plugins": [plugin("assistant", "com.example.assistant")]}
        with self.assertRaises(MODULE.RegistryError):
            MODULE.publish(
                {"version": 1, "plugins": []},
                upstream,
                plugin_id="assistant",
                version_name="1.5.1",
                version_code=11,
                package_name="com.example.other",
                artifact_url="https://github.com/4108814-ai/BuildGround-Rokid/releases/download/assistant-v1.5.1/assistant-phone-release.apk",
                artifact_sha256="11" * 32,
                signer_sha256="22" * 32,
                size_bytes=123456,
                published_at="2026-09-02T12:00:00Z",
                release_notes="Diagnostics.",
            )

    def test_publish_rejects_invalid_signer_hash(self):
        upstream = {"version": 1, "plugins": [plugin("assistant", "com.example.assistant")]}
        with self.assertRaises(MODULE.RegistryError):
            MODULE.publish(
                {"version": 1, "plugins": []},
                upstream,
                plugin_id="assistant",
                version_name="1.5.1",
                version_code=11,
                package_name="com.example.assistant",
                artifact_url="https://github.com/4108814-ai/BuildGround-Rokid/releases/download/assistant-v1.5.1/assistant-phone-release.apk",
                artifact_sha256="11" * 32,
                signer_sha256="NOT-A-HASH",
                size_bytes=123456,
                published_at="2026-09-02T12:00:00Z",
                release_notes="Diagnostics.",
            )


if __name__ == "__main__":
    unittest.main()
