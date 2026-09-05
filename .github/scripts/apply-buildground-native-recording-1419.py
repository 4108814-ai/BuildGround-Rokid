#!/usr/bin/env python3
"""Wire Meetings Start/Stop to Rokid's stock recorder and version glasses hub as 1.4.19."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASSES = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesHub.kt"
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(GLASSES_GRADLE, "versionCode = 10418", "versionCode = 10419", "versionCode")
replace_once(GLASSES_GRADLE, 'versionName = "1.4.18"', 'versionName = "1.4.19"', "versionName")

marker = """        if (envelope.path == NativeAppContract.REQUEST_PATH) {
"""
route = """        if (envelope.path == RokidNativeRecordingController.REQUEST_PATH) {
            val context = appContext
            val handled = context != null && envelope.binary == null &&
                RokidNativeRecordingController.handle(context, envelope.payload) { payload ->
                    sendRemote(
                        BusEnvelope(
                            path = RokidNativeRecordingController.RESULT_PATH,
                            id = envelope.id,
                            payload = payload,
                        ),
                    ) == null
                }
            if (!handled) {
                sendRemote(errorEnvelope(envelope.id, "INVALID_ROKID_RECORDING_REQUEST"))
            }
            return
        }
"""
replace_once(GLASSES, marker, route + marker, "native recording route")

text = GLASSES.read_text(encoding="utf-8")
required = (
    "RokidNativeRecordingController.REQUEST_PATH",
    "RokidNativeRecordingController.RESULT_PATH",
    "INVALID_ROKID_RECORDING_REQUEST",
)
for item in required:
    if item not in text:
        raise SystemExit(f"Missing generated marker: {item}")

controller = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidNativeRecordingController.kt"
controller_text = controller.read_text(encoding="utf-8")
for item in (
    "cmd_start_audio_record",
    "cmd_stop_audio_record",
    "com.rokid.os.sprite.assist.server.IAssistServer",
    "TRANSACTION_CONTROL_MSG_JSON",
):
    if item not in controller_text:
        raise SystemExit(f"Missing stock Rokid control marker: {item}")

print("Applied BuildGround Nexus Glasses 1.4.19 stock Rokid recording control.")
