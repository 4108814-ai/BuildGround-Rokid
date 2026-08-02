package com.anezium.rokidbus.phone

internal sealed interface GlassesApkVerdict {
    data object Accept : GlassesApkVerdict

    data class Reject(val reason: String) : GlassesApkVerdict
}

/**
 * Decides whether a downloaded glasses APK may be uploaded to the glasses.
 *
 * The archive is parsed by the PHONE's PackageManager, which refuses any APK
 * whose minSdk exceeds the phone's own API level — and the glasses hub ships
 * with a higher minSdk than the phone hub supports. So on the oldest supported
 * phones a null parse says nothing about the file itself; there the verified
 * GitHub release digest stands in for the package-name check.
 */
internal object GlassesApkVerificationPolicy {
    /** minSdk of the glasses hub APK; phones below this cannot parse it. */
    const val GLASSES_APK_MIN_SDK = 31

    fun verdict(
        parsedPackageName: String?,
        expectedPackageName: String,
        phoneSdkInt: Int,
        digestVerified: Boolean,
    ): GlassesApkVerdict = when {
        parsedPackageName == expectedPackageName -> GlassesApkVerdict.Accept
        parsedPackageName != null ->
            GlassesApkVerdict.Reject("APK package was $parsedPackageName")
        phoneSdkInt >= GLASSES_APK_MIN_SDK ->
            GlassesApkVerdict.Reject("APK package was unreadable")
        digestVerified -> GlassesApkVerdict.Accept
        else -> GlassesApkVerdict.Reject(
            "APK package was unreadable and the release carried no digest",
        )
    }
}
