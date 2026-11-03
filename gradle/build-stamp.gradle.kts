// BUILD-STAMP-1: the one build string every packaged Infantry build carries, e.g. 1.0.1+20261023.1432.e85b223 (".dirty" appended
// when the working tree had changes). SemVer: <version>+<UTC yyyyMMdd>.<UTC HHmm>.<short sha>[.dirty].
//   * version: the `infantryVersion` Gradle property (root gradle.properties, or -PinfantryVersion); never edited per build.
//   * time:    stamped NOW when the build packages (a task the packaging guard watches, or headlessDist / dockerContext, is on
//              the command line); any other build
//              (run, installDist, test) takes HEAD's commit time instead, so it stays the same per commit and keeps caching.
//   * sha:     `git rev-parse --short HEAD`; "unknown" outside a git checkout.
// Applied from the root build.gradle.kts. Subprojects read rootProject.extra:
//   infantryBuild (String), infantryBuildVersion, infantryBuildSha, infantryBuildDirty (Boolean), infantryPackagingTask (Regex).
// The app shows it (-Dinfantry.build / the APK versionName), the daemon sends it in `ready` (build=), and the packaging guard
// writes it into BUILD-INFO.txt and the copied file names. `./gradlew verifyBuildStamp` checks the format and the dirty flag.

fun stampGit(vararg args: String): String? = try {
    val p = ProcessBuilder(listOf("git") + args).directory(rootDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else null
} catch (e: Exception) { null }

/** The format, in one place. [sha] is used as given; [dirty] appends ".dirty". */
fun formatBuildStamp(version: String, at: java.time.Instant, sha: String, dirty: Boolean): String =
    "$version+" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd.HHmm").withZone(java.time.ZoneOffset.UTC).format(at) +
        ".$sha" + if (dirty) ".dirty" else ""

val buildStampPattern = Regex("""^\d+\.\d+\.\d+\+\d{8}\.\d{4}\.([0-9a-f]{4,40}|unknown)(\.dirty)?$""")

// Same task names the packaging guard (gradle/packaging-guard.gradle.kts) stamps; it reads this Regex from here.
val packagingTask = Regex("""createDistributable|createReleaseDistributable|package(Release)?(Msi|Exe|Dmg|Deb)|releaseAssets|assemble(Debug|Release)?|bundle(Debug|Release)""")

val stampVersion: String = providers.gradleProperty("infantryVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
val stampSha: String = stampGit("rev-parse", "--short", "HEAD") ?: "unknown"
val stampDirty: Boolean = stampGit("status", "--porcelain")?.lines()?.any { it.isNotBlank() } ?: false
// The headless server archive and the Docker context are packaged too (not guarded, but stamped at package time).
val stampNowTask = Regex(packagingTask.pattern + "|headlessDist(Zip|Tar)?|dockerContext")
val stampPackaging: Boolean = gradle.startParameter.taskNames.any { stampNowTask.matches(it.substringAfterLast(':')) }
val stampTime: java.time.Instant = if (stampPackaging) java.time.Instant.now() else
    stampGit("log", "-1", "--format=%ct", "HEAD")?.toLongOrNull()?.let { java.time.Instant.ofEpochSecond(it) } ?: java.time.Instant.EPOCH
val infantryBuild: String = formatBuildStamp(stampVersion, stampTime, stampSha, stampDirty)

extra["infantryBuild"] = infantryBuild
extra["infantryBuildVersion"] = stampVersion
extra["infantryBuildSha"] = stampSha
extra["infantryBuildDirty"] = stampDirty
extra["infantryPackagingTask"] = packagingTask

tasks.register("verifyBuildStamp") {
    group = "verification"
    description = "BUILD-STAMP-1: checks the build string's format and dirty flag, and prints this build's string."
    doLast {
        val at = java.time.Instant.parse("2026-10-23T14:32:59Z")
        val clean = formatBuildStamp("1.0.1", at, "e85b223", dirty = false)
        check(clean == "1.0.1+20261023.1432.e85b223") { "BUILD-STAMP-1: clean format is '$clean'" }
        val dirty = formatBuildStamp("1.0.1", at, "e85b223", dirty = true)
        check(dirty == "1.0.1+20261023.1432.e85b223.dirty") { "BUILD-STAMP-1: dirty format is '$dirty'" }
        // Minutes are UTC whatever the build machine's zone: 23:59 in New York is the next day in UTC.
        val utc = formatBuildStamp("2.3.4", java.time.ZonedDateTime.parse("2026-10-23T23:59:00-04:00").toInstant(), "abc1234", false)
        check(utc == "2.3.4+20261024.0359.abc1234") { "BUILD-STAMP-1: the time is not UTC: '$utc'" }
        listOf(clean, dirty, utc).forEach { check(buildStampPattern.matches(it)) { "BUILD-STAMP-1: '$it' does not match $buildStampPattern" } }
        check(buildStampPattern.matches(infantryBuild)) { "BUILD-STAMP-1: this build's string '$infantryBuild' does not match $buildStampPattern" }
        check(infantryBuild.endsWith(".dirty") == stampDirty) { "BUILD-STAMP-1: dirty flag $stampDirty but string '$infantryBuild'" }
        check(infantryBuild.startsWith("$stampVersion+")) { "BUILD-STAMP-1: '$infantryBuild' does not start with the infantryVersion $stampVersion" }
        logger.lifecycle("BUILD-STAMP-1: OK, this build is $infantryBuild")
    }
}
