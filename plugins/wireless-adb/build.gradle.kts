plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.wirelessadb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.wirelessadb"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
