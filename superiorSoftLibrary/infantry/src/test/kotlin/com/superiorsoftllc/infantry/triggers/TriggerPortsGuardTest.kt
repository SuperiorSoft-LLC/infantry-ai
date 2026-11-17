// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.triggers

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TRIG-1. Rule: the trigger service (Infantry_Triggers' daemon half since TRIG-2c) imports only `kotlin.`, `kotlinx.`, `java.`, `javax.`, the protocol
 * (`...infantry.protocol.`) and the daemon API (`...infantry.daemon.api.`). Trigger code reaches the board and sessions
 * only through those ports.
 * Why: a direct import of a daemon or board class couples triggers to the implementation and breaks the port seam.
 * Fix a failure: the listed `file: import` line reaches past a port. Route the call through a port type in
 * `daemon.api` (or `protocol`) and implement that port in the daemon. Do not add a package to `allowed` to pass.
 */
class TriggerPortsGuardTest {
    private val dir = listOf(
        // TRIG-2c: the trigger service moved into Infantry_Triggers' daemon half; the rule follows the code there.
        "../../plugins/official/Infantry_Triggers/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/triggers/daemon",
        "plugins/official/Infantry_Triggers/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/triggers/daemon",
    ).map(::File).first { it.isDirectory }

    @Test
    fun `triggers imports nothing outside protocol and daemon api`() {
        val allowed = listOf("com.superiorsoftllc.infantry.protocol.", "com.superiorsoftllc.infantry.daemon.api.", "kotlin.", "kotlinx.", "java.", "javax.")
        val bad = dir.listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            f.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
                .filter { imp -> allowed.none { imp.startsWith(it) } }.map { imp -> "${f.name}: $imp" }
        }
        assertEquals(emptyList<String>(), bad, "triggers import past the ports (fix: go through a daemon.api port type)")
    }
}
