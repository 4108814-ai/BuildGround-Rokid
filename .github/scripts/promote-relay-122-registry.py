from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
registry_path = ROOT / "registry/nexus-plugins.v1.json"
data = json.loads(registry_path.read_text(encoding="utf-8"))
plugins = data.get("plugins", [])
matches = [p for p in plugins if p.get("id") == "relay"]
if len(matches) != 1:
    raise SystemExit(f"Expected exactly one relay registry entry, found {len(matches)}")
relay = matches[0]
artifact = relay.get("artifact") or {}
if artifact.get("packageName") != "com.anezium.rokidbus.plugin.relay":
    raise SystemExit("Unexpected Relay package baseline")
if artifact.get("versionName") not in {"1.2.1", "1.2.2"}:
    raise SystemExit(f"Unexpected Relay version baseline: {artifact.get('versionName')}")

relay["sourceUrl"] = "https://github.com/4108814-ai/BuildGround-Rokid"
relay["artifact"] = {
    "target": "phone",
    "url": "https://github.com/4108814-ai/BuildGround-Rokid/releases/download/relay-v1.2.2-bg/relay-phone-1.2.2-bg.apk",
    "sha256": "af1a3d6b6bdef35ee88ecad8aade9595601a74343a1c1e5d4e73a2d9d8be3a91",
    "signerSha256": "55b22185392b8c1f084c3dec58bb0f342ece7102289556ae23cadee6ccec2097",
    "sizeBytes": 5645156,
    "packageName": "com.anezium.rokidbus.plugin.relay",
    "versionCode": 11,
    "versionName": "1.2.2",
}
release = {
    "version": "1.2.2",
    "date": "2026-09-04T09:05:27Z",
    "notes": "BuildGround Relay 1.2.2 — deterministic notification handoff.\n\n- Built from the exact Anezium/Rokid-Nexus relay-v1.2.1 baseline (commit be1bb54a2cbdbc6655011e242fa931910abcb090).\n- Distinct notification keys are presented FIFO instead of overwriting the one already waiting for the glasses.\n- Newer revisions of the same queued notification are coalesced in place, so cumulative messaging-thread updates do not become duplicate bands or move ahead of another conversation.\n- Reconnect/replay keeps the queue head; close, dismissal fallback, expiry or non-retryable show failure advances to the next item.\n- Stale queued captures older than the existing 120-second replay window are dropped.\n- First migration from upstream-signed 1.2.1 requires uninstall/reinstall and re-enabling Android Notification Access because the BuildGround release uses the permanent BuildGround signer.\n\n### Artifact\n- File: `relay-phone-1.2.2-bg.apk`\n- SHA-256: `af1a3d6b6bdef35ee88ecad8aade9595601a74343a1c1e5d4e73a2d9d8be3a91`\n- Signer SHA-256: `55b22185392b8c1f084c3dec58bb0f342ece7102289556ae23cadee6ccec2097`"
}
releases = relay.setdefault("releases", [])
releases = [r for r in releases if r.get("version") != "1.2.2"]
relay["releases"] = [release, *releases]

registry_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Promoted Relay 1.2.2 in registry")
