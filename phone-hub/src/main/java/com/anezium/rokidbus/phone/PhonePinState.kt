package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceValidationResult
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal data class CanonicalPhonePin(
    val ownerPluginId: String,
    val payload: JSONObject,
    val expiresAtMs: Long?,
)

internal sealed interface PhonePinShowResult {
    data class Accepted(
        val pin: CanonicalPhonePin,
        val replacedOwnerPluginId: String?,
    ) : PhonePinShowResult

    data class Rejected(val code: String) : PhonePinShowResult
}

internal sealed interface PhonePinClearResult {
    data class Cleared(val payload: JSONObject) : PhonePinClearResult
    data object Ignored : PhonePinClearResult
}

internal const val HUB_OWNER_ID = "nexus-hub"

/** Canonical single-slot phone state. Transport and scheduling stay in [BusHubService]. */
internal class PhonePinState(
    private val nowMs: () -> Long,
    initialSequence: Long = System.currentTimeMillis(),
) {
    private val sequence = AtomicLong(initialSequence)
    private val lastAcceptedShowByPlugin = mutableMapOf<String, Long>()
    private var active: CanonicalPhonePin? = null

    @Synchronized
    fun show(ownerPluginId: String, payload: JSONObject): PhonePinShowResult {
        val expectedSurfaceId = "$ownerPluginId:${PinSurfaceContract.LOCAL_SURFACE_ID}"
        if (payload.optString("surfaceId") != expectedSurfaceId ||
            payload.optString("localSurfaceId") != PinSurfaceContract.LOCAL_SURFACE_ID ||
            payload.optString("ownerPluginId") != ownerPluginId
        ) {
            return PhonePinShowResult.Rejected(PinSurfaceContract.ERROR_INVALID_PIN)
        }
        val validation = PinSurfaceContract.validateShow(payload)
        if (validation !is PinSurfaceValidationResult.Valid) {
            return PhonePinShowResult.Rejected(PinSurfaceContract.ERROR_INVALID_PIN)
        }

        val now = nowMs()
        val previousAcceptedAt = lastAcceptedShowByPlugin[ownerPluginId]
        if (previousAcceptedAt != null &&
            now - previousAcceptedAt < PinSurfaceContract.MIN_SHOW_INTERVAL_MS
        ) {
            return PhonePinShowResult.Rejected(PinSurfaceContract.ERROR_PIN_RATE_LIMITED)
        }

        val previousOwner = active?.ownerPluginId
        val content = validation.content
        val normalized = PinSurfaceContract.toPayload(expectedSurfaceId, content)
            .put("localSurfaceId", PinSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", ownerPluginId)
            .put("seq", sequence.incrementAndGet())
        val pin = CanonicalPhonePin(
            ownerPluginId = ownerPluginId,
            payload = normalized,
            expiresAtMs = content.ttlMs?.let { now + it },
        )
        active = pin
        lastAcceptedShowByPlugin[ownerPluginId] = now
        return PhonePinShowResult.Accepted(pin, previousOwner?.takeIf { it != ownerPluginId })
    }

    @Synchronized
    fun hide(ownerPluginId: String): PhonePinClearResult =
        if (active?.ownerPluginId == ownerPluginId) clearActive() else PhonePinClearResult.Ignored

    /**
     * Clears the pin when its owner loses the right to hold one (grant revoked,
     * package uninstalled). Losing the owner's bus connection is deliberately NOT
     * a trigger: a pin is canonical hub state, and background plugins are expected
     * to push one and go dormant again. `ttlMs` is what bounds an abandoned pin.
     */
    @Synchronized
    fun ownerLostAccess(ownerPluginId: String): PhonePinClearResult =
        if (active?.ownerPluginId == ownerPluginId) clearActive() else PhonePinClearResult.Ignored

    @Synchronized
    fun ownerPluginId(): String? = active?.ownerPluginId

    @Synchronized
    fun expireIfDue(): PhonePinClearResult {
        val deadline = active?.expiresAtMs ?: return PhonePinClearResult.Ignored
        return if (nowMs() >= deadline) clearActive() else PhonePinClearResult.Ignored
    }

    @Synchronized
    fun expiryDeadlineMs(): Long? = active?.expiresAtMs

    @Synchronized
    fun payloadForResend(): JSONObject? {
        val pin = active ?: return null
        val payload = JSONObject(pin.payload.toString())
        pin.expiresAtMs?.let { deadline ->
            val remaining = (deadline - nowMs()).coerceAtLeast(PinSurfaceContract.MIN_TTL_MS)
            payload.put("ttlMs", remaining.coerceAtMost(PinSurfaceContract.MAX_TTL_MS))
        }
        return payload
    }

    /**
     * Hide payload asserting the empty slot after a reconnect. Without it, a pin
     * cleared while every link was down would survive on the glasses forever.
     */
    @Synchronized
    fun emptySlotHidePayload(): JSONObject? {
        if (active != null) return null
        return JSONObject()
            .put("surfaceId", "$HUB_OWNER_ID:${PinSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", PinSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", HUB_OWNER_ID)
            .put("seq", sequence.incrementAndGet())
    }

    private fun clearActive(): PhonePinClearResult.Cleared {
        val pin = checkNotNull(active)
        active = null
        return PhonePinClearResult.Cleared(
            JSONObject()
                .put("surfaceId", "${pin.ownerPluginId}:${PinSurfaceContract.LOCAL_SURFACE_ID}")
                .put("localSurfaceId", PinSurfaceContract.LOCAL_SURFACE_ID)
                .put("ownerPluginId", pin.ownerPluginId)
                .put("seq", sequence.incrementAndGet()),
        )
    }
}
