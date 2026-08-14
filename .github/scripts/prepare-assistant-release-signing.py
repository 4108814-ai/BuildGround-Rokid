from __future__ import annotations

import base64
import os
import re
from pathlib import Path

B64_KEY = "NEXUS_RELEASE_KEYSTORE_B64"
PASSWORD_KEY = "NEXUS_RELEASE_KEYSTORE_PASSWORD"
ALIAS_KEY = "NEXUS_RELEASE_KEY_ALIAS"


def scalar(raw: str, key: str) -> str:
    """Extract one secret value whether GitHub contains value-only or KEY=value text."""
    raw = raw.replace("\r", "")
    marker = key + "="
    for line in raw.split("\n"):
        stripped = line.strip()
        if marker in stripped:
            value = stripped.split(marker, 1)[1].strip()
            return value.strip("`'\"")
    return raw.strip().strip("`'\"")


def b64_value(raw: str) -> str:
    """Recover a long PKCS12 Base64 payload from common paste formats."""
    raw = raw.replace("\r", "")
    marker = B64_KEY + "="

    # Preferred: marker line, optionally followed by wrapped Base64 lines.
    lines = raw.split("\n")
    for index, line in enumerate(lines):
        if marker not in line:
            continue
        parts: list[str] = []
        first = line.split(marker, 1)[1].strip().strip("`'\"")
        if first:
            parts.append(first)
        for continuation in lines[index + 1 :]:
            value = continuation.strip().strip("`'\"")
            if not value:
                continue
            if value.startswith("NEXUS_RELEASE_"):
                break
            if re.fullmatch(r"[A-Za-z0-9+/=]+", value):
                parts.append(value)
            else:
                break
        candidate = "".join(parts)
        if candidate:
            return candidate

    # Value-only paste, with optional quotes/backticks/whitespace around it.
    compact = "".join(raw.split())
    runs = re.findall(r"[A-Za-z0-9+/=]{1000,}", compact)
    if runs:
        return max(runs, key=len)

    raise SystemExit("Could not locate a Base64 keystore payload in the repository secret")


raw_b64 = os.environ.get("NEXUS_RELEASE_KEYSTORE_B64_RAW", "")
raw_password = os.environ.get("NEXUS_RELEASE_KEYSTORE_PASSWORD_RAW", "")
raw_alias = os.environ.get("NEXUS_RELEASE_KEY_ALIAS_RAW", "")

if not raw_b64 or not raw_password or not raw_alias:
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env_file:
        env_file.write("HAS_RELEASE_SIGNING=false\n")
    print("Stable release signing is not configured on this repository.")
    raise SystemExit(0)

candidate = b64_value(raw_b64)
try:
    decoded = base64.b64decode(candidate, validate=True)
except Exception as exc:
    raise SystemExit(f"Recovered keystore payload is not valid Base64: {type(exc).__name__}") from exc

if len(decoded) < 1000:
    raise SystemExit("Decoded keystore payload is unexpectedly small")

password = scalar(raw_password, PASSWORD_KEY)
alias = scalar(raw_alias, ALIAS_KEY)
if not password or not alias:
    raise SystemExit("Signing password or alias is empty after normalization")

keystore_path = Path(os.environ["RUNNER_TEMP"]) / "nexus-release.p12"
keystore_path.write_bytes(decoded)
os.chmod(keystore_path, 0o600)

# Mask normalized values before any later command can expose them.
print(f"::add-mask::{password}")
print(f"::add-mask::{alias}")

with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env_file:
    env_file.write(f"NEXUS_RELEASE_KEYSTORE={keystore_path}\n")
    env_file.write(f"NEXUS_RELEASE_KEYSTORE_PASSWORD={password}\n")
    env_file.write(f"NEXUS_RELEASE_KEY_ALIAS={alias}\n")
    env_file.write("HAS_RELEASE_SIGNING=true\n")

print("Stable release signing payload recovered.")
