package com.anezium.rokidbus.plugin.relay

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FakeReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FakeNotificationHarness.ACTION_FAKE_REPLY) return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(FakeNotificationHarness.RESULT_REPLY)
            ?.toString()
            .orEmpty()
        FakeNotificationHarness.receiveReply(reply)
    }
}
