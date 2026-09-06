#!/usr/bin/env python3
"""Add a read-only probe for Rokid's finalised stock audio recording files."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "glasses-hub/build.gradle.kts"
CONTROLLER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidNativeRecordingController.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(GRADLE, "versionCode = 10424", "versionCode = 10425", "versionCode")
replace_once(GRADLE, 'versionName = "1.4.24"', 'versionName = "1.4.25"', "versionName")

replace_once(
    CONTROLLER,
    "import org.json.JSONObject\nimport java.util.ArrayDeque\n",
    "import org.json.JSONObject\nimport java.io.File\nimport java.util.ArrayDeque\n",
    "File import",
)

replace_once(
    CONTROLLER,
    '    private const val KEY_CHANGED_AT = "changed_at"\n',
    '    private const val KEY_CHANGED_AT = "changed_at"\n'
    '    private const val KEY_LAST_STARTED_AT = "last_started_at"\n'
    '    private const val RECORDINGS_DIR = "/sdcard/Recordings"\n'
    '    private const val RECORDING_MTIME_TOLERANCE_MS = 2_000L\n',
    "probe constants",
)

replace_once(
    CONTROLLER,
    '        if (action != "start" && action != "stop" && action != "status") return false\n',
    '        if (action != "start" && action != "stop" && action != "status" && action != "probe") return false\n',
    "probe action allowlist",
)

status_block = '''            if (action == "status") {
                val active = readRecordingStateLocked()
                confirmedRecording = active
                reply(successPayload(action = action, recording = active, phase = "state"))
                return true
            }
'''
probe_block = status_block + '''
            if (action == "probe") {
                val active = readRecordingStateLocked()
                confirmedRecording = active
                reply(recordingFileProbeLocked(active))
                return true
            }
'''
replace_once(CONTROLLER, status_block, probe_block, "probe handler")

old_write = '''    private fun writeRecordingStateLocked(active: Boolean) {
        val context = applicationContext ?: return
        // commit() is deliberate: status queried immediately after a HUD reopen must observe the
        // state before we tell the phone that the command completed.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()
    }
'''
new_write = '''    private fun writeRecordingStateLocked(active: Boolean) {
        val context = applicationContext ?: return
        val now = System.currentTimeMillis()
        // commit() is deliberate: status queried immediately after a HUD reopen must observe the
        // state before we tell the phone that the command completed.
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .putLong(KEY_CHANGED_AT, now)
        if (active) editor.putLong(KEY_LAST_STARTED_AT, now)
        editor.commit()
    }

    /**
     * Read-only discriminator for Meetings: does the finalised stock Rokid file become visible to
     * NEXUS in the documented /sdcard/Recordings directory after Stop? No bytes are copied here.
     */
    private fun recordingFileProbeLocked(recording: Boolean): JSONObject {
        val context = applicationContext
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val startedAt = prefs?.getLong(KEY_LAST_STARTED_AT, 0L) ?: 0L
        val directory = File(RECORDINGS_DIR)
        val readable = directory.isDirectory && directory.canRead()
        val files = if (readable) {
            runCatching {
                directory.listFiles()
                    ?.asSequence()
                    ?.filter { file ->
                        file.isFile &&
                            !file.name.endsWith(".tmp", ignoreCase = true) &&
                            file.length() > 0L &&
                            (startedAt <= 0L || file.lastModified() >= startedAt - RECORDING_MTIME_TOLERANCE_MS)
                    }
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val latest = files.maxByOrNull(File::lastModified)
        return successPayload(action = "probe", recording = recording, phase = "file_probe")
            .put("directory", RECORDINGS_DIR)
            .put("directoryReadable", readable)
            .put("sessionStartedAt", startedAt)
            .put("candidateCount", files.size)
            .put("fileFound", latest != null)
            .apply {
                if (latest != null) {
                    put("fileName", latest.name)
                    put("filePath", latest.absolutePath)
                    put("fileSize", latest.length())
                    put("fileModifiedAt", latest.lastModified())
                }
            }
    }
'''
replace_once(CONTROLLER, old_write, new_write, "recording state writer and probe")

text = CONTROLLER.read_text(encoding="utf-8")
gradle = GRADLE.read_text(encoding="utf-8")
for required in (
    'versionCode = 10425',
    'versionName = "1.4.25"',
):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.25 version marker: {required}")

for required in (
    'action != "start" && action != "stop" && action != "status" && action != "probe"',
    'RECORDINGS_DIR = "/sdcard/Recordings"',
    'recordingFileProbeLocked(active)',
    'directory.listFiles()',
    'put("fileFound", latest != null)',
    'KEY_LAST_STARTED_AT',
):
    if required not in text:
        raise SystemExit(f"Missing recorder file probe marker: {required}")

for forbidden in (
    'FileOutputStream(',
    '.delete()',
    'MediaRecorder(',
    'AudioRecord(',
):
    if forbidden in text:
        raise SystemExit(f"Recorder probe must stay read-only: {forbidden}")

print("Applied BuildGround Nexus Glasses 1.4.25 read-only Rokid recorder file probe.")
