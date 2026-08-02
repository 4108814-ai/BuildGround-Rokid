package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesApkVerificationPolicyTest {
    private fun verdict(
        parsedPackageName: String?,
        phoneSdkInt: Int,
        digestVerified: Boolean,
    ) = GlassesApkVerificationPolicy.verdict(
        parsedPackageName = parsedPackageName,
        expectedPackageName = GLASSES_PACKAGE,
        phoneSdkInt = phoneSdkInt,
        digestVerified = digestVerified,
    )

    @Test
    fun `a parsed archive with the right package is accepted on any phone`() {
        assertEquals(
            GlassesApkVerdict.Accept,
            verdict(GLASSES_PACKAGE, phoneSdkInt = 30, digestVerified = false),
        )
        assertEquals(
            GlassesApkVerdict.Accept,
            verdict(GLASSES_PACKAGE, phoneSdkInt = 34, digestVerified = true),
        )
    }

    @Test
    fun `a parsed archive with the wrong package is rejected even with a digest`() {
        assertEquals(
            GlassesApkVerdict.Reject("APK package was com.example.impostor"),
            verdict("com.example.impostor", phoneSdkInt = 34, digestVerified = true),
        )
    }

    @Test
    fun `an unreadable archive on a phone that can parse it is rejected`() {
        assertEquals(
            GlassesApkVerdict.Reject("APK package was unreadable"),
            verdict(null, phoneSdkInt = 31, digestVerified = true),
        )
    }

    @Test
    fun `an Android 11 phone accepts an unreadable archive when the digest matched`() {
        assertEquals(
            GlassesApkVerdict.Accept,
            verdict(null, phoneSdkInt = 30, digestVerified = true),
        )
    }

    @Test
    fun `an Android 11 phone rejects an unreadable archive without a digest`() {
        assertEquals(
            GlassesApkVerdict.Reject(
                "APK package was unreadable and the release carried no digest",
            ),
            verdict(null, phoneSdkInt = 30, digestVerified = false),
        )
    }

    private companion object {
        private const val GLASSES_PACKAGE = "com.anezium.rokidbus.glasses"
    }
}
