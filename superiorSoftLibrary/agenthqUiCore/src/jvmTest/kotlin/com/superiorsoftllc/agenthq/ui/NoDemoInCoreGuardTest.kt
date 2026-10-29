package com.superiorsoftllc.agenthq.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEMO-MERGE-1: the demo is a module set (plugins/demo/<Module>), never core. Core (this library) and the official plugins
 * stay deployment-neutral: no code line (comments are skipped) and no module.json may name the demo. Core offers the generic
 * mechanisms the demo plugs into (WritePolicy, `ready` fields, declared notices, block overrides).
 *
 * [baseline] is a ratchet for the SEC-FIX-1 plugin drop-in lock, which predates this ticket and names its switch
 * (AGENTHQ_DEMO_LOCK): a count may only go down. Every other file must have none.
 */
class NoDemoInCoreGuardTest {
    private val demo = Regex("""(?i)\bdemo""")

    private val baseline = mapOf(
        "ConfigDialog.kt" to 1,
        "ModulesSection.kt" to 1,
        "PluginDropIn.kt" to 1,
        "ShellDropIn.kt" to 3,
        "DesktopDropIn.kt" to 5,
        "DesktopPlugins.kt" to 10,
        "JvmEndpointAccess.kt" to 2,
        "JvmPlatformHost.kt" to 1,
    )

    private fun isComment(line: String): Boolean { val t = line.trimStart(); return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }

    @Test
    fun `no core or official plugin code names the demo`() {
        val core = listOf("src/commonMain", "src/jvmMain", "src/androidMain").map(::File).filter { it.isDirectory }
        val official = File("../../plugins/official")
        assertTrue(core.isNotEmpty() && official.isDirectory, "sources not found from ${File(".").absolutePath}")
        fun scan(root: File) = root.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && (it.extension == "kt" || it.name == "module.json") }.toList()
        val coreFiles = core.flatMap(::scan).toSet()
        val files = coreFiles + scan(official)
        assertTrue(files.size > 100, "too few files scanned (${files.size})")
        val offenders = files.mapNotNull { f ->
            val hits = f.readLines().withIndex().filter { !isComment(it.value) && demo.containsMatchIn(it.value) }
            val allowed = if (f in coreFiles) baseline[f.name] ?: 0 else 0
            if (hits.size <= allowed) null
            else "${f.path} (${hits.size}, allowed $allowed):\n" + hits.joinToString("\n") { "  ${it.index + 1}: ${it.value.trim()}" }
        }
        if (offenders.isNotEmpty()) fail("the demo belongs in plugins/demo/<Module>, not core or an official plugin:\n" + offenders.joinToString("\n"))
    }
}
