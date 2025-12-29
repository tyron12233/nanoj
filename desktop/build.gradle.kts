plugins {
    id("java")
    application
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

    implementation("com.fifesoft:rsyntaxtextarea:3.3.4")

    implementation("com.formdev:flatlaf:3.5.4")

    implementation(project(":core"))
    implementation(project(":api"))
    implementation(project(":lang-java"))
}

application {
    mainClass = "com.tyron.nanoj.desktop.DesktopApp"
}

tasks.test {
    useJUnitPlatform()
}


val javacAddExports = listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.jdeps/com.sun.tools.classfile=ALL-UNNAMED",
    "--add-modules=jdk.jdeps",
    "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
)


tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs = javacAddExports;
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(javacAddExports);
}