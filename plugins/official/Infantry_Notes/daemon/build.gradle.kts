// DP-7c: Infantry_Notes's daemon half, the decisions and issues logs (stores, frames, Knowledge records).
// Compiled against the daemon API only (compileOnly: the daemon provides it at run time); the wire DTOs (agentHqProtocol) are shaded in;
// module.json's `daemon.jar` names the jar this builds. `:daemon-plugin-official-Infantry_Notes:jar`.
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
    // DP-7f: LeadNote is @Serializable; the daemon provides the runtime.
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:agentHqProtocol"))
    testImplementation(kotlin("test"))
    // DP-6: PipelineStoreTest runs the adopted pipeline tables on a real SQLite file.
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.jar {
    archiveFileName.set("Infantry_Notes-daemon.jar")
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
