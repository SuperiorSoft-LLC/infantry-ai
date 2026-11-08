// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthJournalTest {
    private fun dir() = java.nio.file.Files.createTempDirectory("health-test").toFile()

    @Test fun firstStartHasNoCauseAndACleanStopIsReportedAtTheNextStart() {
        val d = dir()
        val j = HealthJournal(d, { 100 }, pidAlive = { false })
        assertNull(j.begin(10, 18901, "b1").lastRestartCause)
        j.end("update")
        val second = j.begin(11, 18901, "b2")
        assertEquals("update", second.lastRestartCause)
        assertEquals(listOf("daemon_start", "daemon_stop", "daemon_start"), j.tail(10).map { it.kind })
    }

    @Test fun aRunningRecordWithADeadPidIsACrash() {
        val d = dir()
        HealthJournal(d, { 100 }, pidAlive = { false }).begin(10, 18901, "b1")
        val s = HealthJournal(d, { 100 }, pidAlive = { false }).begin(11, 18901, "b1")
        assertTrue(s.lastRestartCause!!.startsWith("crash"), s.lastRestartCause)
    }

    @Test fun theEventLogIsBoundedByTheNamedLimit() {
        val j = HealthJournal(dir(), { 10 })
        repeat(100) { j.record("mcp_disconnect", detail = "n$it") }
        val kept = j.tail(1000)
        assertTrue(kept.size <= 30, "kept ${kept.size}")
        assertEquals("n99", kept.last().detail)
    }

    @Test fun offlineReportSaysDownWhenTheStopWasCleanOrThePidIsGone() {
        val d = dir()
        val j = HealthJournal(HealthJournal.dirIn(d), { 100 }, pidAlive = { false })
        j.begin(10, 18901, "b1")
        assertFalse(OfflineHealth.report(d, { false }, eventTail = 5).daemon.up)
        assertTrue(OfflineHealth.report(d, { true }, eventTail = 5).daemon.up)
        j.end("clean stop")
        val r = OfflineHealth.report(d, { true }, eventTail = 5)
        assertFalse(r.daemon.up)
        assertEquals("clean stop: clean stop", r.daemon.lastRestartCause)
    }

    @Test fun aDroppedMcpBridgeIsLoggedWithItsCauseAndCountsReconnects() {
        val j = HealthJournal(dir(), { 100 })
        val t = McpBridgeTracker(j)
        t.connected("s1"); t.disconnected("s1", "socket closed"); t.connected("s1")
        assertEquals(1, t.rows().single().reconnects)
        assertEquals(listOf("mcp_connect", "mcp_disconnect", "mcp_connect"), j.tail(10).map { it.kind })
        assertEquals("socket closed", j.tail(10)[1].cause)
    }

    @Test fun textNamesTheOwnerPortsAndTheReportRoundTripsAsJson() {
        val d = dir()
        val r = OfflineHealth.report(d, { false }, eventTail = 5)
        val text = HealthText.render(r)
        assertTrue("8787" in text && "18900-18999" in text && "DOWN" in text, text)
        assertEquals(r.environment.ownerPorts, HealthReport.parse(r.toJson()).environment.ownerPorts)
        assertTrue(File(d.path).exists())
    }
}
