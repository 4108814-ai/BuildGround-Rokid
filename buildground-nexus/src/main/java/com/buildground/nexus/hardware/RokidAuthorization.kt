package com.buildground.nexus.hardware

import android.app.Activity
import android.content.ComponentName
import android.content.Intent

object RokidAuthorization {
    const val GLOBAL_APP_PACKAGE = "com.rokid.sprite.global.aiapp"
    const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
    private const val AUTH_ACTIVITY = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
    private const val EXTRA_AUTH_RESULT = "auth_result"
    private const val EXTRA_AUTH_TOKEN = "auth_token"
    private const val AUTH_SUCCESS = 2001
    private const val AUTH_CANCELLED = 2003

    data class Result(val success: Boolean, val token: String? = null, val message: String)

    fun isHiRokidInstalled(activity: Activity): Boolean = runCatching {
        activity.packageManager.getPackageInfo(GLOBAL_APP_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun launch(activity: Activity, requestCode: Int): Boolean {
        if (!isHiRokidInstalled(activity)) return false

        val explicit = Intent(AUTH_ACTION).apply {
            component = ComponentName(GLOBAL_APP_PACKAGE, AUTH_ACTIVITY)
            putExtra("auth_package", activity.packageName)
        }
        val fallback = Intent(AUTH_ACTION).apply {
            setPackage(GLOBAL_APP_PACKAGE)
            putExtra("auth_package", activity.packageName)
        }

        return runCatching {
            activity.startActivityForResult(explicit, requestCode)
            true
        }.recoverCatching {
            activity.startActivityForResult(fallback, requestCode)
            true
        }.getOrDefault(false)
    }

    fun parse(data: Intent?): Result {
        val authCode = data?.getIntExtra(EXTRA_AUTH_RESULT, -1) ?: -1
        val token = data?.getStringExtra(EXTRA_AUTH_TOKEN)?.trim().orEmpty()
        return when {
            authCode == AUTH_SUCCESS && token.isNotBlank() ->
                Result(true, token, "Hi Rokid authorization complete")
            authCode == AUTH_CANCELLED ->
                Result(false, null, "Hi Rokid authorization cancelled")
            authCode == AUTH_SUCCESS ->
                Result(false, null, "Authorization returned no token")
            else ->
                Result(false, null, "Hi Rokid authorization failed (code=$authCode)")
        }
    }
}
