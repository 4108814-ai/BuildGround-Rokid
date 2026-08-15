package com.buildground.nexus.hardware

import android.content.Context
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

/**
 * BuildGround-owned CXR-L CUSTOMAPP control channel.
 *
 * This deliberately uses CXRLink.connect(token) and CXRLink's own custom-command
 * callback. The working donor Nexus receives glasses -> phone traffic through this
 * callback, not through IMediaStreamService.registerCustomCmdCallback().
 */
class BuildGroundCxrLinkControl(
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

        val next = CXRLink(appContext)
        val configured = next.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, glassesPackage),
        )
        if (!configured) return false

        next.setCXRLinkCbk(linkCallback)
        next.setCXRCustomCmdCbk(customCmdCallback)
        link = next

        val connected = runCatching { next.connect(token) }.getOrDefault(false)
        if (!connected) {
            runCatching { next.disconnect() }
            link = null
            cxrConnected = false
            glassesConnected = false
            publishState()
        }
        return connected
    }

    fun send(key: String, message: String): Boolean {
        val active = link ?: return false
        if (!cxrConnected || !glassesConnected) return false
        return runCatching {
            val result = active.sendCustomCmd(
                key,
                Caps().apply { write(message) },
            )
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

        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
        override fun onGlassDeviceInfo(info: GlassInfo) = Unit
        override fun onGlassWearingStatus(wearing: Boolean) = Unit
        override fun onGlassAiInterrupt(interrupted: Boolean) = Unit
        override fun onGlassLauncherResume() = Unit
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
