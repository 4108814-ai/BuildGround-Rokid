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
STORE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantMeetingStore.kt"

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

# A Vault shell is intentionally created at Start, but an explicit legacy cancel must still mean
# "no archived meeting" when no durable artifact was produced. Never remove audio/transcript/
# protocol files: only the session-only shell is eligible for cleanup.
store = STORE.read_text(encoding="utf-8")
old_cancel = '''    override fun cancel(meetingId: String?) = synchronized(fileLock) {
        if (meetingId == null) return@synchronized
        deleteActiveIfSameMeeting(meetingId)
    }
'''
new_cancel = '''    override fun cancel(meetingId: String?) = synchronized(fileLock) {
        if (meetingId == null) return@synchronized
        deleteActiveIfSameMeeting(meetingId)
        deleteTransientVaultIfEmpty(meetingId)
    }

    private fun deleteTransientVaultIfEmpty(meetingId: String) {
        if (!MEETING_ID.matches(meetingId)) return
        val directory = File(archiveRoot, meetingId)
        if (!directory.isDirectory) return
        val files = directory.listFiles()?.toList().orEmpty()
        if (files.any { it.name != SESSION_JSON_NAME }) return

        val manifest = File(directory, SESSION_JSON_NAME)
        if (manifest.exists() && !manifest.delete()) {
            logger("Assistant meeting transient vault manifest could not be removed")
            return
        }
        if (directory.listFiles()?.isNotEmpty() == true) return
        if (directory.exists() && !directory.delete()) {
            logger("Assistant meeting transient vault directory could not be removed")
            return
        }
        if (archiveRoot.isDirectory && archiveRoot.listFiles()?.isEmpty() == true && !archiveRoot.delete()) {
            logger("Assistant meeting empty archive root could not be removed")
        }
    }
'''
if store.count(old_cancel) != 1:
    raise SystemExit("Meetings 1.0.5 cancel block changed unexpectedly")
STORE.write_text(store.replace(old_cancel, new_cancel, 1), encoding="utf-8")
