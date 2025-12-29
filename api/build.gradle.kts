plugins {
    id("java")
}

group = "com.tyron.nanoj"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("com.google.guava:guava:33.5.0-jre")

    implementation("org.jetbrains:annotations:26.0.2-1")
}

tasks.test {
    useJUnitPlatform()
}