// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// UPDATER-1: Infantry_Updater's daemon half and the update engine (GitHub / local releases, verify, MSI handoff, server
// archive staging, the phone's APK offer). Compiled against the daemon API only (compileOnly: the daemon provides it at run
// time); module.json's `daemon.jar` names the jar this builds. The same jar is the engine of the desktop window's updater
// (:infantryUi depends on this project) and of the headless `infantry update` command (bin/infantry puts it on the class path).
// `:daemon-plugin-official-Infantry_Updater:jar`.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))
    // The window's UpdateService is coroutine based; the daemon half never calls it (the plugin loader does not expose coroutines).
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    // The event-contract version a status reports (a const, inlined at compile time).
    compileOnly(project(":superiorSoftLibrary:infantryProtocol"))
    testImplementation(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(project(":superiorSoftLibrary:infantryProtocol"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    archiveFileName.set("Infantry_Updater-daemon.jar")
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
