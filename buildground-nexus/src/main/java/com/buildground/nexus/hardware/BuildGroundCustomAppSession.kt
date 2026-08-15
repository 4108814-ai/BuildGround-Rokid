package com.buildground.nexus.hardware

import android.content.Context
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.GlassInfo
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.rokid.cxr.Caps

/**
 * BuildGround-owned CXR-L CUSTOMAPP transport.
 *
 * This intentionally mirrors the transport primitives used by the known-working
 * Nexus phone hub: com.example.cxrglobal CXRLink, direct connect(token), and a
 * serialized Caps payload for sendCustomCmd(). The package identity and protocol
 * remain BuildGround-owned.
 */
class BuildGroundCustomAppSession(
    context: Context,
    private val glassesPackage: String,
    private val onLinkState: (cxrConnected: Boolean, glassesConnected: Boolean) -> Unit,
    private val onCustomCommand: (key: String, payload: ByteArray) -> Unit,
) {
    private val appContext = context.applicationContext
    private var link: CXRLink? = null
    @Volatile private var cxrConnected = false
    @Volatile private var glassesConnected = false

    fun start(token: String): Boolean {
        if (token.isBlank()) return false
        if (link != null) return true

        stop()
        val next = CXRLink(appContext).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    glassesPackage,
                ),
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomCmdCbk(customCmdCallback)
        }
        link = next

        val requested = runCatching { next.connect(token) }.getOrDefault(false)
        if (!requested) {
            runCatching { next.disconnect() }
            link = null
            cxrConnected = false
            glassesConnected = false
            publishState()
        }
        return requested
    }

    fun send(key: String, payload: ByteArray): Boolean {
        val caps = runCatching { Caps.fromBytes(payload) }.getOrNull() ?: return false
        return send(key, caps)
    }

    fun send(key: String, payload: Caps): Boolean {
        val active = link ?: return false
        if (!cxrConnected || !glassesConnected) return false
        return runCatching {
            val result = active.sendCustomCmd(key, payload.serialize())
            result != null && result >= 0
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { link?.disconnect() }
        link = null
        cxrConnected = false
        glassesConnected = false
        publishState()
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            publishState()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassesConnected = connected
            publishState()
        }

        override fun onGlassDeviceInfo(info: GlassInfo) = Unit
        override fun onGlassWearingStatus(wearing: Boolean) = Unit
        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
    }

    private val customCmdCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String, payload: ByteArray) {
            onCustomCommand(key, payload)
        }
    }

    private fun publishState() {
        onLinkState(cxrConnected, glassesConnected)
    }
}
