#!/usr/bin/env python3
"""Fix native recorder state ownership and return the glasses display to standby."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"
CONTROLLER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidNativeRecordingController.kt"
ACCESSIBILITY = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# This script runs after the proven 1.4.20 native-recorder bridge has been applied.
replace_once(GLASSES_GRADLE, "versionCode = 10420", "versionCode = 10421", "versionCode")
replace_once(GLASSES_GRADLE, 'versionName = "1.4.20"', 'versionName = "1.4.21"', "versionName")

# 1.4.20 waited ten seconds for result_audio_record before letting Meetings remember that
# recording was active. On the physical RV101 the stock recorder starts and stores the file, but
# that callback is not delivered to our registered client; the timeout therefore made Meetings
# forget the still-running native recorder. Binder dispatch is the command boundary that the
# working community bridges use. Treat a successful controlMsgJson call as accepted/confirmed,
# while still accepting a synchronous result_audio_record if firmware happens to provide one.
old_dispatch = '''    private fun dispatchNextLocked() {
        if (inFlight != null) return
        val current = server ?: return
        if (!registered) {
            registerLocked(current)
            if (!registered) return
        }
        val context = applicationContext ?: return
        if (queued.isEmpty()) return
        val request = queued.removeFirst()
        val command = if (request.action == "start") CMD_START else CMD_STOP
        val json = JSONObject()
            .put("type", command)
            .put(
                "data",
                JSONObject().put("audioOpenType", AUDIO_OPEN_TYPE),
            )
            .toString()

        // Arm correlation before the Binder call. Some firmware builds may emit the result from
        // inside controlMsgJson(), so setting inFlight afterwards would lose a synchronous ack.
        inFlight = request
        request.reply(
            JSONObject()
                .put("action", request.action)
                .put("accepted", true)
                .put("confirmed", false)
                .put("phase", "dispatched")
                .put("owner", "rokid"),
        )
        scheduleTimeoutLocked(request)

        val sent = runCatching {
            current.controlMsgJson(context.packageName, json)
        }
        if (sent.isFailure) {
            // If a synchronous native callback already completed this request, do not overwrite it.
            if (inFlight !== request) return
            cancelTimeoutLocked()
            inFlight = null
            val error = sent.exceptionOrNull()
            Log.w(TAG, "native command failed action=${request.action}", error)
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", false)
                    .put("confirmed", false)
                    .put("phase", "error")
                    .put("error", "ROKID_RECORDING_COMMAND_FAILED")
                    .put("detail", error?.javaClass?.simpleName ?: "unknown"),
            )
            dispatchNextLocked()
        }
    }
'''
new_dispatch = '''    private fun dispatchNextLocked() {
        if (inFlight != null) return
        val current = server ?: return
        if (!registered) {
            registerLocked(current)
            if (!registered) return
        }
        val context = applicationContext ?: return
        if (queued.isEmpty()) return
        val request = queued.removeFirst()
        val command = if (request.action == "start") CMD_START else CMD_STOP
        val json = JSONObject()
            .put("type", command)
            .put(
                "data",
                JSONObject().put("audioOpenType", AUDIO_OPEN_TYPE),
            )
            .toString()

        // Arm correlation before the Binder call because some firmware may answer synchronously.
        // Crucially, do not make the phone-side recording state depend on an asynchronous callback:
        // the physical RV101 starts/stops the stock recorder correctly without delivering that
        // callback to this client. A successful Binder dispatch is therefore our control boundary.
        inFlight = request
        request.reply(
            JSONObject()
                .put("action", request.action)
                .put("accepted", true)
                .put("confirmed", false)
                .put("phase", "dispatched")
                .put("owner", "rokid"),
        )

        val sent = runCatching {
            current.controlMsgJson(context.packageName, json)
        }
        if (sent.isFailure) {
            // If a synchronous native callback already completed this request, do not overwrite it.
            if (inFlight !== request) return
            cancelTimeoutLocked()
            inFlight = null
            val error = sent.exceptionOrNull()
            Log.w(TAG, "native command failed action=${request.action}", error)
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", false)
                    .put("confirmed", false)
                    .put("phase", "error")
                    .put("error", "ROKID_RECORDING_COMMAND_FAILED")
                    .put("detail", error?.javaClass?.simpleName ?: "unknown"),
            )
            dispatchNextLocked()
            return
        }

        // A synchronous result_audio_record may already have cleared this request. Otherwise the
        // successful stock Binder command itself confirms the control state for Meetings.
        if (inFlight === request) {
            cancelTimeoutLocked()
            inFlight = null
            confirmedRecording = request.action == "start"
            request.reply(
                JSONObject()
                    .put("action", request.action)
                    .put("accepted", true)
                    .put("confirmed", true)
                    .put("recording", confirmedRecording)
                    .put("phase", "binder_dispatch")
                    .put("owner", "rokid"),
            )
        }
        if (request.action == "start") scheduleDisplaySleep()
        dispatchNextLocked()
    }
'''
replace_once(CONTROLLER, old_dispatch, new_dispatch, "native recorder dispatch")

replace_once(
    CONTROLLER,
    '    private const val NATIVE_RESULT_TIMEOUT_MS = 10_000L\n',
    '    private const val NATIVE_RESULT_TIMEOUT_MS = 10_000L\n'
    '    private const val DISPLAY_SLEEP_DELAY_MS = 1_200L\n',
    "display sleep delay",
)

schedule_marker = '''    private fun scheduleTimeoutLocked(request: Request) {
'''
schedule_sleep = '''    private fun scheduleDisplaySleep() {
        mainHandler.postDelayed(
            {
                // A rapid Stop before the delayed lock wins; never darken the display after the
                // user has already ended the native recording.
                if (confirmedRecording != true) return@postDelayed
                val requested = RokidBusAccessibilityService.requestDisplaySleep()
                Log.i(TAG, "native recorder post-start display sleep requested=$requested")
            },
            DISPLAY_SLEEP_DELAY_MS,
        )
    }

'''
replace_once(CONTROLLER, schedule_marker, schedule_sleep + schedule_marker, "display sleep hook")

accessibility_marker = '''        /** True while the AccessibilityService is connected and able to drive Settings. */
        internal fun isLive(): Boolean = liveInstance != null
'''
accessibility_replacement = accessibility_marker + '''

        /**
         * Return the waveguide to standby after the stock recorder has been started remotely.
         * Recording ownership remains with Rokid's system service; this only locks the display,
         * using the same Android accessibility global action already used by Nexus notice sleep.
         */
        internal fun requestDisplaySleep(): Boolean {
            val service = liveInstance ?: return false
            service.main.post {
                val locked = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                log("native recorder display sleep locked=$locked")
            }
            return true
        }
'''
replace_once(ACCESSIBILITY, accessibility_marker, accessibility_replacement, "accessibility display sleep")

controller = CONTROLLER.read_text(encoding="utf-8")
accessibility = ACCESSIBILITY.read_text(encoding="utf-8")
gradle = GLASSES_GRADLE.read_text(encoding="utf-8")

for required in (
    'versionCode = 10421',
    'versionName = "1.4.21"',
):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.21 version marker: {required}")

for required in (
    '.put("phase", "binder_dispatch")',
    'if (request.action == "start") scheduleDisplaySleep()',
    'DISPLAY_SLEEP_DELAY_MS = 1_200L',
    'RokidBusAccessibilityService.requestDisplaySleep()',
):
    if required not in controller:
        raise SystemExit(f"Missing 1.4.21 controller marker: {required}")

for required in (
    'internal fun requestDisplaySleep(): Boolean',
    'AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN',
):
    if required not in accessibility:
        raise SystemExit(f"Missing 1.4.21 accessibility marker: {required}")

# The ten-second timeout helper may remain as dead compatibility code for a synchronous native
# result path, but dispatchNextLocked must no longer arm it.
dispatch_body = controller.split('    private fun dispatchNextLocked() {', 1)[1].split(
    '    private fun scheduleDisplaySleep()', 1
)[0]
if 'scheduleTimeoutLocked(request)' in dispatch_body:
    raise SystemExit("1.4.21 still gates recorder state on the ten-second native callback")

print("Applied BuildGround Nexus Glasses 1.4.21 native recorder state/display fix.")
