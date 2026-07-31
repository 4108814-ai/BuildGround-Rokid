plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.photosync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.photosync"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":bus-client"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
