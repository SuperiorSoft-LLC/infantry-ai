// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): Demo_Lock's daemon half, the seated lockdown a preset's `seats` declare (the
// visitor token, the per-IP turn limit, the seeded sessions and their warm-up, the demo's refusals). Core holds none of it.
// Compiled against the daemon API only (compileOnly: the daemon provides it at run time). `:daemon-plugin-demo-Demo_Lock:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(kotlin("test"))
    // The tests run the lock against core's real action catalog and policy runtime (never on the plugin's compile path).
    testImplementation(project(":superiorSoftLibrary:agentHq"))
}

tasks.jar {
    archiveFileName.set("Demo_Lock-daemon.jar")
    // OfficialTrust: the jar carries module.json (signed with -Pihq.keystore / -Pihq.alias, password in env IHQ_STOREPASS);
    // a bundled or devTrust run needs no signature.
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
