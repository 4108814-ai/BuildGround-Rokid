package com.buildground.nexus.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.rokid.cxr.Caps
import com.rokid.sprite.aiapp.externalapp.ICustomCmdCallback
import com.rokid.sprite.aiapp.externalapp.IDeviceStatusCallback
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService

/**
 * BuildGround-owned Hi Rokid command transport.
 *
 * The known-working Nexus compatibility layer ultimately talks directly to the
 * global Hi Rokid IMediaStreamService: register a custom-command callback and
 * send serialized Caps through sendCustomCmd(). We implement that small transport
 * directly here instead of depending on an external compatibility wrapper.
 */
class BuildGroundCustomAppSession(
    context: Context,
    @Suppress("UNUSED_PARAMETER") private val glassesPackage: String,
    private val onLinkState: (cxrConnected: Boolean, glassesConnected: Boolean) -> Unit,
    private val onCustomCommand: (key: String, payload: ByteArray) -> Unit,
) {
    private val appContext = context.applicationContext
    private var service: IMediaStreamService? = null
    private var bound = false
    @Volatile private var serviceConnected = false
    @Volatile private var glassesConnected = false

    fun start(token: String): Boolean {
        if (token.isBlank()) return false
        if (bound && service != null) return true

        stop()
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(RokidAuthorization.GLOBAL_APP_PACKAGE)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, appContext.packageName)

        bound = runCatching {
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            serviceConnected = false
            glassesConnected = false
            publishState()
        }
        return bound
    }

    fun send(key: String, payload: ByteArray): Boolean {
        val active = service ?: return false
        if (!serviceConnected || !glassesConnected) return false
        return runCatching {
            val result = active.sendCustomCmd(key, payload)
            result >= 0
        }.getOrDefault(false)
    }

    fun send(key: String, payload: Caps): Boolean =
        runCatching { send(key, payload.serialize()) }.getOrDefault(false)

    fun stop() {
        val active = service
        if (active != null) {
            runCatching { active.unregisterDeviceStatusCallback(deviceStatusCallback) }
            runCatching { active.unregisterCustomCmdCallback(customCmdCallback) }
        }
        if (bound) runCatching { appContext.unbindService(serviceConnection) }
        bound = false
        service = null
        serviceConnected = false
        glassesConnected = false
        publishState()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                markDisconnected()
                return
            }
            val connectedService = IMediaStreamService.Stub.asInterface(binder)
            service = connectedService
            runCatching { connectedService.registerDeviceStatusCallback(deviceStatusCallback) }
            runCatching { connectedService.registerCustomCmdCallback(customCmdCallback) }
            serviceConnected = true
            glassesConnected = runCatching { connectedService.isDeviceConnected }.getOrDefault(false)
            publishState()
        }

        override fun onServiceDisconnected(name: ComponentName?) = markDisconnected()
        override fun onBindingDied(name: ComponentName?) = markDisconnected()
        override fun onNullBinding(name: ComponentName?) = markDisconnected()
    }

    private val deviceStatusCallback = object : IDeviceStatusCallback.Stub() {
        override fun onDeviceConnectChanged(connected: Boolean) {
            glassesConnected = connected
            publishState()
        }

        override fun onDeviceInfoNotifiy(infoJson: String?) = Unit
        override fun onWearingStatusNotify(wearing: Boolean) = Unit
        override fun onCurrentScenesNotify(scenesJson: String?) = Unit
    }

    private val customCmdCallback = object : ICustomCmdCallback.Stub() {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key == null || payload == null) return
            onCustomCommand(key, payload)
        }
    }

    private fun markDisconnected() {
        service = null
        serviceConnected = false
        glassesConnected = false
        publishState()
    }

    private fun publishState() {
        onLinkState(serviceConnected, glassesConnected)
    }

    private companion object {
        const val MEDIA_STREAM_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_AUTH_PACKAGE = "auth_package"
    }
}
