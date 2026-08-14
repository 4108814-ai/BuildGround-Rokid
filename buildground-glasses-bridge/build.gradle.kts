plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("BUILDGROUND_NEXUS_RELEASE_KEYSTORE").orNull
val releaseKeystorePassword = providers.environmentVariable("BUILDGROUND_NEXUS_RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("BUILDGROUND_NEXUS_RELEASE_KEY_ALIAS").orNull
val signingValues = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias)
val signingConfigured = signingValues.all { !it.isNullOrBlank() }
val signingPartiallyConfigured = signingValues.any { !it.isNullOrBlank() } && !signingConfigured

if (signingPartiallyConfigured) error("Incomplete BuildGround Nexus signing configuration")

android {
    namespace = "com.buildground.nexus.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.buildground.nexus.glasses"
        minSdk = 31
        // Preserve compatibility with the current RV101 / YodaOS application environment.
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingConfigured) {
            create("buildgroundRelease") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeystorePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingConfigured) signingConfig = signingConfigs.getByName("buildgroundRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260522.063600-105")
}

tasks.configureEach {
    if (name.contains("Release", ignoreCase = true)) {
        doFirst {
            check(signingConfigured) {
                "BuildGround glasses release signing is mandatory. Configure BUILDGROUND_NEXUS_RELEASE_KEYSTORE, BUILDGROUND_NEXUS_RELEASE_KEYSTORE_PASSWORD and BUILDGROUND_NEXUS_RELEASE_KEY_ALIAS."
            }
        }
    }
}
