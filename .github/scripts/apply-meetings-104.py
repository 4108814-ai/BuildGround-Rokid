from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / ".github/scripts/apply-meetings-103.py"
PLUGIN_BASE = ROOT / "bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginService.kt"
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"

runpy.run_path(str(BASE), run_name="__main__")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# NexusPluginService historically treated every plugin-session close as the end of every
# microphone lease. Meetings 1.0.3 intentionally lets its transient HUD disappear while a
# meeting remains active, so that default tears down the source-audio session underneath it.
# Add opt-in lifecycle hooks. Defaults preserve every other Nexus plugin unchanged.
replace_once(
    PLUGIN_BASE,
    "    protected open val hubTarget: HubTarget = HubTarget.PHONE\n\n"
    "    protected val isNexusSessionOpen: Boolean\n",
    "    protected open val hubTarget: HubTarget = HubTarget.PHONE\n\n"
    "    /** Keep a live raw-audio lease across a transient plugin UI close. Opt-in only. */\n"
    "    protected open fun retainNexusAudioOnClose(): Boolean = false\n\n"
    "    /** Keep the Android foreground-service anchor while retained work is still active. */\n"
    "    protected open fun retainNexusForegroundOnClose(): Boolean = false\n\n"
    "    protected val isNexusSessionOpen: Boolean\n",
    "plugin lifecycle retention hooks",
)

replace_once(
    PLUGIN_BASE,
    '''    final override fun onClose() {
        client?.releaseAudioSession()
        client?.releaseSpeechSession()
        client?.releaseTtsSession()
        client?.releaseSnapshotSession()
        try {
            onNexusClose()
        } finally {
            sessionOpen = false
            stopNexusSessionForeground()
        }
    }
''',
    '''    final override fun onClose() {
        val retainAudio = retainNexusAudioOnClose()
        if (!retainAudio) client?.releaseAudioSession()
        client?.releaseSpeechSession()
        client?.releaseTtsSession()
        client?.releaseSnapshotSession()
        try {
            onNexusClose()
        } finally {
            sessionOpen = false
            if (!retainNexusForegroundOnClose()) stopNexusSessionForeground()
        }
    }
''',
    "plugin close retention",
)

# Meetings owns the raw NexusAudioSession for as long as the meeting state is active or its
# explicit Stop is draining final frames. HUD/Notice visibility is not an ownership boundary.
close_marker = "    override fun onNexusClose() {\n"
text = SERVICE.read_text(encoding="utf-8")
if text.count(close_marker) != 1:
    raise SystemExit("Meetings onNexusClose marker missing")
retention = '''    override fun retainNexusAudioOnClose(): Boolean =
        meetingRecorder.active || meetingStopPending

    override fun retainNexusForegroundOnClose(): Boolean =
        meetingRecorder.active || meetingStopPending

'''
text = text.replace(close_marker, retention + close_marker, 1)
SERVICE.write_text(text, encoding="utf-8")

# If an explicit Stop finishes after the HUD session has already gone away, release the
# foreground anchor here; otherwise the base service correctly releases it on the next close.
replace_once(
    SERVICE,
    '''    private fun completeMeetingsNotice(message: String) {
        meetingsStopping = false
        meetingsNoticeTickerJob?.cancel()
        meetingsNoticeTickerJob = null
        meetingsStartedElapsedRealtimeMs = null
        meetingsCompletion = message
        showMeetingsNotice()
    }
''',
    '''    private fun completeMeetingsNotice(message: String) {
        meetingsStopping = false
        meetingsNoticeTickerJob?.cancel()
        meetingsNoticeTickerJob = null
        meetingsStartedElapsedRealtimeMs = null
        meetingsCompletion = message
        showMeetingsNotice()
        if (!meetingRecorder.active && !meetingStopPending && !isNexusSessionOpen) {
            stopNexusSessionForeground()
        }
    }
''',
    "foreground release after background finalize",
)

# Version-specific comments and invariants.
text = SERVICE.read_text(encoding="utf-8").replace(
    "Meetings 1.0.3 is explicitly button-controlled.",
    "Meetings 1.0.4 is explicitly button-controlled.",
)
SERVICE.write_text(text, encoding="utf-8")

base_text = PLUGIN_BASE.read_text(encoding="utf-8")
service_text = SERVICE.read_text(encoding="utf-8")
for marker in (
    "protected open fun retainNexusAudioOnClose(): Boolean = false",
    "protected open fun retainNexusForegroundOnClose(): Boolean = false",
    "if (!retainAudio) client?.releaseAudioSession()",
):
    if marker not in base_text:
        raise SystemExit(f"Meetings 1.0.4 base lifecycle marker missing: {marker}")
for marker in (
    "override fun retainNexusAudioOnClose(): Boolean =",
    "meetingRecorder.active || meetingStopPending",
    "stopNexusSessionForeground()",
):
    if marker not in service_text:
        raise SystemExit(f"Meetings 1.0.4 service marker missing: {marker}")
