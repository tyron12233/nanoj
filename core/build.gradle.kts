plugins {
    id("java")
}

group = "com.tyron.nanoj"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(project(":test-framework"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("it.unimi.dsi:fastutil:8.5.12")
    implementation("org.mapdb:mapdb:3.0.10")

    implementation(project(":api"))
    implementation("org.yaml:snakeyaml:2.2")
}

tasks.test {
    useJUnitPlatform()
}