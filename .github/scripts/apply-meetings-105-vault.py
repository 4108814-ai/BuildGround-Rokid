from pathlib import Path
import runpy
import subprocess

# Keep the full vault implementation pinned to the immutable implementation commit and apply
# generator-only corrections here. This makes the release deterministic while avoiding a second
# copy of the large generated-runtime patch.
ROOT = Path(__file__).resolve().parents[2]
PINNED_IMPLEMENTATION_COMMIT = "ae14ff17251bf7549747c5d4e07408cd868a21cb"
PINNED_PATH = ".github/scripts/apply-meetings-105-vault.py"
TEMP = Path(__file__).with_name("_apply-meetings-105-vault-pinned.py")

source = subprocess.check_output(
    ["git", "show", f"{PINNED_IMPLEMENTATION_COMMIT}:{PINNED_PATH}"],
    cwd=ROOT,
    text=True,
)
old = '''for marker in (
    "MediaStore.Downloads.EXTERNAL_CONTENT_URI",
    "BuildGround/Meetings/",
    "audio.wav",
    "protocol.md",
):'''
new = '''for marker in (
    "MediaStore.Downloads.EXTERNAL_CONTENT_URI",
    "BuildGround/Meetings/",
    "audio.wav",
    "AssistantMeetingStore.TRANSCRIPT_TEXT_NAME",
    "AssistantMeetingStore.PROTOCOL_MARKDOWN_NAME",
):'''
if source.count(old) != 1:
    raise SystemExit("Pinned Meetings 1.0.5 exporter invariant block changed unexpectedly")
source = source.replace(old, new, 1)
TEMP.write_text(source, encoding="utf-8")
try:
    runpy.run_path(str(TEMP), run_name="__main__")
finally:
    TEMP.unlink(missing_ok=True)
