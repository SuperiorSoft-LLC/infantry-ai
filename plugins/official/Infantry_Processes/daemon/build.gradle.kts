// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): Infantry_Processes's daemon half, the MCP tools that used to be built
// into the CLI. Compiled against the daemon API only (compileOnly: the daemon provides it at run time); module.json's
// `daemon.jar` names the jar this builds. `:daemon-plugin-official-Infantry_Processes:jar`.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    archiveFileName.set("Infantry_Processes-daemon.jar")
    // OfficialTrust: like the UI jar, the daemon jar carries a signed module.json equal to the one on disk. Signed when
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

tasks.test { useJUnitPlatform() }
