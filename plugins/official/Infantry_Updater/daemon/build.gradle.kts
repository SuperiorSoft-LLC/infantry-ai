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

// UPDATER-DEFAULT-1: where this app's releases come from is MANIFEST data (developer data, never a user setting): the
// `update` block of ../module.json. The build bakes it into the jar as a resource the engine reads (UpdateManifest), so the
// window, the daemon half and the headless `infantry update` all see it with no setup. A build may override a value with a
// Gradle property (infantryUpdateRepo, infantryUpdateVisibility, infantryUpdateChannel): the release workflow passes the
// repository it publishes to, so a fork's release checks the fork. Env / update.properties stay developer overrides at run time.
val manifestFile = layout.projectDirectory.file("../module.json")
val updateOverrides = listOf("repo", "visibility", "channel").associateWith { key ->
    providers.gradleProperty("infantryUpdate" + key.replaceFirstChar { it.uppercase() }).map { it.trim() }.orElse("")
}
val updateManifest = tasks.register("updateManifest") {
    val out = layout.buildDirectory.dir("generated/update-manifest")
    val manifest = manifestFile
    val overrides = updateOverrides
    inputs.file(manifest)
    overrides.forEach { (k, v) -> inputs.property(k, v) }
    outputs.dir(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val block = ((groovy.json.JsonSlurper().parse(manifest.asFile) as Map<String, Any?>)["update"] as? Map<String, Any?>).orEmpty()
        val values = linkedMapOf<String, String>()
        block.forEach { (k, v) -> if (v != null && v !is Map<*, *> && v !is List<*>) values[k] = v.toString().trim() }
        overrides.forEach { (k, v) -> v.get().takeIf { it.isNotEmpty() }?.let { values[k] = it } }
        val repo = values["repo"].orEmpty()
        require(repo.isEmpty() || Regex("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$").matches(repo)) { "update repo '$repo' is not owner/repo" }
        val unsafe = values.entries.firstOrNull { (k, v) -> !Regex("^[A-Za-z0-9]+$").matches(k) || !Regex("^[A-Za-z0-9._/-]*$").matches(v) }
        require(unsafe == null) { "module.json update.${unsafe?.key} '${unsafe?.value}' has characters a manifest value never needs" }
        val file = out.get().file("infantry-update-manifest.properties").asFile
        file.parentFile.mkdirs()
        // Sorted, no timestamp: the same manifest gives the same bytes (a reproducible jar).
        val lines = listOf("# module.json `update` block, baked by the build (UPDATER-DEFAULT-1).") + values.toSortedMap().map { (k, v) -> "$k=$v" }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main { resources.srcDir(updateManifest) }

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
