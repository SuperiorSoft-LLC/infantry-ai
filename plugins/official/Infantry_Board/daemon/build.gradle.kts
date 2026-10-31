// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): Infantry_Board's daemon half, the `ticket_files` MCP tools that used to be built
// into the CLI. Compiled against the daemon API only (compileOnly: the daemon provides it at run time); module.json's
// `daemon.jar` names the jar this builds. `:daemon-plugin-official-Infantry_Board:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// DP-5h-3: the board (agentHqBoard's classes) is SHADED into this jar, no source move: the plugin carries the board it builds.
// The daemon API stays compileOnly (the host provides it at run time, one copy); only agentHqBoard's own output is bundled.
val shaded by configurations.creating { isTransitive = false }

dependencies {
    shaded(project(":superiorSoftLibrary:agentHqBoard"))
    compileOnly(project(":superiorSoftLibrary:agentHqBoard"))
    compileOnly(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:agentHqBoard"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    archiveFileName.set("Infantry_Board-daemon.jar")
    // OfficialTrust: like the UI jar, the daemon jar carries a signed module.json equal to the one on disk. Signed when
    // -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS); a bundled or devTrust run needs no signature.
    from(layout.projectDirectory.file("../module.json"))
    from({ shaded.map { if (it.isDirectory) it else zipTree(it) } }) { exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/MANIFEST.MF") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
