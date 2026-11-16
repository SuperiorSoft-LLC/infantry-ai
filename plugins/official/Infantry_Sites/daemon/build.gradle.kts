// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SITES-1: Infantry_Sites daemon half: provider infantry, servers, agent tools.
// `daemon.jar` names the jar this builds. `:daemon-plugin-official-Infantry_Sites:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// SITES-SSH-1: the SSH client (Apache MINA SSHD, OpenSSH user certificates; no OpenSSH install needed) and its Ed25519
// provider are shaded into this jar: the daemon's plugin class loader exposes only the SDK.
val sshLibs = listOf("org.apache.sshd:sshd-core:2.14.0", "org.apache.sshd:sshd-common:2.14.0", "net.i2p.crypto:eddsa:0.3.0", "org.slf4j:slf4j-api:1.7.36")
val shaded by configurations.creating { isTransitive = false }

dependencies {
    sshLibs.forEach { shaded(it); compileOnly(it); testImplementation(it) }
    compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    archiveFileName.set("Infantry_Sites-daemon.jar")
    // OfficialTrust: like the UI jar, the daemon jar carries a signed module.json equal to the one on disk. Signed when
    // -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS); a bundled or devTrust run needs no signature.
    from(layout.projectDirectory.file("../module.json"))
    from({ shaded.map { if (it.isDirectory) it else zipTree(it) } }) { exclude("module.json", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF", "META-INF/versions/**") }
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
