// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PACKAGE-1 (docs/DAEMON-PLUGINS.md section 9): the one place that knows what a module's daemon half (and, HARNESS-PLUGIN-3, its
// harness half: module.json `harness.jar`, built by `:harness-plugin-<folder>-<Module>:jar`) is, for every packaging
// path (desktop stageOfficialModules / prepareDistributableResources, installDist, dockerContext, headlessDist via installDist).
// A module has a daemon half when its module.json has a `daemon` block; the half is `daemon.jar` (relative to the module
// folder, where `:daemon-plugin-<folder>-<Module>:jar` writes it) plus the module's `policies/` folder when it has one.
// Applied from the root build.gradle.kts. Subprojects read rootProject.extra:
//   ihqDaemonJar(moduleDir: File): String?                         module.json's daemon.jar, null when there is no daemon block
//   ihqVerifyDaemonHalves(modulesRoot: File, label: String, expected: List<String>)
//       fails the build when a staged module.json declares a daemon half whose jar is not next to it, or when a module in
//       [expected] (folder names) is not staged at all: a missing half can never ship silently.
// `verifyDaemonHalvesGuard` (wired into :superiorSoftLibrary:infantry:check) runs the check on scratch folders.

/** `daemon.jar` of `<moduleDir>/module.json`; null when the module declares no daemon half. A `daemon` block without a jar fails. */
fun daemonJar(moduleDir: File): String? {
    val json = File(moduleDir, "module.json").takeIf { it.isFile } ?: return null
    val daemon = (groovy.json.JsonSlurper().parse(json) as Map<*, *>)["daemon"] as? Map<*, *> ?: return null
    return (daemon["jar"] as? String)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("PACKAGE-1: $json has a daemon block without daemon.jar")
}

/**
 * HARNESS-PLUGIN-3: `harness.jar` of `<moduleDir>/module.json` (the harness half, `:harness-plugin-<folder>-<Module>:jar`);
 * null when the module declares no harness. A `harness` block without a jar fails, like a daemon block.
 */
fun harnessJar(moduleDir: File): String? {
    val json = File(moduleDir, "module.json").takeIf { it.isFile } ?: return null
    val harness = (groovy.json.JsonSlurper().parse(json) as Map<*, *>)["harness"] as? Map<*, *> ?: return null
    return (harness["jar"] as? String)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("HARNESS-PLUGIN-3: $json has a harness block without harness.jar")
}

/** Every staged module folder (`<root>/<tier>/<Module>/module.json`) whose declared daemon jar is missing, as messages. */
fun daemonHalfProblems(modulesRoot: File, expected: List<String>): List<String> {
    val staged = modulesRoot.listFiles { f -> f.isDirectory }.orEmpty()
        .flatMap { tier -> tier.listFiles { f -> File(f, "module.json").isFile }.orEmpty().toList() }
    val problems = staged.flatMap { dir ->
        listOfNotNull(
            daemonJar(dir)?.let { jar -> if (File(dir, jar).isFile) null else "${dir.parentFile.name}/${dir.name} declares daemon.jar $jar but it is not staged" },
            harnessJar(dir)?.let { jar -> if (File(dir, jar).isFile) null else "${dir.parentFile.name}/${dir.name} declares harness.jar $jar but it is not staged" },
        )
    }
    val names = staged.map { it.name }.toSet()
    return problems + expected.filter { it !in names }.map { "$it (a module with a daemon half) is not staged at all" }
}

fun verifyDaemonHalves(modulesRoot: File, label: String, expected: List<String>) {
    val problems = daemonHalfProblems(modulesRoot, expected)
    if (problems.isNotEmpty()) throw GradleException("PACKAGE-1: $label ships a module without its daemon half ($modulesRoot): ${problems.joinToString("; ")}")
    val halves = modulesRoot.listFiles { f -> f.isDirectory }.orEmpty().sumOf { t -> t.listFiles { f -> daemonJar(f) != null }.orEmpty().size }
    val harnesses = modulesRoot.listFiles { f -> f.isDirectory }.orEmpty().sumOf { t -> t.listFiles { f -> harnessJar(f) != null }.orEmpty().size }
    logger.lifecycle("PACKAGE-1: $label carries $halves daemon half/halves and $harnesses harness half/halves, every declared jar present ($modulesRoot)")
}

rootProject.extra["ihqDaemonJar"] = { dir: File -> daemonJar(dir) }
rootProject.extra["ihqHarnessJar"] = { dir: File -> harnessJar(dir) }
rootProject.extra["ihqVerifyDaemonHalves"] = { root: File, label: String, expected: List<String> -> verifyDaemonHalves(root, label, expected) }

tasks.register("verifyDaemonHalvesGuard") {
    group = "verification"
    description = "PACKAGE-1: the daemon-half packaging check passes a complete modules root and fails a missing jar or module."
    val scratch = layout.buildDirectory.dir("tmp/verifyDaemonHalvesGuard")
    doLast {
        val root = scratch.get().asFile.apply { deleteRecursively() }
        fun module(name: String, daemon: Boolean, withJar: Boolean) = File(root, "official/$name").apply {
            mkdirs()
            val block = if (daemon) ""","daemon":{"entry":"x.Y","jar":"daemon/build/libs/$name-daemon.jar"}""" else ""
            File(this, "module.json").writeText("""{"name":"$name","tier":"official","jar":"build/libs/$name.jar"$block}""")
            if (withJar) File(this, "daemon/build/libs/$name-daemon.jar").apply { parentFile.mkdirs(); writeText("jar") }
        }
        module("Ui_Only", daemon = false, withJar = false)
        module("With_Half", daemon = true, withJar = true)
        check(daemonHalfProblems(root, listOf("With_Half")).isEmpty()) { "PACKAGE-1 guard: a complete root was refused: ${daemonHalfProblems(root, listOf("With_Half"))}" }
        check(daemonHalfProblems(root, listOf("Not_Staged")).single().startsWith("Not_Staged")) { "PACKAGE-1 guard: a module missing from the stage passed" }
        module("No_Jar", daemon = true, withJar = false)
        val missing = daemonHalfProblems(root, emptyList())
        check(missing.size == 1 && "official/No_Jar declares daemon.jar daemon/build/libs/No_Jar-daemon.jar" in missing.single()) { "PACKAGE-1 guard: a missing daemon jar passed: $missing" }
        check(runCatching { verifyDaemonHalves(root, "guard self-test", emptyList()) }.isFailure) { "PACKAGE-1 guard: verifyDaemonHalves did not fail the build" }
        // HARNESS-PLUGIN-3: a harness half is checked the same way.
        File(root, "official/No_Jar").deleteRecursively()
        fun harnessModule(name: String, withJar: Boolean) = File(root, "official/$name").apply {
            mkdirs()
            File(this, "module.json").writeText("""{"name":"$name","tier":"official","harness":{"jar":"harness/build/libs/$name-harness.jar","contract":"2.0.0","ids":["x"]}}""")
            if (withJar) File(this, "harness/build/libs/$name-harness.jar").apply { parentFile.mkdirs(); writeText("jar") }
        }
        harnessModule("With_Harness", withJar = true)
        check(daemonHalfProblems(root, listOf("With_Harness")).isEmpty()) { "HARNESS-PLUGIN-3 guard: a complete harness module was refused: ${daemonHalfProblems(root, listOf("With_Harness"))}" }
        harnessModule("No_Harness_Jar", withJar = false)
        val noHarness = daemonHalfProblems(root, emptyList())
        check(noHarness.size == 1 && "official/No_Harness_Jar declares harness.jar" in noHarness.single()) { "HARNESS-PLUGIN-3 guard: a missing harness jar passed: $noHarness" }
        logger.lifecycle("PACKAGE-1: daemon-half guard OK")
    }
}
