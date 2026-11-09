// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.security.MessageDigest

/** The validated parts of a plugin's module.json that the packaging needs. */
class PluginModule(val name: String, val version: String?, val raw: MutableMap<String, Any?>, val hasDaemon: Boolean, val hasHarness: Boolean) {
    @Suppress("UNCHECKED_CAST") private fun half(key: String) = raw[key] as? Map<String, Any?>
    val apiPlugin: String? get() = raw["apiVersion"]?.toString()?.takeIf { it.isNotBlank() }?.let { ">=$it" }
    val apiDaemon: String? get() = half("daemon")?.get("apiAtLeast")?.toString()?.let { ">=$it" }
    val apiHarness: String? get() = half("harness")?.get("apiAtLeast")?.toString()?.let { ">=$it" }
}

object ModuleJson {
    private val plainName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** Reads module.json and checks it against the folders next to it; throws a [GradleException] naming the problem. */
    @Suppress("UNCHECKED_CAST")
    fun read(projectDir: File): PluginModule {
        val file = File(projectDir, "module.json")
        if (!file.isFile) throw GradleException("PLUGIN: no module.json in ${projectDir.path}")
        val m = try {
            JsonSlurper().parse(file) as? MutableMap<String, Any?>
        } catch (e: Exception) {
            throw GradleException("PLUGIN: module.json is not valid JSON: ${e.message}")
        } ?: throw GradleException("PLUGIN: module.json is not a JSON object")
        val name = m["name"] as? String ?: throw GradleException("PLUGIN: module.json has no \"name\"")
        if (!plainName.matches(name) || name.contains("..")) throw GradleException("PLUGIN: module.json name '$name' must be a plain folder name (letters, digits, '.', '_', '-')")
        if ((m["entry"] as? String).isNullOrBlank()) throw GradleException("PLUGIN: module.json of $name has no \"entry\"")
        val daemon = m["daemon"] as? Map<String, Any?>
        val harness = m["harness"] as? Map<String, Any?>
        val daemonDir = File(projectDir, "daemon/build.gradle.kts").isFile
        val harnessDir = File(projectDir, "harness/build.gradle.kts").isFile
        if (daemon != null && !daemonDir) throw GradleException("PLUGIN: module.json of $name declares \"daemon\" but daemon/build.gradle.kts does not exist")
        if (daemon == null && daemonDir) throw GradleException("PLUGIN: $name has a daemon/ folder but module.json declares no \"daemon\"")
        if (harness != null && !harnessDir) throw GradleException("PLUGIN: module.json of $name declares \"harness\" but harness/build.gradle.kts does not exist")
        if (harness == null && harnessDir) throw GradleException("PLUGIN: $name has a harness/ folder but module.json declares no \"harness\"")
        return PluginModule(name, m["version"] as? String, m, daemon != null, harness != null)
    }
}

/**
 * PLUGIN-GRADLE-1: `plugins { id("tech.superiorsoftllc.infantry.plugin") }` in a plugin module's build.gradle.kts.
 * Validates module.json, adds the SDK per half, and registers `ihq` (the package) and `catalogEntry`.
 */
class InfantryPluginPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val module = ModuleJson.read(project.projectDir)
        wireSdk(project, listOf("compileOnly", "jvmCompileOnly"), listOf("testImplementation", "jvmTestImplementation"), "infantryUiCore", "infantry-ui-core")

        // settings.gradle.kts names a half's project :<half>-plugin-<folder>-<Module>; the template's is :<half>-plugin-<its folder>.
        val folder = project.projectDir.parentFile.name
        fun half(prefix: String, present: Boolean, sdkProject: String, artifact: String): Project? {
            if (!present) return null
            val names = listOf(":$prefix-plugin-$folder-${module.name}", ":$prefix-plugin-${project.projectDir.name}")
            val p = names.firstNotNullOfOrNull { project.rootProject.findProject(it) }
                ?: throw GradleException("PLUGIN: ${module.name} has a $prefix half but no Gradle project ${names.first()} (settings.gradle.kts creates it from $prefix/build.gradle.kts)")
            p.plugins.withType(JavaPlugin::class.java) { wireSdk(p, listOf("compileOnly"), listOf("testImplementation"), sdkProject, artifact) }
            return p
        }
        val daemonProject = half("daemon", module.hasDaemon, "infantryDaemonApi", "infantry-daemon-api")
        val harnessProject = half("harness", module.hasHarness, "infantryHarnessApi", "infantry-harness-api")

        val ihqDir = project.layout.buildDirectory.dir("ihq")
        val uiJar = jarOf(project, if (project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) "jvmJar" else "jar")
        val daemonJar = daemonProject?.let { jarOf(it, "jar") }
        val harnessJar = harnessProject?.let { jarOf(it, "jar") }

        val manifestTask = project.tasks.register("ihqManifest") { t ->
            t.group = "build"
            t.description = "Writes the package's module.json (jar, daemon.jar, harness.jar moved to lib/)."
            t.inputs.file(project.layout.projectDirectory.file("module.json"))
            t.inputs.property("uiJar", uiJar.flatMap { it.archiveFileName })
            daemonJar?.let { t.inputs.property("daemonJar", it.flatMap { j -> j.archiveFileName }) }
            harnessJar?.let { t.inputs.property("harnessJar", it.flatMap { j -> j.archiveFileName }) }
            val out = ihqDir.map { it.file("stage/module.json") }
            t.outputs.file(out)
            t.doLast {
                val m = ModuleJson.read(project.projectDir).raw
                m["jar"] = "lib/" + uiJar.get().archiveFileName.get()
                @Suppress("UNCHECKED_CAST")
                if (daemonJar != null) m["daemon"] = (m["daemon"] as Map<String, Any?>) + ("jar" to "lib/" + daemonJar.get().archiveFileName.get())
                @Suppress("UNCHECKED_CAST")
                if (harnessJar != null) m["harness"] = (m["harness"] as Map<String, Any?>) + ("jar" to "lib/" + harnessJar.get().archiveFileName.get())
                out.get().asFile.also { it.parentFile.mkdirs() }.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(m)) + "\n")
            }
        }

        val ihq = project.tasks.register("ihq", Zip::class.java) { z ->
            z.group = "build"
            z.description = "Packages this plugin into build/ihq/<name>.ihq (module.json + lib/ jars of each half), signed if -Pihq.keystore is given."
            z.dependsOn(manifestTask, uiJar)
            daemonJar?.let { z.dependsOn(it) }
            harnessJar?.let { z.dependsOn(it) }
            z.archiveFileName.set(module.name + ".ihq")
            z.destinationDirectory.set(ihqDir)
            z.isPreserveFileTimestamps = false
            z.isReproducibleFileOrder = true
            z.from(ihqDir.map { it.file("stage/module.json") })
            listOfNotNull(uiJar, daemonJar, harnessJar).forEach { j -> z.from(j.flatMap { it.archiveFile }) { it.into("lib") } }
            val keystore = project.providers.gradleProperty("ihq.keystore")
            val alias = project.providers.gradleProperty("ihq.alias")
            val storePass = project.providers.environmentVariable("IHQ_STOREPASS")
            // MASCOT-LICENSE-1: an unsigned package always installs into the community tier, and the loader refuses a
            // community module without an author or a license (ModuleResolver.communityRefusal). Refuse to build one.
            val projectDir = project.projectDir
            z.doFirst {
                if (!keystore.isPresent) {
                    val raw = ModuleJson.read(projectDir).raw
                    val missing = listOf("author", "license").filter { (raw[it] as? String).isNullOrBlank() }
                    if (missing.isNotEmpty()) throw GradleException(
                        "PLUGIN: module.json of ${module.name} has no ${missing.joinToString(" or ") { "\"$it\"" }}: an unsigned package lands in the community tier, " +
                            "where the loader refuses it (\"community module without ${if ("author" in missing) "an author" else "a license"}\")",
                    )
                }
            }
            z.doLast {
                if (keystore.isPresent) {
                    val jarsigner = File(File(System.getProperty("java.home"), "bin"), "jarsigner" + if (OperatingSystem.current().isWindows) ".exe" else "").path
                    val args = listOf(jarsigner, "-keystore", keystore.get(), "-storepass", storePass.orNull ?: error("IHQ_STOREPASS is not set"), z.archiveFile.get().asFile.path, alias.orNull ?: error("-Pihq.alias is required"))
                    val p = ProcessBuilder(args).redirectErrorStream(true).start()
                    val text = p.inputStream.bufferedReader().readText()
                    check(p.waitFor() == 0) { "jarsigner failed: $text" }
                }
            }
        }

        project.tasks.register("catalogEntry") { t ->
            t.group = "build"
            t.description = "Writes build/ihq/catalog-entry.json: one schema-1 CatalogVersion for the .ihq (grants are recomputed by the daemon)."
            t.dependsOn(ihq)
            val url = project.providers.gradleProperty("ihq.url")
            val pkgFile = ihq.flatMap { it.archiveFile }
            val out = ihqDir.map { it.file("catalog-entry.json") }
            t.outputs.file(out)
            t.doLast {
                val pkg = pkgFile.get().asFile
                val sha = MessageDigest.getInstance("SHA-256").digest(pkg.readBytes()).joinToString("") { "%02x".format(it) }
                val m = ModuleJson.read(project.projectDir)
                val api = linkedMapOf<String, Any?>()
                m.apiPlugin?.let { api["plugin"] = it }
                m.apiDaemon?.let { api["daemon"] = it }
                m.apiHarness?.let { api["harness"] = it }
                val entry = linkedMapOf<String, Any?>("version" to (m.version ?: "0.0.0"), "api" to api, "url" to (url.orNull ?: pkg.name), "size" to pkg.length(), "sha256" to sha)
                out.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(entry)) + "\n")
            }
        }
    }

    /** The jar task, looked up lazily: the Kotlin multiplatform plugin and other projects create it after this plugin is applied. */
    private fun jarOf(p: Project, name: String): Provider<Jar> = p.provider { p.tasks.getByName(name) as Jar }

    /** The local SDK project when this build has it, else the published coordinate through the SDK BOM: decided by whether the project exists. */
    private fun wireSdk(p: Project, compileOnly: List<String>, test: List<String>, sdkProject: String, artifact: String) {
        val local = p.rootProject.findProject(":superiorSoftLibrary:$sdkProject")
        val coordinate = "tech.superiorsoftllc.infantry:$artifact"
        val bom = "tech.superiorsoftllc.infantry:infantry-sdk-bom:" + ((p.findProperty("infantry.sdk.version") as? String) ?: "latest.release")
        for (names in listOf(compileOnly, test)) {
            val conf = names.firstOrNull { p.configurations.findByName(it) != null } ?: continue
            if (local != null) p.dependencies.add(conf, p.dependencies.project(mapOf("path" to local.path)))
            else {
                p.dependencies.add(conf, p.dependencies.platform(bom))
                p.dependencies.add(conf, coordinate)
            }
        }
    }
}
