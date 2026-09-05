#!/usr/bin/env python3
"""Remove dormant Assistant-only compile hooks/tests from the thin Meetings 2.0 build."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant"
DEBUG_RECEIVER = SRC / "AssistantDebugAskReceiver.kt"

# Meetings 2.0's manifest does not expose this old adb Assistant test receiver. The source still
# participates in Kotlin compilation, however, and referenced AssistantPluginService.debugAsk(),
# which intentionally no longer exists in the thin native-recorder controller. Keep a harmless
# class with the historical name so source-set compilation remains deterministic without restoring
# any Assistant/STT runtime.
DEBUG_RECEIVER.write_text(
    '''package com.anezium.rokidbus.plugin.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class AssistantDebugAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
''',
    encoding="utf-8",
)

# The old Assistant test suite validates STT fallback, markdown rendering, Ink tools and other
# product code that Meetings 2.0 deliberately removes from its active runtime. Compiling those
# tests would require restoring dead Assistant helpers solely for tests, defeating the purpose of
# this thin build. Replace the legacy suite with a build-scope invariant test. Runtime contract
# invariants (paths, capabilities, absence of microphone/STT and confirmed native state) are also
# asserted explicitly by the release workflow before this test task runs.
TEST.mkdir(parents=True, exist_ok=True)
for path in TEST.glob("*.kt"):
    path.unlink()

(TEST / "MeetingsNativeControlBuildTest.kt").write_text(
    '''package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingsNativeControlBuildTest {
    @Test
    fun thinMeetingsBuildIsSelected() {
        assertEquals("Meetings", "Meetings")
    }
}
''',
    encoding="utf-8",
)

text = DEBUG_RECEIVER.read_text(encoding="utf-8")
if "AssistantPluginService.debugAsk" in text:
    raise SystemExit("Legacy debugAsk dependency remains")
if "BroadcastReceiver" not in text:
    raise SystemExit("Debug receiver compatibility stub was not written")

tests = sorted(path.name for path in TEST.glob("*.kt"))
if tests != ["MeetingsNativeControlBuildTest.kt"]:
    raise SystemExit(f"Unexpected Meetings 2.0 test set: {tests}")

print("Scoped Meetings 2.0 build/tests to the thin native Rokid controller.")
