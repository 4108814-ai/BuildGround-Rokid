package com.buildground.nexus.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.rokid.sprite.aiapp.externalapp.IGlassAppCallback
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService

/**
 * One-shot remover for the BuildGround test companion on Rokid Glasses.
 *
 * It can only target our fixed package name. This is intentionally separate
 * from the general Hardware Bridge so the bootstrap/recovery operation cannot
 * be redirected to arbitrary packages.
 */
class BuildGroundCompanionRemover(context: Context) {
    data class Result(val success: Boolean, val message: String)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var activeConnection: ServiceConnection? = null
    private var generation = 0L

    fun remove(token: String, listener: (Result) -> Unit): Boolean {
        if (token.isBlank()) {
            listener(Result(false, "Authorize Hi Rokid before removing the test companion"))
            return false
        }
        if (activeConnection != null) {
            listener(Result(false, "BuildGround glasses companion removal is already in progress"))
            return false
        }

        val op = ++generation
        lateinit var connection: ServiceConnection
        val callback = object : IGlassAppCallback.Stub() {
            override fun onInstallAppResult(success: Boolean) = Unit
            override fun onOpenAppResult(success: Boolean) = Unit
            override fun onQueryAppResult(pkg: String?, installed: Boolean) = Unit
            override fun onStopAppResult(success: Boolean) = Unit

            override fun onUnInstallAppResult(success: Boolean) {
                finish(
                    op = op,
                    connection = connection,
                    result = Result(
                        success = success,
                        message = if (success) {
                            "BuildGround test glasses companion REMOVED"
                        } else {
                            "Hi Rokid could not remove BuildGround test glasses companion"
                        },
                    ),
                    listener = listener,
                )
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (op != generation || binder == null) {
                    finish(op, this, Result(false, "Hi Rokid remover service returned no binder"), listener)
                    return
                }
                val service = IMediaStreamService.Stub.asInterface(binder)
                runCatching {
                    service.uninstallApp(GLASSES_PACKAGE, callback)
                }.onFailure {
                    finish(
                        op,
                        this,
                        Result(false, "BuildGround companion removal request failed: ${it.javaClass.simpleName}"),
                        listener,
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish(op, this, Result(false, "Hi Rokid remover service disconnected"), listener)
            }

            override fun onBindingDied(name: ComponentName?) {
                finish(op, this, Result(false, "Hi Rokid remover service binding died"), listener)
            }

            override fun onNullBinding(name: ComponentName?) {
                finish(op, this, Result(false, "Hi Rokid rejected companion removal binding"), listener)
            }
        }

        activeConnection = connection
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(RokidAuthorization.GLOBAL_APP_PACKAGE)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, appContext.packageName)

        val bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            activeConnection = null
            listener(Result(false, "Could not bind Hi Rokid companion remover"))
            return false
        }

        main.postDelayed({
            if (op == generation && activeConnection === connection) {
                finish(op, connection, Result(false, "BuildGround companion removal timed out"), listener)
            }
        }, REMOVE_TIMEOUT_MS)
        return true
    }

    fun close() {
        generation += 1L
        activeConnection?.let { connection -> runCatching { appContext.unbindService(connection) } }
        activeConnection = null
    }

    private fun finish(
        op: Long,
        connection: ServiceConnection,
        result: Result,
        listener: (Result) -> Unit,
    ) {
        if (op != generation || activeConnection !== connection) return
        generation += 1L
        runCatching { appContext.unbindService(connection) }
        activeConnection = null
        main.post { listener(result) }
    }

    private companion object {
        const val MEDIA_STREAM_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_AUTH_PACKAGE = "auth_package"
        const val GLASSES_PACKAGE = "com.buildground.nexus.glasses"
        const val REMOVE_TIMEOUT_MS = 10_000L
    }
}
