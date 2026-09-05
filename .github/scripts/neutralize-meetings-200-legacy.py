#!/usr/bin/env python3
"""Remove dormant Assistant-only compile hooks from the thin Meetings 2.0 build."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
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

text = DEBUG_RECEIVER.read_text(encoding="utf-8")
if "AssistantPluginService.debugAsk" in text:
    raise SystemExit("Legacy debugAsk dependency remains")
if "BroadcastReceiver" not in text:
    raise SystemExit("Debug receiver compatibility stub was not written")

print("Neutralized dormant Assistant debug hook for Meetings 2.0.")
