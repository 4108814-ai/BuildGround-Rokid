package com.buildground.nexus.hardware

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

/**
 * BuildGround-owned CXR-L CUSTOMAPP transport.
 *
 * The important distinction from the raw Hi Rokid AIDL service is that CXR-L
 * must be configured for the exact glasses package before custom commands are
 * routed bidirectionally between phone and CXR-S on the glasses.
 */
class BuildGroundCustomAppSession(
    context: Context,
    private val glassesPackage: String,
    private val onLinkState: (cxrConnected: Boolean, glassesConnected: Boolean) -> Unit,
    private val onCustomCommand: (key: String, payload: ByteArray) -> Unit,
) {
    private val appContext = context.applicationContext
    private var link: CXRLink? = null
    private var serviceConnection: ServiceConnection? = null
    private var bound = false
    @Volatile private var cxrConnected = false
    @Volatile private var glassesConnected = false

    fun start(token: String): Boolean {
        if (token.isBlank()) return false
        if (link != null && bound) return true

        stop()
        val next = CXRLink(appContext)
        val configured = next.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, glassesPackage),
        )
        if (!configured) return false

        next.setCXRLinkCbk(linkCallback)
        next.setCXRCustomCmdCbk(customCmdCallback)
        link = next

        val connection = runCatching { findServiceConnection(next) }.getOrNull() ?: run {
            link = null
            return false
        }
        serviceConnection = connection

        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(RokidAuthorization.GLOBAL_APP_PACKAGE)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, appContext.packageName)

        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            serviceConnection = null
            link = null
        }
        return bound
    }

    fun send(key: String, payload: ByteArray): Boolean {
        val active = link ?: return false
        if (!cxrConnected || !glassesConnected) return false
        return runCatching { active.sendCustomCmd(key, payload) >= 0 }.getOrDefault(false)
    }

    fun stop() {
        if (bound) {
            serviceConnection?.let { connection -> runCatching { appContext.unbindService(connection) } }
        }
        runCatching { link?.disconnect() }
        bound = false
        serviceConnection = null
        link = null
        cxrConnected = false
        glassesConnected = false
        onLinkState(false, false)
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
    }

    private val customCmdCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String, payload: ByteArray) {
            onCustomCommand(key, payload)
        }
    }

    private fun publishState() {
        onLinkState(cxrConnected, glassesConnected)
    }

    private fun findServiceConnection(cxrLink: CXRLink): ServiceConnection {
        var type: Class<*>? = cxrLink.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { field ->
                ServiceConnection::class.java.isAssignableFrom(field.type)
            }
            if (field != null) {
                field.isAccessible = true
                return field.get(cxrLink) as ServiceConnection
            }
            type = type.superclass
        }
        error("CXR-L ServiceConnection field not found")
    }

    private companion object {
        const val MEDIA_STREAM_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_AUTH_PACKAGE = "auth_package"
    }
}
