package com.superiorsoftllc.infantry.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HARNESS H9 (docs/HARNESS-PLUGINS.md): the core jar carries no concrete harness.
 *  (a) the only class in core main that implements the Harness SPI is the generic descriptor-driven CliHarness (no id of its own);
 *  (b) a built-in harness id string literal appears in core main sources only at the allow-listed sites, each with its reason
 *      (the id is data for a mechanism, never a branch on a concrete adapter). A stale allow-list entry fails too.
 */
class HarnessCoreGuardTest {
    private val mains = listOf("infantry", "infantryCore").map { File(TestDaemonPlugins.repo, "superiorSoftLibrary/$it/src/main/kotlin/com/superiorsoftllc/infantry") }
    private fun files() = mains.flatMap { it.walkTopDown().toList() }.asSequence().filter { it.isFile && it.extension == "kt" }.toList()
    private fun code(f: File) = f.readLines().filter { l -> l.trim().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") } }

    @Test fun `no core class implements the Harness SPI except the generic descriptor harness`() {
        // `class X : Harness {`, `class X(..) : A, Harness {` and the closing line `) : Harness {` of a multi-line constructor
        val impl = Regex("""^\s*(?:(?:internal|private|data|open|abstract)\s+)*(?:(?:class|object)\s+\w+.*|\))\s*:\s*(?:[\w.<>, ]*,\s*)?Harness\s*(?:\(\s*\))?\s*\{""")
        val hits = files().filter { f -> code(f).any { impl.containsMatchIn(it) } }.map { it.name }.toSet()
        assertTrue(hits == setOf("CliHarness.kt"), "core classes implementing Harness (a concrete harness belongs in a harness plugin module): $hits")
    }

    @Test fun `built-in harness id literals appear only at allow-listed core sites`() {
        val id = Regex(""""(claude|opencode|pi)"""")
        val found = files().filter { f -> code(f).any { id.containsMatchIn(it.substringBefore(" // ")) } }.map { it.name }.toSet()
        val unexpected = found - ALLOWED.keys
        val stale = ALLOWED.keys - found
        assertTrue(unexpected.isEmpty(), "harness id literal in core (move it to the harness plugin or add an allow-list entry with a reason): $unexpected")
        assertTrue(stale.isEmpty(), "allow-list entry no longer needed, remove it: $stale")
        assertTrue(ALLOWED.values.all { it.length >= 12 }, "every allow-list entry needs a real reason")
    }

    companion object {
        val ALLOWED = mapOf(
            "CliTools.kt" to "executable resolution table: tool id -> binary names and install dirs; data about binaries on disk, not a harness class",
            "Limits.kt" to "provider quota windows keyed by provider id (usage limits are read from the vendor's own endpoint)",
            "ProviderFactory.kt" to "INFANTRY_PROVIDER alias table and MODULE_SERVED set: maps user spellings to module-served ids; data, no adapter code",
            "CoreHarnessContributions.kt" to "ids of harness modules whose contributions core knows as data (module-served ids)",
            "SessionStatsService.kt" to "stats provider-tag constants for rows already stored under those ids",
            "DescriptorOutput.kt" to "descriptor output.format enum wire names (claude/pi stream formats); format names, not adapters",
            "OneShotAdapter.kt" to "one retry guard keyed on the provider id for an empty opencode one-shot; remains until the adapter's retry moves behind the SPI",
            "WatchHooks.kt" to "native background-task harness set and rewrite request tag for the watch mechanism; moves with the watch hook",
            "PiToolBridgeRegistration.kt" to "experiments package registers launch extras for an id; blocked on a launch-extras SPI (see DaemonCoreGuardTest PLUGIN_LATER)",
        )
    }
}
