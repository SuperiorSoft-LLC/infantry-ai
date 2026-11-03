package com.superiorsoftllc.infantry.daemon.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ATTN-FIX-1: the accepted hook kinds cannot drift from what the host dispatches or what official modules declare. */
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
        assertEquals(declared, DaemonPluginHost.HOOK_KINDS)
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
