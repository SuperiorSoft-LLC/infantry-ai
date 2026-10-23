package com.superiorsoftllc.agenthq.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundWorkTest {
    private val root = 100L
    private fun source(vararg p: ProcInfo) = object : ProcessSource {
        override fun descendants(rootPid: Long) = p.toList()
    }
    private fun record(work: BackgroundWork? = null) = SessionRecord(
        id = "s", provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
        state = SessionState.IDLE, backgroundWork = work,
    )

    @Test fun `no descendants means no background work`() {
        assertNull(BackgroundWorkScanner.scan(source(), root, 1_000))
    }

    @Test fun `launch helpers and console hosts are ignored with their subtree`() {
        val helper = ProcInfo(201, root, "node mcp-server.js", 2_000)
        val child = ProcInfo(202, 201, "python worker.py", 90_000)
        val conhost = ProcInfo(203, root, "conhost.exe 0x4", 90_000)
        assertNull(BackgroundWorkScanner.scan(source(helper, child, conhost), root, 1_000))
    }

    @Test fun `a later command is background work with its label and start`() {
        val gradle = ProcInfo(300, root, "gradlew agentHqUi:test", 60_000)
        val work = assertNotNull(BackgroundWorkScanner.scan(source(gradle, ProcInfo(301, 300, "java -cp x", 61_000)), root, 1_000))
        assertEquals(300, work.pid)
        assertEquals(60_000, work.startedAt)
        assertEquals(2, work.count)
        assertTrue(work.label.startsWith("gradlew agentHqUi:test"))
    }

    @Test fun `background work is running, not turn-busy, and reads BUSY on the wire`() {
        val idle = record()
        assertFalse(idle.isBusy())
        val busy = record(BackgroundWork(300, "sleep 60", 5, 1))
        assertFalse(idle.isRunning())
        assertTrue(busy.isRunning())
        // input is never blocked by background work alone: no queueing, mail delivers, the session can be woken
        assertFalse(busy.isBusy())
        assertTrue(record(BackgroundWork(300, "sleep 60", 5, 1)).copy(providerSessionId = "p").isAvailable())
        assertTrue(busy.copy(state = SessionState.BUSY).isBusy())
        val json = Json.parseToJsonElement(Json.encodeToString(SessionRecordSerializer, busy)).jsonObject
        assertEquals("BUSY", json.getValue("state").jsonPrimitive.content)
        val idleJson = Json.parseToJsonElement(Json.encodeToString(SessionRecordSerializer, idle)).jsonObject
        assertEquals("IDLE", idleJson.getValue("state").jsonPrimitive.content)
    }
}
