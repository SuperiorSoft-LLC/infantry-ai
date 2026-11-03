package com.superiorsoftllc.infantry.daemon

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Ticket 3b30398d: every daemon a test JVM boots used to leave its `infantry-process-watch` scheduler behind, each one
 * walking the whole OS process table every 10 s; a full suite piled up hundreds and starved the fork of CPU.
 * Starting and stopping 20 real daemons in this JVM must leave none of the threads a daemon owns running.
 */
class DaemonThreadLeakTest {

    @Test(timeout = 60_000)
    fun twentyDaemonsStartedAndStoppedLeaveNoDaemonThreadsBehind() {
        val before = daemonThreads()
        repeat(20) {
            val fixture = DaemonFixture.launch()
            try {
                fixture.health()
            } finally {
                fixture.stop()
                fixture.deleteState()
            }
        }
        // Executor threads end asynchronously after shutdownNow; give them a moment, then compare.
        val deadline = System.currentTimeMillis() + 10_000
        var after = daemonThreads()
        while (System.currentTimeMillis() < deadline && grown(before, after).isNotEmpty()) {
            Thread.sleep(100)
            after = daemonThreads()
        }
        println("[DaemonThreadLeakTest] daemon threads before=$before after=$after")
        assertEquals(0, after["infantry-process-watch"] ?: 0, "process-watch threads left running: $after")
        assertEquals(emptyMap(), grown(before, after), "daemon-owned threads left running after 20 daemons stopped (before=$before)")
    }

    private fun grown(before: Map<String, Int>, after: Map<String, Int>): Map<String, Int> =
        after.filter { (name, n) -> n > (before[name] ?: 0) }

    /** Live threads of the daemon's own executors, by name (pool-numbered names folded into one). */
    private fun daemonThreads(): Map<String, Int> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(PREFIX, ignoreCase = true) }
            .groupingBy { it.name.replace(Regex("""-\d+$"""), "") }.eachCount().toSortedMap()

    private companion object {
        const val PREFIX = "infantry-"
    }
}
