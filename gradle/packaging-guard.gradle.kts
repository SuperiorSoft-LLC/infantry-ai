// PACKAGING-GUARD-1: every packaging task (Compose createDistributable/package*Msi|Exe, releaseAssets, Android assemble*/bundle*)
//  1. prints the git branch and short sha at the start of the build,
//  2. refuses a dirty working tree or a checkout that is neither feature-agenthq-mvp nor a v* tag
//     (override: -PallowAnyBranch=true, which downgrades the refusal to a warning),
//  3. writes BUILD-INFO.txt next to what it built and adds a sha-named copy of each msi/exe/apk under <outdir>/<sha>/.
// The original output paths stay put: .github/workflows/release.yml (tag build, no override) reads them.
// Applied from :agentHqUi and :agentHqAndroid (apply(from = rootProject.file("gradle/packaging-guard.gradle.kts"))).

fun git(vararg args: String): String? = try {
    val p = ProcessBuilder(listOf("git") + args).directory(rootProject.projectDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else null
} catch (e: Exception) { null }

val releaseBranch = "feature-agenthq-mvp"
val guardedTask = Regex("""createDistributable|createReleaseDistributable|package(Release)?(Msi|Exe|Dmg|Deb)|releaseAssets|assemble(Debug|Release)?|bundle(Debug|Release)""")
fun isGuarded(t: Task) = guardedTask.matches(t.name)

val sha: String = git("rev-parse", "--short", "HEAD") ?: "unknown"
val branch: String = git("rev-parse", "--abbrev-ref", "HEAD") ?: "unknown"
val tag: String? = (git("describe", "--exact-match", "--tags", "HEAD")?.takeIf { it.startsWith("v") })
    ?: System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && it.startsWith("v") }
val dirty: List<String> = git("status", "--porcelain")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
val allowAny = providers.gradleProperty("allowAnyBranch").orNull == "true"

val buildInfo: String = buildString {
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

fun stamp(dir: File, exts: Set<String>) {
    if (!dir.isDirectory) return
    File(dir, "BUILD-INFO.txt").writeText(buildInfo)
    val shaDir = File(dir, sha)
    dir.listFiles { f -> f.isFile && f.extension in exts }?.forEach { f ->
        shaDir.mkdirs()
        f.copyTo(File(shaDir, "${f.nameWithoutExtension}-$sha.${f.extension}"), overwrite = true)
        File(shaDir, "BUILD-INFO.txt").writeText(buildInfo)
    }
}

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
