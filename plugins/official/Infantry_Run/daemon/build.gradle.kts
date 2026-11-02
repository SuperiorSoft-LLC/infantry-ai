// CMD-1: Infantry_Run daemon half (saved commands and their runs, over api.runs). Compiled against the daemon API only; protocol shaded in.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

val shaded by configurations.creating { isTransitive = false }

dependencies {
    shaded(project(":superiorSoftLibrary:agentHqProtocol"))
    compileOnly(project(":superiorSoftLibrary:agentHqProtocol"))
    compileOnly(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:agentHqProtocol"))
    testImplementation(project(":superiorSoftLibrary:agentHqRunner"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    archiveFileName.set("Infantry_Run-daemon.jar")
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
