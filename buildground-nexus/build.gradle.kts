plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("BUILDGROUND_NEXUS_RELEASE_KEYSTORE").orNull
val releaseKeystorePassword = providers.environmentVariable("BUILDGROUND_NEXUS_RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("BUILDGROUND_NEXUS_RELEASE_KEY_ALIAS").orNull

val signingValues = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias)
val signingConfigured = signingValues.all { !it.isNullOrBlank() }
val signingPartiallyConfigured = signingValues.any { !it.isNullOrBlank() } && !signingConfigured

if (signingPartiallyConfigured) {
    error("Incomplete BuildGround Nexus signing configuration")
}

android {
    namespace = "com.buildground.nexus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.buildground.nexus"
        minSdk = 30
        targetSdk = 36
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
            // Keep the production package identity during hardware tests so the phone can
            // address the exact same glasses package through CXR CUSTOMAPP.
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("buildgroundRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Official Rokid CXR-L API/AIDL surface. No CxrGlobal/Anezium wrapper is used.
    implementation("com.rokid.cxr:client-l:1.1.0")
}

// A distributable release must never be produced with the Android debug key or unsigned.
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true)) {
        doFirst {
            check(signingConfigured) {
                "BuildGround Nexus release signing is mandatory. Configure BUILDGROUND_NEXUS_RELEASE_KEYSTORE, BUILDGROUND_NEXUS_RELEASE_KEYSTORE_PASSWORD and BUILDGROUND_NEXUS_RELEASE_KEY_ALIAS."
            }
        }
    }
}
