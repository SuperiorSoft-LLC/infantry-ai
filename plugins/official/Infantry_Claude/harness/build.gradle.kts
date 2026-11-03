// HARNESS H7b-3: the claude harness (production) plugin, compiled against the harness API only (compileOnly: the daemon provides it at run time through
// HarnessApiClassLoader). `:harness-plugin-official-Infantry_Claude:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryHarnessApi"))
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(project(":superiorSoftLibrary:infantryHarnessApi"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("Infantry_Claude-harness.jar")
    // OfficialTrust: like the UI jar, the harness jar carries a signed module.json equal to the one on disk. Signed when
    // -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS); a bundled or devTrust run needs no signature.
    from(layout.projectDirectory.file("../module.json"))
    val keystore = providers.gradleProperty("ihq.keystore")
    val alias = providers.gradleProperty("ihq.alias")
    val storePass = providers.environmentVariable("IHQ_STOREPASS")
    doLast {
        if (keystore.isPresent) {
            val file = archiveFile.get().asFile
            val jarsigner = File(File(System.getProperty("java.home"), "bin"), "jarsigner" + if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else "").path
            val p = ProcessBuilder(jarsigner, "-keystore", keystore.get(), "-storepass", storePass.orNull ?: error("IHQ_STOREPASS is not set"), file.path, alias.orNull ?: error("-Pihq.alias is required")).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "jarsigner failed: $text" }
        }
    }
}
