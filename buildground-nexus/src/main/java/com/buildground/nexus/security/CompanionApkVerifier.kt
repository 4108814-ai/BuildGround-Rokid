package com.buildground.nexus.security

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Accepts a glasses companion APK only when both its package identity and its
 * signing certificate match the currently installed BuildGround Nexus host.
 */
object CompanionApkVerifier {
    const val EXPECTED_PACKAGE = "com.buildground.nexus.glasses"

    data class Result(val trusted: Boolean, val message: String)

    fun verify(context: Context, apk: File): Result {
        if (!apk.isFile || apk.length() <= 0L) return Result(false, "Selected APK file is invalid")
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return Result(false, "Could not inspect selected APK")
        if (archive.packageName != EXPECTED_PACKAGE) {
            return Result(false, "Rejected package: ${archive.packageName}")
        }

        val hostInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val hostSigners = hostInfo.signingInfo?.apkContentsSigners.orEmpty()
        val archiveSigners = archive.signingInfo?.apkContentsSigners.orEmpty()
        if (hostSigners.size != 1 || archiveSigners.size != 1) {
            return Result(false, "BuildGround requires exactly one APK signer")
        }

        val hostDigest = sha256(hostSigners[0].toByteArray())
        val archiveDigest = sha256(archiveSigners[0].toByteArray())
        if (!hostDigest.contentEquals(archiveDigest)) {
            return Result(false, "Rejected: glasses APK signer does not match BuildGround Nexus")
        }
        return Result(true, "BuildGround glasses APK package and signer verified")
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
