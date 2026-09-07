package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal object GlassesPackageDiagnostics {
    fun logCurrent(context: Context) {
        runCatching {
            val packageManager = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, flags)
            }
            val applicationInfo = packageInfo.applicationInfo
            val signerDigests = packageInfo.signingInfo?.apkContentsSigners.orEmpty().map { signer ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signer.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(context.packageName)
            }
            log(
                "diag event=glasses_package_state package=${packageInfo.packageName} " +
                    "versionName=${packageInfo.versionName.orEmpty()} versionCode=${packageInfo.longVersionCode} " +
                    "signerSha256=${signerDigests.joinToString(",")} enabled=${applicationInfo?.enabled} " +
                    "flags=${applicationInfo?.flags ?: 0} sourceDir=${applicationInfo?.sourceDir.orEmpty()} " +
                    "installer=${installer.orEmpty()} sdk=${Build.VERSION.SDK_INT} " +
                    "deviceAbis=${Build.SUPPORTED_ABIS.joinToString(",")}",
            )
        }.onFailure { failure ->
            logError("diag event=glasses_package_state_failed type=${failure.javaClass.simpleName}", failure)
        }
    }
}
