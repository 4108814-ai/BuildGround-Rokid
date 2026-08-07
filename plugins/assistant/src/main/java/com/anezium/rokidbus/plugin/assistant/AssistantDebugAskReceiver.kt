package com.anezium.rokidbus.plugin.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Test-only entry: feeds a typed question into the live assistant session exactly as
 * if the speech pipeline had produced it, so end-to-end behavior can be exercised
 * from adb without speaking. Unexported — only the shell or this app can reach it
 * (Relay's fake-notification harness is the precedent).
 *
 * The assistant session must be open (assistant launched from the glasses); a
 * broadcast outside a session is logged and dropped.
 *
 *   adb shell am broadcast \
 *     -n com.anezium.rokidbus.plugin.assistant/.AssistantDebugAskReceiver \
 *     -a com.anezium.rokidbus.plugin.assistant.action.DEBUG_ASK \
 *     --es q "remind me in two minutes to check the oven"
 */
class AssistantDebugAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_ASK) return
        val question = intent.getStringExtra(EXTRA_QUESTION)?.trim().orEmpty()
        if (question.isEmpty()) {
            Log.i(TAG, "debug ask dropped: empty question")
            return
        }
        val delivered = AssistantPluginService.debugAsk(question)
        Log.i(TAG, "debug ask delivered=$delivered chars=${question.length}")
    }

    companion object {
        const val ACTION_DEBUG_ASK = "com.anezium.rokidbus.plugin.assistant.action.DEBUG_ASK"
        const val EXTRA_QUESTION = "q"
        private const val TAG = "NexusAssistant"
    }
}
