package com.anezium.rokidbus.client.plugin

import android.content.Context
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceValidationResult
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.CapabilityParseResult
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

class NexusPluginClient internal constructor(
    private val pluginId: String,
    private val callbacks: NexusPluginCallbacks,
    private val transport: NexusPluginTransport,
) : NexusPluginTransport.Listener, AutoCloseable {
    private val seenEventIds = ArrayDeque<String>()
    private val seenEventIdSet = linkedSetOf<String>()
    private val audioSessionLock = Any()
    private val speechSessionLock = Any()
    private var registrationState = PluginRegistrationResult.REGISTRATION_FAILED
    private var opened = false
    private var closed = false
    private var approvedCapabilities: Set<PluginCapability> = emptySet()
    private var registeredAudioSession: NexusAudioSession? = null
    private var audioSessionApiUsed = false
    private var registeredSpeechSession: NexusSpeechSession? = null
    private var speechSessionApiUsed = false
    @Volatile private var currentLinkState = 0
    @Volatile private var hubCapabilities = 0

    val isApproved: Boolean
        get() = registrationState == PluginRegistrationResult.APPROVED

    fun hasCapability(capability: PluginCapability): Boolean =
        isApproved && capability in approvedCapabilities

    val supportsImageSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.IMAGE_SURFACE != 0

    /**
     * Whether these glasses can show a pin — not whether one would appear this instant.
     * Unlike [supportsImageSurface] this ignores the link: a pin pushed while the glasses
     * are asleep is held by the hub and delivered when they come back, so refusing here
     * would strand exactly the background plugins pins exist for.
     */
    val supportsPinSurface: Boolean
        get() = hubCapabilities and BusCapabilityBits.PIN_SURFACE != 0

    fun showPin(pin: NexusPin): NexusSdkResult {
        pinPreflight()?.let { return it }
        val payload = pin.toPayload()
        if (PinSurfaceContract.validateShow(payload) !is PinSurfaceValidationResult.Valid) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (send(BusPaths.PIN_SHOW, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun hidePin(): NexusSdkResult {
        pinPreflight()?.let { return it }
        return if (
            send(
                BusPaths.PIN_HIDE,
                UUID.randomUUID().toString(),
                JSONObject().put("surfaceId", PinSurfaceContract.LOCAL_SURFACE_ID),
            )
        ) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }

    fun connect() {
        check(!closed) { "NexusPluginClient is closed" }
        transport.connect(this)
    }

    fun send(path: String, id: String, payload: JSONObject): Boolean {
        if (closed || !isApproved) return false
        return transport.send(path, id, payload)
    }

    internal fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean {
        if (closed || !isApproved) return false
        val sent = transport.sendBinary(path, id, payload, data)
        if (!sent) {
            currentLinkState = currentLinkState and LinkStateBits.SPP_DATA_UP.inv()
            hubCapabilities = transport.capabilities()
        }
        return sent
    }

    internal fun isApprovedForAudio(): Boolean = !closed && isApproved

    internal fun isApprovedForSpeech(): Boolean = !closed && isApproved

    internal fun registerAudioSession(session: NexusAudioSession): Boolean =
        synchronized(audioSessionLock) {
            if (closed || registeredAudioSession?.let { it !== session } == true) {
                false
            } else {
                registeredAudioSession = session
                audioSessionApiUsed = true
                true
            }
        }

    internal fun unregisterAudioSession(session: NexusAudioSession) {
        synchronized(audioSessionLock) {
            if (registeredAudioSession === session) registeredAudioSession = null
        }
    }

    internal fun sendAudioAcquire(session: NexusAudioSession, id: String): Boolean {
        if (synchronized(audioSessionLock) { registeredAudioSession !== session }) return false
        return send(NEXUS_AUDIO_LEASE_ACQUIRE_PATH, id, JSONObject())
    }

    internal fun sendAudioRelease(
        session: NexusAudioSession,
        id: String,
        leaseId: String,
    ): Boolean {
        if (synchronized(audioSessionLock) { registeredAudioSession !== session }) return false
        return send(
            NEXUS_AUDIO_LEASE_RELEASE_PATH,
            id,
            JSONObject().put("leaseId", leaseId),
        )
    }

    internal fun releaseAudioSession() {
        currentAudioSession()?.terminate(
            reason = NexusAudioStopReason.RELEASED,
            releaseActiveLease = true,
        )
    }

    internal fun registerSpeechSession(session: NexusSpeechSession): Boolean =
        synchronized(speechSessionLock) {
            if (closed || registeredSpeechSession?.let { it !== session } == true) {
                false
            } else {
                registeredSpeechSession = session
                speechSessionApiUsed = true
                true
            }
        }

    internal fun unregisterSpeechSession(session: NexusSpeechSession) {
        synchronized(speechSessionLock) {
            if (registeredSpeechSession === session) registeredSpeechSession = null
        }
    }

    internal fun sendSpeechStart(
        session: NexusSpeechSession,
        id: String,
        language: String?,
    ): Boolean {
        if (synchronized(speechSessionLock) { registeredSpeechSession !== session }) return false
        val payload = JSONObject()
            .put("version", 1)
            .put("mode", "utterance")
        language?.trim()?.takeIf(String::isNotEmpty)?.let { payload.put("language", it) }
        return send(NEXUS_STT_SESSION_START_PATH, id, payload)
    }

    internal fun sendSpeechStop(
        session: NexusSpeechSession,
        id: String,
        sessionId: String,
    ): Boolean {
        if (synchronized(speechSessionLock) { registeredSpeechSession !== session }) return false
        return send(
            NEXUS_STT_SESSION_STOP_PATH,
            id,
            JSONObject().put("sessionId", sessionId),
        )
    }

    internal fun releaseSpeechSession() {
        currentSpeechSession()?.terminate(
            reason = NexusSpeechStopReason.CANCELLED,
            stopActiveSession = true,
        )
    }

    override fun onRegistrationState(result: Int) {
        if (closed) return
        registrationState = result
        // Approval is the moment a fire-and-forget plugin acts on — connect, push a pin,
        // disconnect — so capabilities must be true by then. Leaving this to the first
        // onLinkState is a race, and the loser reads every capability as absent.
        if (result == PluginRegistrationResult.APPROVED) {
            hubCapabilities = transport.capabilities()
        }
        if (result != PluginRegistrationResult.APPROVED) {
            approvedCapabilities = emptySet()
            terminateAudioSession(
                reason = NexusAudioStopReason.ERROR,
                releaseActiveLease = false,
            )
            terminateSpeechSession(
                reason = NexusSpeechStopReason.ERROR,
                stopActiveSession = false,
            )
        }
        callbacks.onRegistrationState(result)
        if (result != PluginRegistrationResult.APPROVED && opened) {
            opened = false
            callbacks.onClose()
        }
    }

    override fun onLinkState(state: Int) {
        if (closed) return
        currentLinkState = state
        hubCapabilities = transport.capabilities()
        callbacks.onLinkState(state)
    }

    override fun onGlassesAiButton(active: Boolean) {
        if (closed) return
        callbacks.onGlassesAiButton(active)
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) {
        if (closed || payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        if (routeSpeechMessage(path, payload)) return
        if (routeAudioMessage(path, payload)) return
        when (path) {
            // A duplicate PLUGIN_OPEN (fresh event id) is the hub asking an already-open
            // plugin to re-present itself — e.g. the glasses fell back to the launcher
            // while the hub still considers the session open. onOpen implementations
            // reset and re-show, which also acknowledges the hub's open watchdog.
            BusPaths.PLUGIN_OPEN -> if (isApproved) {
                opened = true
                callbacks.onOpen()
            }
            BusPaths.PLUGIN_CLOSE -> if (opened) {
                opened = false
                releaseAudioSession()
                releaseSpeechSession()
                callbacks.onClose()
            }
            BusPaths.PLUGIN_INPUT -> if (opened && isApproved) {
                callbacks.onInput(
                    NexusInputEvent(
                        surfaceId = payload.optString("localSurfaceId", payload.optString("surfaceId")),
                        keyCode = payload.optInt("keyCode"),
                        action = payload.optInt("action"),
                    ),
                )
            }
            BusPaths.PLUGIN_REGISTRATION -> {
                // A fresh registration means the hub has no open session with us (it just
                // (re)accepted this client), so a stale `opened` from a previous hub life
                // must not swallow the next PLUGIN_OPEN.
                if (opened) {
                    opened = false
                    callbacks.onClose()
                }
                val result = payload.optInt("result", PluginRegistrationResult.REGISTRATION_FAILED)
                val parsed = PluginCapability.parseList(payload.optString("capabilities"))
                approvedCapabilities = if (parsed is CapabilityParseResult.Valid) {
                    parsed.capabilities
                } else {
                    emptySet()
                }
                onRegistrationState(result)
            }
            else -> if (isApproved) callbacks.onMessage(path, id, payload)
        }
    }

    override fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray) {
        if (closed || !isApproved || payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        if (routeSpeechBinary(path)) return
        if (routeAudioBinary(path, payload, data)) return
        callbacks.onBinary(path, id, payload, data)
    }

    override fun onError(message: String) = Unit

    override fun close() {
        if (closed) return
        closed = true
        terminateAudioSession(
            reason = NexusAudioStopReason.ERROR,
            releaseActiveLease = false,
        )
        terminateSpeechSession(
            reason = NexusSpeechStopReason.ERROR,
            stopActiveSession = false,
        )
        if (opened) {
            opened = false
            callbacks.onClose()
        }
        transport.close()
        currentLinkState = 0
        hubCapabilities = 0
        seenEventIds.clear()
        seenEventIdSet.clear()
    }

    private fun routeAudioMessage(path: String, payload: JSONObject): Boolean {
        if (path != NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH &&
            path != NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH &&
            path != NEXUS_AUDIO_LEASE_REVOKED_PATH
        ) {
            return false
        }
        val (session, consume) = synchronized(audioSessionLock) {
            registeredAudioSession to audioSessionApiUsed
        }
        when (path) {
            NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH -> session?.onAcquireReply(payload)
            NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH -> session?.onReleaseReply(payload)
            NEXUS_AUDIO_LEASE_REVOKED_PATH -> session?.onRevoked(payload)
        }
        return consume
    }

    private fun routeSpeechMessage(path: String, payload: JSONObject): Boolean {
        if (!isSpeechPath(path)) return false
        val (session, consume) = synchronized(speechSessionLock) {
            registeredSpeechSession to speechSessionApiUsed
        }
        when (path) {
            NEXUS_STT_SESSION_START_REPLY_PATH -> session?.onStartReply(payload)
            NEXUS_STT_SESSION_STOP_REPLY_PATH -> session?.onStopReply(payload)
            NEXUS_STT_STATE_PATH -> session?.onState(payload)
            NEXUS_STT_PARTIAL_PATH -> session?.onPartial(payload)
            NEXUS_STT_FINAL_PATH -> session?.onFinal(payload)
            NEXUS_STT_SESSION_ENDED_PATH -> session?.onEnded(payload)
        }
        return consume
    }

    private fun routeSpeechBinary(path: String): Boolean {
        if (!isSpeechPath(path)) return false
        return synchronized(speechSessionLock) { speechSessionApiUsed }
    }

    private fun routeAudioBinary(path: String, payload: JSONObject, data: ByteArray): Boolean {
        if (path != NEXUS_AUDIO_FRAMES_PATH) return false
        val (session, consume) = synchronized(audioSessionLock) {
            registeredAudioSession to audioSessionApiUsed
        }
        session?.onAudioFrame(payload, data)
        return consume
    }

    private fun currentAudioSession(): NexusAudioSession? =
        synchronized(audioSessionLock) { registeredAudioSession }

    private fun currentSpeechSession(): NexusSpeechSession? =
        synchronized(speechSessionLock) { registeredSpeechSession }

    private fun terminateAudioSession(
        reason: NexusAudioStopReason,
        releaseActiveLease: Boolean,
    ) {
        currentAudioSession()?.terminate(reason, releaseActiveLease)
    }

    private fun terminateSpeechSession(
        reason: NexusSpeechStopReason,
        stopActiveSession: Boolean,
    ) {
        currentSpeechSession()?.terminate(reason, stopActiveSession)
    }

    private fun isSpeechPath(path: String): Boolean =
        path == "/stt" || path.startsWith("/stt/")

    private fun rememberEvent(id: String): Boolean {
        if (id.isBlank() || !seenEventIdSet.add(id)) return false
        seenEventIds += id
        while (seenEventIds.size > MAX_SEEN_EVENTS) {
            seenEventIdSet.remove(seenEventIds.removeFirst())
        }
        return true
    }

    private fun pinPreflight(): NexusSdkResult? = when {
        !isApproved -> NexusSdkResult.NOT_REGISTERED
        !hasCapability(PluginCapability.SURFACES) -> NexusSdkResult.CAPABILITY_NOT_GRANTED
        !supportsPinSurface -> NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        else -> null
    }

    companion object {
        private const val MAX_SEEN_EVENTS = 128

        fun create(
            context: Context,
            pluginId: String,
            callbacks: NexusPluginCallbacks,
            hubTarget: HubTarget = HubTarget.PHONE,
        ): NexusPluginClient = NexusPluginClient(
            pluginId = pluginId,
            callbacks = callbacks,
            transport = AndroidNexusPluginTransport(context, pluginId, hubTarget),
        )
    }
}
