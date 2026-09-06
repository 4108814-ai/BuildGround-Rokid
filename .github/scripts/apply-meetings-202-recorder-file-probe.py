#!/usr/bin/env python3
"""Add the read-only Rokid recorder-file discriminator to Meetings 2.0.2."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"
GRADLE = ROOT / "plugins/assistant/build.gradle.kts"
MANIFEST = ROOT / "plugins/assistant/src/main/AndroidManifest.xml"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(GRADLE, "versionCode = 28", "versionCode = 29", "versionCode")
replace_once(GRADLE, 'versionName = "2.0.1"', 'versionName = "2.0.2"', "versionName")

replace_once(
    SERVICE,
    "    private var pendingTimeout: Runnable? = null\n    private var transientMessage: String? = null\n",
    "    private var pendingTimeout: Runnable? = null\n    private var probeRunnable: Runnable? = null\n    private var transientMessage: String? = null\n",
    "probe runnable state",
)

replace_once(
    SERVICE,
    "        clearPending()\n        recordingState = null\n        transientMessage = null\n        request(\"status\")\n",
    "        clearPending()\n        clearProbe()\n        recordingState = null\n        transientMessage = null\n        request(\"status\")\n",
    "open cleanup",
)

replace_once(
    SERVICE,
    "        noticeVisible = false\n        clearPending()\n        recordingState = null\n    }\n\n    override fun onNexusInput",
    "        noticeVisible = false\n        clearPending()\n        clearProbe()\n        recordingState = null\n    }\n\n    override fun onNexusInput",
    "close cleanup",
)

replace_once(
    SERVICE,
    "        noticeVisible = false\n        clearPending()\n        recordingState = null\n    }\n\n    override fun onNexusMessage",
    "        noticeVisible = false\n        clearPending()\n        clearProbe()\n        recordingState = null\n    }\n\n    override fun onNexusMessage",
    "notice close cleanup",
)

old_result = '''        recordingState = payload.optBoolean("recording", false)
        transientMessage = when (action) {
            "start" -> if (recordingState == true) "Запись запущена" else "Запуск не подтверждён состоянием Rokid"
            "stop" -> if (recordingState == false) "Запись остановлена" else "Остановка не подтверждена состоянием Rokid"
            else -> null
        }
        showControl()
'''
new_result = '''        recordingState = payload.optBoolean("recording", recordingState ?: false)
        if (action == "probe") {
            transientMessage = when {
                !payload.optBoolean("directoryReadable", false) ->
                    "NEXUS не читает /sdcard/Recordings"
                payload.optBoolean("fileFound", false) -> {
                    val name = payload.optString("fileName").ifBlank { "аудиофайл" }
                    val size = payload.optLong("fileSize", 0L)
                    "Файл Rokid найден: $name · ${formatBytes(size)}"
                }
                else -> "Папка Rokid доступна, файл текущей записи не найден"
            }
            showControl()
            return
        }

        transientMessage = when (action) {
            "start" -> if (recordingState == true) "Запись запущена" else "Запуск не подтверждён состоянием Rokid"
            "stop" -> if (recordingState == false) "Запись остановлена — жду файл Rokid…" else "Остановка не подтверждена состоянием Rokid"
            else -> null
        }
        showControl()
        if (action == "stop" && recordingState == false) scheduleProbe()
'''
replace_once(SERVICE, old_result, new_result, "probe result handling")

replace_once(
    SERVICE,
    "    override fun onDestroy() {\n        clearPending()\n        super.onDestroy()\n    }\n",
    "    override fun onDestroy() {\n        clearPending()\n        clearProbe()\n        super.onDestroy()\n    }\n",
    "destroy cleanup",
)

replace_once(
    SERVICE,
    '''            pending == "stop" -> {
                lines = listOf("Останавливаю штатную запись Rokid…")
                actions = emptyList()
            }
''',
    '''            pending == "stop" -> {
                lines = listOf("Останавливаю штатную запись Rokid…")
                actions = emptyList()
            }
            pending == "probe" -> {
                lines = listOf("Ищу файл штатной записи Rokid…")
                actions = emptyList()
            }
''',
    "probe pending UI",
)

replace_once(
    SERVICE,
    '''            transientMessage = when (action) {
                "status" -> "Нет ответа состояния от NEXUS Glasses"
                "start" -> "Нет ответа на запуск"
                else -> "Нет ответа на остановку"
            }
''',
    '''            transientMessage = when (action) {
                "status" -> "Нет ответа состояния от NEXUS Glasses"
                "start" -> "Нет ответа на запуск"
                "stop" -> "Нет ответа на остановку"
                else -> "Нет ответа на поиск файла Rokid"
            }
''',
    "probe timeout UI",
)

marker = '''    private fun clearPending() {
        pendingTimeout?.let(handler::removeCallbacks)
        pendingTimeout = null
        pendingId = null
        pendingAction = null
    }

'''
addition = marker + '''    private fun scheduleProbe() {
        clearProbe()
        val task = Runnable {
            probeRunnable = null
            if (recordingState == false && pendingId == null) request("probe")
        }
        probeRunnable = task
        handler.postDelayed(task, PROBE_DELAY_MS)
    }

    private fun clearProbe() {
        probeRunnable?.let(handler::removeCallbacks)
        probeRunnable = null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f МБ".format(bytes.toDouble() / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f КБ".format(bytes.toDouble() / 1024.0)
        else -> "$bytes Б"
    }

'''
replace_once(SERVICE, marker, addition, "probe scheduling helpers")

replace_once(
    SERVICE,
    "        private const val REQUEST_TIMEOUT_MS = 5_000L\n        private const val NOTICE_TTL_MS = 30_000L\n",
    "        private const val REQUEST_TIMEOUT_MS = 5_000L\n        private const val PROBE_DELAY_MS = 2_000L\n        private const val NOTICE_TTL_MS = 30_000L\n",
    "probe delay constant",
)

service = SERVICE.read_text(encoding="utf-8")
gradle = GRADLE.read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")
for required in (
    'versionCode = 29',
    'versionName = "2.0.2"',
):
    if required not in gradle:
        raise SystemExit(f"Missing Meetings 2.0.2 version marker: {required}")

for required in (
    'request("probe")',
    'PROBE_DELAY_MS = 2_000L',
    'Файл Rokid найден:',
    'NEXUS не читает /sdcard/Recordings',
    'Папка Rokid доступна, файл текущей записи не найден',
    'Ищу файл штатной записи Rokid…',
):
    if required not in service:
        raise SystemExit(f"Missing Meetings 2.0.2 probe marker: {required}")

for forbidden in (
    "NexusAudioSession",
    "NexusSpeechSession",
    "meetingRecorder",
    "transcriber",
    "MediaRecorder",
    "AudioRecord",
):
    if forbidden in service:
        raise SystemExit(f"Forbidden capture/runtime remains in Meetings 2.0.2: {forbidden}")

if 'android:value="surfaces"' not in manifest:
    raise SystemExit("Meetings 2.0.2 must remain surfaces-only")
if "microphone" in manifest or "stt" in manifest or "tts" in manifest:
    raise SystemExit("Meetings 2.0.2 manifest still requests audio/AI capabilities")

print("Applied Meetings 2.0.2 read-only Rokid recorder-file probe UI.")
