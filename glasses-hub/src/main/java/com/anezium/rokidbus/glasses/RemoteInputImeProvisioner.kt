package com.anezium.rokidbus.glasses

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

internal object RemoteInputImeProvisioner {
    fun ensureConfigured(context: Context): Boolean {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val component = ComponentName(context, NexusRemoteInputMethodService::class.java)
            .flattenToShortString()
        return runCatching {
            val resolver = context.contentResolver
            val enabled = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_INPUT_METHODS,
            )
            val updated = enabledMethodsWithNexus(enabled, component)
            if (updated != enabled) {
                check(
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_INPUT_METHODS,
                        updated,
                    ),
                )
            }

            val current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (shouldSelectNexus(current, component)) {
                check(
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD,
                        component,
                    ),
                )
            }
            true
        }.getOrDefault(false)
    }

    internal fun enabledMethodsWithNexus(current: String?, component: String): String {
        val methods = current.orEmpty()
            .split(':')
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        methods += component
        return methods.joinToString(":")
    }

    internal fun shouldSelectNexus(current: String?, component: String): Boolean =
        current.isNullOrBlank() || current == component
}
