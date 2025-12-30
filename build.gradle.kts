plugins {
    id("java")
}

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion

group = "org.example"
version = "1.0-SNAPSHOT"

allprojects {
    repositories {
        // Prefer repo1 as a fallback when repo.maven.apache.org is intermittently blocked (HTTP 403) on some runners.
        maven {
            url = uri("https://repo1.maven.org/maven2")
        }
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

