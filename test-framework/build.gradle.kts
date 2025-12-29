plugins {
    id("java-library")
}

group = "com.tyron.nanoj"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(platform("org.junit:junit-bom:5.10.0"))
    api("org.junit.jupiter:junit-jupiter")
    api("com.google.truth:truth:1.4.5")

    implementation(project(":api"))
    implementation(project(":core"))
}

tasks.test {
    useJUnitPlatform()
}