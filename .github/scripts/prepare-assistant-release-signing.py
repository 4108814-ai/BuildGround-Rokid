from __future__ import annotations

import base64
import os
import re
from pathlib import Path

B64_KEY = "NEXUS_RELEASE_KEYSTORE_B64"
PASSWORD_KEY = "NEXUS_RELEASE_KEYSTORE_PASSWORD"
ALIAS_KEY = "NEXUS_RELEASE_KEY_ALIAS"
BASE64_CHARS = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")


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


def clean_payload_fragment(value: str) -> str:
    value = value.replace("\ufeff", "").strip()
    if value.startswith("b'") and value.endswith("'"):
        value = value[2:-1]
    elif value.startswith('b"') and value.endswith('"'):
        value = value[2:-1]
    value = value.strip("`'\"“”‘’")
    return "".join(value.split())


def validate_candidate(candidate: str) -> str | None:
    candidate = clean_payload_fragment(candidate)
    if len(candidate) < 1000:
        return None
    if not all(char in BASE64_CHARS for char in candidate):
        return None
    try:
        base64.b64decode(candidate, validate=True)
    except Exception:
        return None
    return candidate


def b64_value(raw: str) -> str:
    """Recover the PKCS12 Base64 payload without exposing repository secret text."""
    raw = raw.replace("\r", "").replace("\ufeff", "")
    marker = B64_KEY + "="

    if marker in raw:
        tail = raw.split(marker, 1)[1]
        # If an entire secrets file/line was pasted, stop before another secret name.
        next_marker = tail.find("NEXUS_RELEASE_")
        if next_marker >= 0:
            tail = tail[:next_marker]

        direct = validate_candidate(tail)
        if direct is not None:
            return direct

        # Wrapped Base64 may contain punctuation or an invisible separator. Find the
        # longest legitimate run first; never print the recovered value.
        runs = re.findall(r"[A-Za-z0-9+/=]{1000,}", tail)
        for candidate in sorted(runs, key=len, reverse=True):
            valid = validate_candidate(candidate)
            if valid is not None:
                return valid

        # Final marker-scoped fallback: discard non-Base64 wrapper characters.
        filtered = "".join(char for char in tail if char in BASE64_CHARS)
        valid = validate_candidate(filtered)
        if valid is not None:
            return valid

    # Value-only paste.
    direct = validate_candidate(raw)
    if direct is not None:
        return direct

    compact = "".join(raw.split())
    runs = re.findall(r"[A-Za-z0-9+/=]{1000,}", compact)
    for candidate in sorted(runs, key=len, reverse=True):
        valid = validate_candidate(candidate)
        if valid is not None:
            return valid

    # Safe value-only fallback for isolated wrapper characters such as quotes/BOM.
    filtered = "".join(char for char in compact if char in BASE64_CHARS)
    valid = validate_candidate(filtered)
    if valid is not None:
        return valid

    raise SystemExit("Could not recover a valid Base64 keystore payload from the repository secret")


raw_b64 = os.environ.get("NEXUS_RELEASE_KEYSTORE_B64_RAW", "")
raw_password = os.environ.get("NEXUS_RELEASE_KEYSTORE_PASSWORD_RAW", "")
raw_alias = os.environ.get("NEXUS_RELEASE_KEY_ALIAS_RAW", "")

if not raw_b64 or not raw_password or not raw_alias:
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env_file:
        env_file.write("HAS_RELEASE_SIGNING=false\n")
    print("Stable release signing is not configured on this repository.")
    raise SystemExit(0)

candidate = b64_value(raw_b64)
decoded = base64.b64decode(candidate, validate=True)
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
