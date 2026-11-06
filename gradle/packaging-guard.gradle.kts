// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PACKAGING-GUARD-1: every packaging task (Compose createDistributable/package*Msi|Exe, releaseAssets, Android assemble*/bundle*)
//  1. prints the git branch and short sha at the start of the build,
//  2. refuses a dirty working tree or a checkout that is neither feature-agenthq-mvp nor a v* tag
//     (override: -PallowAnyBranch=true, which downgrades the refusal to a warning),
//  3. writes BUILD-INFO.txt next to what it built and adds a copy of each msi/exe/apk under <outdir>/<sha>/, named with the
//     BUILD-STAMP-1 build string (gradle/build-stamp.gradle.kts): InfantryAI-1.0.1.msi -> <sha>/InfantryAI-1.0.1+20261023.1432.e85b223.msi,
//     app-debug.apk -> <sha>/app-debug-1.0.1+20261023.1432.e85b223.apk. BUILD-INFO.txt carries the same string as build=.
//     `verifyPackagingStamp` (wired into check) runs this stamping on dummy files and checks both.
// The original output paths stay put: .github/workflows/release.yml (tag build, no override) reads them.
// Applied from :infantryUi and :infantryAndroid (apply(from = rootProject.file("gradle/packaging-guard.gradle.kts"))).

fun git(vararg args: String): String? = try {
    val p = ProcessBuilder(listOf("git") + args).directory(rootProject.projectDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else null
} catch (e: Exception) { null }

val releaseBranch = "feature-agenthq-mvp"
// BUILD-STAMP-1: the task names, version, sha and build string all come from gradle/build-stamp.gradle.kts (root).
val guardedTask = rootProject.extra["infantryPackagingTask"] as Regex
fun isGuarded(t: Task) = guardedTask.matches(t.name)

val sha: String = rootProject.extra["infantryBuildSha"] as String
val buildVersion: String = rootProject.extra["infantryBuildVersion"] as String
val buildStamp: String = rootProject.extra["infantryBuild"] as String
val branch: String = git("rev-parse", "--abbrev-ref", "HEAD") ?: "unknown"
val tag: String? = (git("describe", "--exact-match", "--tags", "HEAD")?.takeIf { it.startsWith("v") })
    ?: System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && it.startsWith("v") }
val dirty: List<String> = git("status", "--porcelain")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
val allowAny = providers.gradleProperty("allowAnyBranch").orNull == "true"

val buildInfo: String = buildString {
    appendLine("build=$buildStamp")
    appendLine("version=$buildVersion")
    appendLine("branch=$branch")
    appendLine("sha=$sha")
    appendLine("tag=${tag ?: "-"}")
    appendLine("dirty=${dirty.isNotEmpty()}")
    appendLine("allowAnyBranch=$allowAny")
    appendLine("built=${java.time.Instant.now()}")
}

gradle.taskGraph.whenReady {
    val packaging = allTasks.filter { it.project == project && isGuarded(it) }
    val marker = "packagingGuardRan"
    if (packaging.isNotEmpty() && !gradle.extensions.extraProperties.has(marker)) {
        gradle.extensions.extraProperties.set(marker, true)
        println("PACKAGING-GUARD: packaging branch=$branch sha=$sha${tag?.let { " tag=$it" } ?: ""} (${packaging.joinToString { it.name }})")
        val problems = mutableListOf<String>()
        if (dirty.isNotEmpty()) problems += "working tree is dirty (${dirty.size} change(s), first: ${dirty.first()})"
        if (branch != releaseBranch && tag == null) problems += "checkout is '$branch' ($sha), not $releaseBranch or a v* tag"
        if (problems.isNotEmpty()) {
            val msg = "PACKAGING-GUARD: " + problems.joinToString("; ")
            if (allowAny) println("$msg -- continuing because -PallowAnyBranch=true") else
                throw GradleException("$msg. Check out $releaseBranch clean, or pass -PallowAnyBranch=true to package anyway.")
        }
    }
}

/** BUILD-STAMP-1: the copy's name: the version suffix (if any) gives way to the whole build string, which already holds it. */
// NIGHTLY-1: a nightly MSI is named with its numeric package version (-PinfantryPackageVersion), not the SemVer; strip either.
val packageVersion: String? = providers.gradleProperty("infantryPackageVersion").orNull?.trim()?.takeIf { it.isNotEmpty() }
fun stampedName(f: File): String =
    "${f.nameWithoutExtension.removeSuffix("-$buildVersion").let { n -> packageVersion?.let { n.removeSuffix("-$it") } ?: n }}-$buildStamp.${f.extension}"

fun stamp(dir: File, exts: Set<String>) {
    if (!dir.isDirectory) return
    File(dir, "BUILD-INFO.txt").writeText(buildInfo)
    val shaDir = File(dir, sha)
    dir.listFiles { f -> f.isFile && f.extension in exts }?.forEach { f ->
        shaDir.mkdirs()
        f.copyTo(File(shaDir, stampedName(f)), overwrite = true)
        File(shaDir, "BUILD-INFO.txt").writeText(buildInfo)
    }
}

// BUILD-STAMP-1 acceptance without minutes of jpackage: stamp dummy outputs in a scratch folder under build/ and check that
// BUILD-INFO.txt and the copied file names carry the very build string the app and the daemon report.
val verifyPackagingStamp = tasks.register("verifyPackagingStamp") {
    group = "verification"
    description = "BUILD-STAMP-1: the packaging guard writes the build string into BUILD-INFO.txt and the copied msi/exe/apk names."
    val scratch = layout.buildDirectory.dir("tmp/verifyPackagingStamp")
    doLast {
        val dir = scratch.get().asFile.apply { deleteRecursively(); mkdirs() }
        val outputs = listOf("InfantryAI-$buildVersion.msi", "InfantryAI-$buildVersion.exe", "app-debug.apk").map { File(dir, it).apply { writeText(name) } }
        stamp(dir, setOf("msi", "exe", "apk"))
        listOf(File(dir, "BUILD-INFO.txt"), File(dir, "$sha/BUILD-INFO.txt")).forEach { info ->
            check(info.isFile) { "BUILD-STAMP-1: no ${info.path}" }
            check("build=$buildStamp" in info.readLines()) { "BUILD-STAMP-1: ${info.path} has no build=$buildStamp:\n${info.readText()}" }
        }
        val expected = listOf("InfantryAI-$buildStamp.msi", "InfantryAI-$buildStamp.exe", "app-debug-$buildStamp.apk")
        val copied = File(dir, sha).list()?.filter { !it.endsWith(".txt") }?.sorted().orEmpty()
        check(copied == expected.sorted()) { "BUILD-STAMP-1: copies are $copied, expected $expected" }
        expected.zip(outputs).forEach { (name, original) ->
            check(File(dir, "$sha/$name").readText() == original.name) { "BUILD-STAMP-1: $name is not a copy of ${original.name}" }
            check(original.isFile) { "BUILD-STAMP-1: ${original.name} must stay where release.yml reads it" }
        }
        logger.lifecycle("BUILD-STAMP-1: packaging stamp OK ($buildStamp)")
    }
}
tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyPackagingStamp) }

tasks.matching { isGuarded(it) }.configureEach {
    val taskName = name
    doLast {
        val b = layout.buildDirectory.get().asFile
        when {
            taskName.endsWith("Distributable") -> {
                val main = if (taskName.contains("Release")) "main-release" else "main"
                File(b, "compose/binaries/$main/app").listFiles { f -> f.isDirectory }?.forEach { stamp(it, emptySet()) }
            }
            taskName == "releaseAssets" -> b.resolve("release").listFiles { f -> f.isDirectory && f.name != sha }
                ?.forEach { stamp(it, setOf("msi", "exe")) }
            taskName.startsWith("package") -> {
                val main = if (taskName.startsWith("packageRelease")) "main-release" else "main"
                val fmt = taskName.removePrefix("packageRelease").removePrefix("package").lowercase()
                stamp(File(b, "compose/binaries/$main/$fmt"), setOf("msi", "exe", "dmg", "deb"))
            }
            else -> b.resolve("outputs/apk").listFiles { f -> f.isDirectory }?.forEach { stamp(it, setOf("apk")) }
        }
    }
}
