#!/usr/bin/env python3
"""Wire Meetings Start/Stop to Rokid's stock recorder and version glasses hub as 1.4.20."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASSES = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesHub.kt"
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"
CONTROLLER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidNativeRecordingController.kt"
SERVER_AIDL = ROOT / "glasses-hub/src/main/aidl/com/rokid/os/sprite/assist/server/IAssistServer.aidl"
CLIENT_AIDL = ROOT / "glasses-hub/src/main/aidl/com/rokid/os/sprite/assist/client/IAssistClient.aidl"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(GLASSES_GRADLE, "versionCode = 10418", "versionCode = 10420", "versionCode")
replace_once(GLASSES_GRADLE, 'versionName = "1.4.18"', 'versionName = "1.4.20"', "versionName")

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
for item in (
    "RokidNativeRecordingController.REQUEST_PATH",
    "RokidNativeRecordingController.RESULT_PATH",
    "INVALID_ROKID_RECORDING_REQUEST",
):
    if item not in text:
        raise SystemExit(f"Missing generated marker: {item}")

controller = CONTROLLER.read_text(encoding="utf-8")
for item in (
    'put("type", command)',
    'JSONObject().put("audioOpenType", AUDIO_OPEN_TYPE)',
    'cmd_start_audio_record',
    'cmd_stop_audio_record',
    'result_audio_record',
    'IAssistServer.Stub.asInterface',
    'IAssistClient.Stub()',
):
    if item not in controller:
        raise SystemExit(f"Missing stock Rokid control marker: {item}")
if 'put("cmd", command)' in controller:
    raise SystemExit("Legacy incorrect cmd-shaped Rokid payload remains")

server_aidl = SERVER_AIDL.read_text(encoding="utf-8")
client_aidl = CLIENT_AIDL.read_text(encoding="utf-8")
for item in (
    'void registerClient(String packageName, IAssistClient client);',
    'void unRegisterClient(String packageName);',
    'void controlMsgJson(String packageName, String json);',
):
    if item not in server_aidl:
        raise SystemExit(f"Missing Rokid server AIDL marker: {item}")
for item in (
    'void onRegisterResult(String resultJson);',
    'boolean onMessageReceive(String messageJson);',
    'void onDataReceive(String key, String param, in byte[] data);',
):
    if item not in client_aidl:
        raise SystemExit(f"Missing Rokid client AIDL marker: {item}")

print("Applied BuildGround Nexus Glasses 1.4.20 corrected stock Rokid recording control.")
