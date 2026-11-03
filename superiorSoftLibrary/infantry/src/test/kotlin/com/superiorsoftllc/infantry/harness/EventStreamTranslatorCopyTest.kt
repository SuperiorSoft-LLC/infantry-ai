// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HARNESS H6c: the "event stream" reader exists twice, because a harness plugin sees only the harness API and the daemon's descriptor harnesses
 * (`output.format` "pi") read the same wire format: here (core types) and in plugins/official/Infantry_Pi (API types). Everything but the
 * package and import lines must be identical, so a fix to one cannot be forgotten in the other.
 */
class EventStreamTranslatorCopyTest {
    private fun body(f: File): String = f.readText().replace("\r\n", "\n").lines()
        .filterNot { it.startsWith("package ") || it.startsWith("import ") }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    @Test fun `the daemon's reader and the pi plugin's copy are the same code`() {
        val core = File(TestDaemonPlugins.repo, "superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/harness/EventStreamTranslator.kt")
        val plugin = File(TestDaemonPlugins.repo, "plugins/official/Infantry_Pi/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/pi/harness/EventStreamTranslator.kt")
        assertTrue(core.isFile && plugin.isFile, "both copies must exist")
        fun strip(s: String) = s.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }.joinToString("\n")
        assertEquals(strip(body(core)), strip(body(plugin)), "the event-stream translators drifted apart")
    }
}
