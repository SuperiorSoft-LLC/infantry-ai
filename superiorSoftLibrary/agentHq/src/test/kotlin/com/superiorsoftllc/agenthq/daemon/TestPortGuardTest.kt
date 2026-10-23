package com.superiorsoftllc.agenthq.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PORT-1: fixed ports per use. 8787 is the owner's daemon (`runDaemon`), 8788 the owner's UI preview
 * (`runRealDaemon`). Tests, gates, QA scenarios and probes bind port 0 (ephemeral), so a test run can never
 * collide with, or be mistaken for, the owner's daemons.
 *
 * This guard scans the test sources of the three AgentHQ modules for a BIND of 8787/8788. A string that only
 * names the port (an expected URL, a Host header) is not a bind and is allowed.
 */
class TestPortGuardTest {

    private val moduleRoot = File(".").absoluteFile.normalize()
    private val testRoots = listOf(
        File(moduleRoot, "src/test"),
        File(moduleRoot, "../agentHqUi/src/test"),
        File(moduleRoot, "../agenthqUiCore/src/jvmTest"),
        File(moduleRoot, "../agenthqUiCore/src/commonTest"),
        File(moduleRoot, "../agenthqUiCore/src/androidUnitTest"),
    )

    @Test fun noTestBindsTheOwnersFixedPorts() {
        assertTrue(File(moduleRoot, "src/test").isDirectory, "run from the agentHq module dir, got $moduleRoot")
        val offenders = testRoots.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts") } }
            .filter { it.name != "TestPortGuardTest.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (bindsFixedPort(line)) "${file.relativeTo(moduleRoot)}:${i + 1}: ${line.trim()}" else null
                }
            }
        assertEquals(emptyList(), offenders, "tests must bind port 0, never 8787/8788 (QUICKSTART.md, Ports)")
    }

    /** The guard itself must recognise the bind shapes it exists to catch, and leave plain URLs alone. */
    @Test fun theGuardCatchesEachBindShape() {
        listOf(
            "DaemonFixture.launch(port = 8787)",
            "AgentHqDaemon.build(port=8788, provider = p)",
            "ServerSocket(8787)",
            "socket.bind(InetSocketAddress(\"127.0.0.1\", 8788))",
            "environment(\"AGENTHQ_PORT\", \"8788\")",
            "put(\"AGENTHQ_PORT\", \"8787\")",
            "embeddedServer(CIO, 8787) {",
        ).forEach { assertTrue(bindsFixedPort(it), it) }
        listOf(
            "assertEquals(\"ws://127.0.0.1:8787/ws\", url)",
            "policy.check(\"/ws\", \"10.0.0.5:8787\", null, null)",
            "DaemonFixture.launch(port = 18787)",
        ).forEach { assertTrue(!bindsFixedPort(it), it) }
    }

    private fun bindsFixedPort(line: String): Boolean = BIND_PATTERNS.any { it.containsMatchIn(line) }

    private companion object {
        const val FIXED = "(?<!\\d)878[78](?!\\d)"
        val BIND_PATTERNS = listOf(
            Regex("\\bport\\s*=\\s*$FIXED"),
            Regex("ServerSocket\\(\\s*$FIXED"),
            Regex("InetSocketAddress\\([^)]*,\\s*$FIXED"),
            Regex("AGENTHQ_PORT[\"=,\\s]+\"?$FIXED"),
            Regex("embeddedServer\\([^)]*,\\s*(port\\s*=\\s*)?$FIXED"),
        )
    }
}
