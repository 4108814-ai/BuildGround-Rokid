package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Credentials for the glasses-owned Wi-Fi Direct group that carries a media-sync session.
 * Deliberately independent from [CameraLinkEndpointOffer]: the camera link owns its own
 * transport modes and must stay free to evolve without dragging photo sync along.
 */
data class MediaSyncLinkOffer(
    val sessionId: String,
    val ssid: String,
    val passphrase: String,
    val goIp: String,
    val port: Int,
    val token: String,
)

object MediaSyncLinkOfferContract {
    const val VERSION = 1

    fun encode(offer: MediaSyncLinkOffer): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("sessionId", offer.sessionId)
        .put("ssid", offer.ssid)
        .put("passphrase", offer.passphrase)
        .put("goIp", offer.goIp)
        .put("port", offer.port)
        .put("token", offer.token)

    fun decode(payload: JSONObject): MediaSyncLinkOffer? {
        if (payload.optInt("version") != VERSION) return null
        val sessionId = payload.optString("sessionId")
        val ssid = payload.optString("ssid")
        val passphrase = payload.optString("passphrase")
        val goIp = payload.optString("goIp")
        val token = payload.optString("token")
        val port = payload.optInt("port")
        if (sessionId.isBlank() || sessionId.length > 128) return null
        if (ssid.isBlank() || ssid.length > 128) return null
        if (passphrase.length !in 8..128) return null
        if (goIp.isBlank() || goIp.length > 64) return null
        if (token.length !in 16..256) return null
        if (port !in 1..65535) return null
        return MediaSyncLinkOffer(sessionId, ssid, passphrase, goIp, port, token)
    }
}
