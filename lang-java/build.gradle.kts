plugins {
    id("java")
}

group = "com.tyron.nanoj"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":test-framework"))

    implementation("org.mapdb:mapdb:3.0.10")
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    implementation(project(":api"))
    implementation(project(":core"))
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
    options.compilerArgs = javacAddExports
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(*javacAddExports.toTypedArray())
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(*javacAddExports.toTypedArray())
}

tasks.withType<Test>().configureEach {
    // Ensure ALL test tasks (including filtered --tests runs) get module opens/exports.
    jvmArgs(*javacAddExports.toTypedArray())
}