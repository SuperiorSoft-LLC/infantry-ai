// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SCRIPT-2: the LuaJ script engine, an engine plugin of Infantry_Scripting (shades its contract classes and luaj-jse).
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// SCRIPT-2: the LuaJ script engine, an engine plugin of Infantry_Scripting (shades its contract classes and luaj-jse).
val shaded by configurations.creating { isTransitive = false }

dependencies {
    shaded(project(":daemon-plugin-official-Infantry_Scripting"))
    shaded("org.luaj:luaj-jse:3.0.1")
    compileOnly("org.luaj:luaj-jse:3.0.1")
    compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(project(":daemon-plugin-official-Infantry_Scripting"))
    testImplementation(project(":daemon-plugin-official-Infantry_Scripting"))
    testImplementation("org.luaj:luaj-jse:3.0.1")
    testImplementation(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.jar {
    archiveFileName.set("Infantry_Lua-daemon.jar")
    // OfficialTrust: like the UI jar, the daemon jar carries a signed module.json equal to the one on disk. Signed when
    // -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS); a bundled or devTrust run needs no signature.
    from(layout.projectDirectory.file("../module.json"))
    from({ shaded.map { if (it.isDirectory) it else zipTree(it) } }) { exclude("module.json", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/MANIFEST.MF") }
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
