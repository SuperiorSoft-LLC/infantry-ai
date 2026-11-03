package com.superiorsoftllc.infantry.daemon

import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonLogTest {
    @Test fun rotatesAndKeepsAtMostMaxFiles() {
        val state = Files.createTempDirectory("dlog").toFile()
        val log = DaemonLog.install(state, maxFiles = 3, maxBytes = 200)!!
        try { repeat(40) { System.err.println("line $it ${"x".repeat(30)}") } } finally { log.close() }
        val files = DaemonLog.dir(state).listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("infantry.1.log", "infantry.2.log", "infantry.log"), files)
    }

    @Test fun stallProbeOutputReachesTheFile() {
        val state = Files.createTempDirectory("dlog").toFile()
        val log = DaemonLog.install(state)!!
        try {
            StallProbe(URI("http://127.0.0.1:1/health"), ask = { _, _ -> false }, dump = { "  busy-thread" }, log = { System.err.println(it) }).tick()
        } finally { log.close() }
        val text = DaemonLog.file(DaemonLog.dir(state), 0).readText()
        assertTrue("stall probe: /health did not answer" in text && "busy-thread" in text, text)
    }
}
