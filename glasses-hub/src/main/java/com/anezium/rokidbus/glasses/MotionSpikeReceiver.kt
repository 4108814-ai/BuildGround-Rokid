package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject

/**
 * Trigger for [MotionSpikeRenderer].
 *
 * Lives in the main source set rather than beside the debug probe receiver for
 * one practical reason: the accessibility overlay window only exists in the
 * armed production hub, and that hub is release-signed, so a debug build
 * cannot be installed over it. Testing the real window means shipping the
 * trigger in the real build. It comes back out before any of this merges.
 */
class MotionSpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sequence = intent.getStringExtra("seq").orEmpty().ifEmpty { "loop" }
        if (sequence.startsWith("notice")) {
            log("Notice spike request: $sequence -> ${playNotice(sequence)}")
            return
        }
        log("Motion spike request: $sequence -> ${MotionSpikeRenderer.play(sequence)}")
    }

    /**
     * Feeds the controller the envelope the phone would have sent, so the band
     * can be looked at without driving the sample by hand on the glasses. Spike
     * only; it leaves with the rest of the scaffolding.
     */
    private fun playNotice(sequence: String): String {
        val path = when (sequence) {
            "notice" -> BusPaths.NOTICE_SHOW
            "notice-update" -> BusPaths.NOTICE_UPDATE
            "notice-hide" -> BusPaths.NOTICE_HIDE
            else -> return "use notice, notice-update or notice-hide"
        }
        val payload = JSONObject()
            .put("surfaceId", "spike:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
            .put("ownerPluginId", "spike")
            .put("seq", SystemClock.elapsedRealtime())
        when (path) {
            BusPaths.NOTICE_SHOW -> payload
                .put("kind", NoticeSurfaceContract.KIND)
                .put("title", "Marie")
                .put("body", "Je suis en route, dix minutes. Tu as toujours besoin du chargeur ?")
                .put("footer", "Retour pour ignorer")
                .put("ttlMs", 12_000L)
            BusPaths.NOTICE_UPDATE -> payload.put("footer", "J'écoute…")
        }
        NoticeController.handleNoticeEnvelope(BusEnvelope(path = path, payload = payload))
        return "sent $path"
    }
}
