package com.superiorsoftllc.agenthq.triggers

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** TRIG-1: the triggers package compiles against the protocol and the daemon API only. */
class TriggerPortsGuardTest {
    private val dir = listOf(
        "src/main/kotlin/com/superiorsoftllc/agenthq/triggers",
        "superiorSoftLibrary/agentHq/src/main/kotlin/com/superiorsoftllc/agenthq/triggers",
    ).map(::File).first { it.isDirectory }

    @Test
    fun `triggers imports nothing outside protocol and daemon api`() {
        val allowed = listOf("com.superiorsoftllc.agenthq.protocol.", "com.superiorsoftllc.agenthq.daemon.api.", "kotlin.", "kotlinx.", "java.", "javax.")
        val bad = dir.listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            f.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
                .filter { imp -> allowed.none { imp.startsWith(it) } }.map { imp -> "${f.name}: $imp" }
        }
        assertEquals(emptyList<String>(), bad)
    }
}
