package com.superiorsoftllc.agenthq.ui.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the platform boundary of :agenthqUiCore: commonMain stays portable (Android + JVM), and each
 * platform owns its host in its own source set. Runs from the module directory.
 */
class PlatformBoundaryTest {
    private val module = File(".").absoluteFile.normalize()
    private fun src(set: String) = File(module, "src/$set/kotlin")
    private fun kotlinFiles(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun text(set: String) = kotlinFiles(src(set)).joinToString("\n") { it.readText() }

    private val forbiddenInCommon = listOf(
        Regex("""\bimport java\."""), Regex("""\bimport javax\."""), Regex("""\bimport android\."""),
        Regex("""\bimport androidx\.compose\.ui\.window\."""), Regex("""\bimport androidx\.compose\.desktop"""),
        Regex("""\bimport org\.jetbrains\.skia"""), Regex("""System\.getenv"""), Regex("""\bjava\.io\.File\b"""),
        Regex("""\bFile\("""), Regex("""\bapplication\s*\{"""),
    )

    @Test fun commonMainUsesNoPlatformOrDesktopApis() {
        val offenders = kotlinFiles(src("commonMain")).flatMap { f ->
            val body = f.readText()
            forbiddenInCommon.filter { it.containsMatchIn(body) }.map { "${f.name}: ${it.pattern}" }
        }
        assertTrue(offenders.isEmpty(), "platform APIs in commonMain: $offenders")
    }

    @Test fun buildFileKeepsDesktopOutOfTheCore() {
        val build = File(module, "build.gradle.kts").readText()
        assertTrue("compose.desktop" !in build, "compose.desktop must not be a core dependency")
        assertTrue("androidLibrary" in build && "jvm()" in build, "core must target both jvm() and androidLibrary")
    }

    @Test fun eachPlatformOwnsItsHostInItsOwnSourceSet() {
        assertTrue(File(src("jvmMain"), "com/superiorsoftllc/agenthq/ui/shell/JvmPlatformHost.kt").isFile, "jvmMain host")
        assertTrue(File(src("androidMain"), "com/superiorsoftllc/agenthq/ui/shell/AndroidPlatformHost.kt").isFile, "androidMain host")
        assertTrue(File(src("androidMain"), "com/superiorsoftllc/agenthq/ui/shell/AgentHqActivity.kt").isFile, "android entry")
        assertTrue(File(module, "src/androidMain/AndroidManifest.xml").isFile, "android manifest")
    }

    @Test fun expectDefaultIsDeclaredOnceAndActualisedPerPlatform() {
        assertTrue("expect fun defaultPlatformHost" in text("commonMain"), "expect defaultPlatformHost")
        assertTrue("actual fun defaultPlatformHost" in text("jvmMain"), "jvm actual")
        assertTrue("actual fun defaultPlatformHost" in text("androidMain"), "android actual")
    }
}
