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

    @Test fun `every official module json hook kind validates`() {
        val bad = mutableListOf<String>()
        File(root(), "plugins/official").listFiles { f -> f.isDirectory }!!.forEach { dir ->
            val json = File(dir, "module.json").takeIf { it.isFile }?.readText() ?: return@forEach
            Regex(""""hooks"\s*:\s*\[([^\]]*)]""").findAll(json).forEach { m ->
                Regex(""""([A-Za-z]+)"""").findAll(m.groupValues[1]).forEach { k ->
                    if (k.groupValues[1] !in DaemonPluginHost.HOOK_KINDS) bad += "${dir.name}: ${k.groupValues[1]}"
                }
            }
        }
        assertTrue(bad.isEmpty(), "refused hook kinds: $bad")
    }
}
