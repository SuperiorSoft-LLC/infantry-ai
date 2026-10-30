package com.superiorsoftllc.agenthq.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DAEMON-PLUGINS-1 layering rule: the seated demo lockdown (visitor token, per-IP turn limit, seeded sessions and their
 * warm-up, the demo's refusals) is Demo_Lock's daemon plugin (plugins/demo/Demo_Lock/daemon). Fails when the daemon's own
 * code (comments aside) names one of its symbols again.
 */
class NoDemoInDaemonCoreGuardTest {
    private val main = File(TestDaemonPlugins.repo, "superiorSoftLibrary/agentHq/src/main/kotlin")
    private val symbols = listOf(
        "DemoLock", "DemoTurnLimit", "demoLock", "DEMO_LOCK", "AGENTHQ_DEMO_", "/demo/visitor-token", "demo_limit", "visitorToken",
        "warmUp", "seatFirstTurn",
    )

    @Test fun `the moved files are gone from core`() {
        for (name in listOf("DemoLock.kt", "DemoTurnLimit.kt", "McpTicketFilesTools.kt")) {
            assertTrue(main.walkTopDown().none { it.name == name }, "$name is back in core")
        }
    }

    @Test fun `core daemon code names no demo lock symbol`() {
        val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 100, "the daemon sources were not found under $main")
        val hits = sources.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) ->
                val code = line.trim()
                !code.startsWith("//") && !code.startsWith("*") && !code.startsWith("/*") && symbols.any { it in code.substringBefore(" // ") }
            }.map { (i, line) -> "${f.name}:${i + 1}: ${line.trim().take(120)}" }
        }
        assertTrue(hits.isEmpty(), "demo lock symbols in core daemon code (they belong in plugins/demo/Demo_Lock/daemon):\n${hits.joinToString("\n")}")
    }

    @Test fun `core daemon sources have no demo word at all, comments and settings keys included`() {
        val word = Regex("(?i)(^|[^a-z])demo")
        val hits = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> word.containsMatchIn(l) }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim().take(120)}" }
        }.toList()
        assertTrue(hits.isEmpty(), "the word demo in core daemon sources (values come from the Demo_Lock plugin and preset):\n${hits.joinToString("\n")}")
    }
}
