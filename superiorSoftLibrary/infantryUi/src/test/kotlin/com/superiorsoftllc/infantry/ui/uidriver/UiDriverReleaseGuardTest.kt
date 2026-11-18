// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.uidriver

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI-DRIVER-1: the UI driver is off and unreachable in a release build. It lives only in the infantryUi TEST source set,
 * so a packaged app (built from the main source sets) has no driver class, no API and no opt-in to flip:
 *
 * - no main source set of any client module or plugin names the driver or its opt-in variable;
 * - the only Gradle task that runs it uses the test classpath, and the packaged app's main class and jvmArgs carry neither;
 * - the packaging scripts never name it;
 * - when the main classes are built, none of them is a driver class.
 */
class UiDriverReleaseGuardTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "superiorSoftLibrary").isDirectory }
    private val tokens = listOf("INFANTRY_UI_DRIVER", "UiDriver", "uidriver", "runUiDriver")
    private val testDirs = setOf("test", "jvmTest", "commonTest", "androidTest", "androidUnitTest", "wasmJsTest", "desktopTest", "testFixtures")

    private fun mainSources(): Sequence<File> {
        val modules = File(root, "superiorSoftLibrary").listFiles().orEmpty().filter { it.isDirectory }.map { File(it, "src") }
        val plugins = File(root, "plugins").walkTopDown().onEnter { it.name != "build" && it.name !in testDirs && !it.name.startsWith(".") }
            .filter { it.isDirectory && it.name == "src" }.toList()
        return (modules + plugins).asSequence().filter { it.isDirectory }.flatMap { src ->
            src.walkTopDown().onEnter { it.name !in testDirs && it.name != "build" }.filter { it.isFile && it.extension in setOf("kt", "kts", "java") }
        }
    }

    @Test
    fun `no main source set names the driver`() {
        val offenders = mainSources().filter { f -> f.readText().let { text -> tokens.any { it in text } } }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertEquals(emptyList(), offenders, "the UI driver belongs to the infantryUi test source set only")
    }

    @Test
    fun `the driver task uses the test classpath and the packaged app never names it`() {
        val gradle = File(root, "superiorSoftLibrary/infantryUi/build.gradle.kts").readText()
        val task = gradle.substringAfter("tasks.register<JavaExec>(\"runUiDriver\")").substringBefore("\n}\n")
        assertTrue("sourceSets[\"test\"].runtimeClasspath" in task, "runUiDriver runs on the test classpath")
        assertTrue("java.awt.headless\", \"true\"" in task, "runUiDriver is headless")
        val packaged = gradle.substringAfter("compose.desktop {", "")
        assertTrue(packaged.isNotEmpty(), "the packaged app's compose.desktop block is found")
        assertTrue(tokens.none { it in packaged }, "the packaged app's main class and jvmArgs never name the driver")
        val packaging = File(root, "scripts").walkTopDown().filter { it.isFile && (it.name.startsWith("package-") || it.path.contains("msix")) }
        assertEquals(emptyList(), packaging.filter { f -> tokens.any { it in f.readText() } }.map { it.name }.toList())
    }

    @Test
    fun `no built main class is a driver class`() {
        val mainClasses = File(root, "superiorSoftLibrary/infantryUi/build/classes/kotlin/main")
        if (!mainClasses.isDirectory) return // not built in this run; the source check above already covers it
        assertEquals(emptyList(), mainClasses.walkTopDown().filter { it.isFile && "uidriver" in it.path.lowercase() }.map { it.name }.toList())
    }
}
