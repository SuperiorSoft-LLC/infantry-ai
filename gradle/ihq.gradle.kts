// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// S5: packages a plugin module into <Vendor_Module>.ihq (apply with: apply(from = rootProject.file("gradle/ihq.gradle.kts"))).
// Layout: module.json (its "jar" rewritten to lib/<jar>), lib/<the module's jar>, and the data folders module.json `files` names. Run `ihq`; output build/ihq/<name>.ihq.
// Reads <projectDir>/module.json and the project's `jar` task. Signing is optional and never uses a key from the repo:
// pass -Pihq.keystore=<file> -Pihq.alias=<alias> (password in env IHQ_STOREPASS) and jarsigner signs the whole package.
// The installer (IhqInstaller) decides the tier from the signer; an unsigned .ihq is only accepted if install.json allows it.
val moduleJsonFile = layout.projectDirectory.file("module.json").asFile
val ihqDir = layout.buildDirectory.dir("ihq")

@Suppress("UNCHECKED_CAST")
fun manifest() = groovy.json.JsonSlurper().parse(moduleJsonFile) as MutableMap<String, Any?>

val ihqManifest = tasks.register("ihqManifest") {
    group = "build"
    description = "Writes the package's module.json (jar moved to lib/)."
    val jarTask = tasks.named<Jar>("jar")
    inputs.file(moduleJsonFile)
    inputs.property("jarName", jarTask.flatMap { it.archiveFileName })
    val out = ihqDir.map { it.file("stage/module.json") }
    outputs.file(out)
    doLast {
        val m = manifest()
        m["jar"] = "lib/" + jarTask.get().archiveFileName.get()
        out.get().asFile.also { it.parentFile.mkdirs() }.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(m)) + "\n")
    }
}

tasks.register<Zip>("ihq") {
    group = "build"
    description = "Packages this plugin module into build/ihq/<name>.ihq (module.json + lib/<jar>), signed if -Pihq.keystore is given."
    val jarTask = tasks.named<Jar>("jar")
    dependsOn(ihqManifest, jarTask)
    archiveFileName.set(provider { (manifest()["name"] as String) + ".ihq" })
    destinationDirectory.set(ihqDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(ihqManifest.map { ihqDir.get().file("stage/module.json") })
    from(jarTask.map { it.archiveFile }) { into("lib") }
    // PLUGIN-PKG-FILES-1: the data folders module.json `files` declares go in as they are.
    (manifest()["files"] as? List<*>).orEmpty().filterIsInstance<String>().forEach { d -> from(layout.projectDirectory.dir(d)) { into(d) } }
    val keystore = providers.gradleProperty("ihq.keystore")
    val alias = providers.gradleProperty("ihq.alias")
    val storePass = providers.environmentVariable("IHQ_STOREPASS")
    doLast {
        if (keystore.isPresent) {
            val file = archiveFile.get().asFile
            val jarsigner = File(File(System.getProperty("java.home"), "bin"), "jarsigner" + if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else "").path
            val args = mutableListOf(jarsigner, "-keystore", keystore.get(), "-storepass", storePass.orNull ?: error("IHQ_STOREPASS is not set"), file.path, alias.orNull ?: error("-Pihq.alias is required"))
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "jarsigner failed: $text" }
        }
    }
}
