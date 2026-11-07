// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ATTN-FIX-1. Rule: the hook kinds the daemon host accepts (`DaemonPluginHost.HOOK_KINDS`) equal the kinds it declares
 * with `declare("<kind>")` in `DaemonPluginHost.kt`, and every hook kind in an official `module.json` is accepted.
 * Why: a kind the host dispatches but does not accept is silently dropped; a declared-but-unlisted kind is refused at load.
 * Fix a failure: add or remove the kind in `HOOK_KINDS` so it matches the `declare(...)` calls, and fix the
 * `module.json` named in the message (rename the kind to one the host declares). Do not weaken the regex.
 */
class HookKindsGuardTest {
    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "plugins/official").isDirectory) d = d.parentFile
        return requireNotNull(d) { "plugins/official not found" }
    }

    @Test fun `every hook kind the host declares is accepted`() {
        val src = File(root(), "superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/DaemonPluginHost.kt").readText()
        val declared = Regex("""declare\("([A-Za-z]+)"\)""").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(declared.isNotEmpty())
        assertEquals(declared, DaemonPluginHost.HOOK_KINDS, "HOOK_KINDS must equal the declare(...) kinds in DaemonPluginHost.kt (fix: edit HOOK_KINDS to match)")
    }

    // CI-TESTS-3: a module.json has two "hooks" arrays with different vocabularies. The old regex read both against the
    // daemon list, so the harness half (added by H7b-2, after this guard) always looked refused. Each half is now checked
    // against the list its own loader enforces: daemon.hooks -> DaemonPluginHost.HOOK_KINDS, harness.hooks -> ModuleHarnessHost.HOOK_KINDS.
    @Test fun `every official module json hook kind validates`() {
        val bad = mutableListOf<String>()
        fun kinds(half: kotlinx.serialization.json.JsonElement?): List<String> =
            (half as? kotlinx.serialization.json.JsonObject)?.get("hooks")?.let { h -> (h as? kotlinx.serialization.json.JsonArray)?.map { it.toString().trim('"') } }.orEmpty()
        File(root(), "plugins/official").listFiles { f -> f.isDirectory }!!.forEach { dir ->
            val json = File(dir, "module.json").takeIf { it.isFile }?.readText() ?: return@forEach
            val top = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            kinds(top["daemon"]).forEach { if (it !in DaemonPluginHost.HOOK_KINDS) bad += "${dir.name} daemon: $it" }
            kinds(top["harness"]).forEach { if (it !in com.superiorsoftllc.infantry.harness.ModuleHarnessHost.HOOK_KINDS) bad += "${dir.name} harness: $it" }
        }
        assertTrue(bad.isEmpty(), "refused hook kinds: $bad")
    }
}
