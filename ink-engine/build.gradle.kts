plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

group = "com.github.Anezium.Rokid-Nexus"
version = providers.gradleProperty("versionName").orElse("0.1.0-SNAPSHOT").get()

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

dependencies {
    // Android consumers get org.json from the platform; shipping it as a real
    // dependency would duplicate those classes at dex time.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = "ink-engine"
            pom {
                name.set("Rokid Nexus Ink Engine")
                description.set("Rokid Nexus Ink page compiler and wire documents")
                url.set("https://github.com/Anezium/Rokid-Nexus")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("Anezium")
                        name.set("Anezium")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Anezium/Rokid-Nexus.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Anezium/Rokid-Nexus.git")
                    url.set("https://github.com/Anezium/Rokid-Nexus")
                }
            }
        }
    }
}
