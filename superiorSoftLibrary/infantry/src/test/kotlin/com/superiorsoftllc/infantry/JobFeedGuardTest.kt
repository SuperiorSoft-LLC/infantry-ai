// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JOBFEED-1: core and the shell know nothing of the Job Feed plugin, and the plugin is off until the owner enables it. */
class JobFeedGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "plugins/official").isDirectory }
    private val coreDirs = listOf(
        "infantry", "infantryCore", "infantryProtocol", "infantryRunner", "infantryDaemonApi", "infantryHarnessApi", "infantryBoard",
        "infantryPlugins", "infantryUiCore", "infantryAppUi", "infantryUi", "infantryWeb", "infantryAndroid",
    )
    private val name = Regex("(?i)job_?feed")

    @Test fun `no core or shell source names the plugin`() {
        val hits = coreDirs.map { File(root, "superiorSoftLibrary/$it") }.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().onEnter { it.name != "build" && it.name != "test" && it.name != "jvmTest" && it.name != "commonTest" }
                .filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                    f.readLines().filter { name.containsMatchIn(it) && "JOBFEED-1" !in it }.map { "${f.name}: ${it.trim()}" }
                }.toList()
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun `the plugin is optional and off by default`() {
        val text = File(root, "plugins/official/Infantry_JobFeed/module.json").readText()
        assertTrue(Regex("""\"defaultEnabled\"\s*:\s*false""".replace("\\\"", "\""))
            .also { check(it.pattern.isNotEmpty()) }.containsMatchIn(text), "module.json must say defaultEnabled false")
        assertTrue(!File(root, "plugins/modules.json").readText().contains("Infantry_JobFeed"), "modules.json must not enable it")
    }
}
