// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PROC-WATCH-CPU-1: one OS process-table read serves every session and both watch threads. */
class ProcessSnapshotsTest {
    private var now = 0L
    private var table: Map<Long, Long?> = emptyMap()
    private val starts = HashMap<Long, Long>()
    private val describes = ArrayList<Long>()
    private val saved = Triple(ProcessSnapshots.readTable, ProcessSnapshots.describe, ProcessSnapshots.clock)
    private val savedTtl = ProcessSnapshots.ttlMs

    @BeforeTest
    fun fake() {
        ProcessSnapshots.readTable = { table }
        ProcessSnapshots.describe = { pid -> describes += pid; starts[pid]?.let { ProcInfo(pid, null, "cmd$pid", it) } }
        ProcessSnapshots.clock = { now }
        ProcessSnapshots.ttlMs = 1500
        ProcessSnapshots.reset()
    }

    @AfterTest
    fun restore() {
        ProcessSnapshots.readTable = saved.first
        ProcessSnapshots.describe = saved.second
        ProcessSnapshots.clock = saved.third
        ProcessSnapshots.ttlMs = savedTtl
        ProcessSnapshots.reset()
    }

    private fun proc(pid: Long, parent: Long?, start: Long) { table = table + (pid to parent); starts[pid] = start }

    @Test
    fun `every session's scans in one tick share one table read, and details are read once per process`() {
        for (s in 0 until 8) { val root = 100L + s * 10; proc(root, 1, 10); proc(root + 1, root, 20); proc(root + 2, root + 1, 30) }
        val reads0 = ProcessSnapshots.tableReads
        repeat(3) { tick ->
            now = tick * 2000L
            for (s in 0 until 8) {
                val root = 100L + s * 10
                assertEquals(listOf(root + 1, root + 2), OsProcessSource.descendants(root).map { it.pid })
                OsProcessSource.descendants(root)
                OsProcessSource.info(root)
            }
        }
        assertEquals(3, ProcessSnapshots.tableReads - reads0, "one table read per tick, not one per call")
        assertEquals(24, describes.size, "each of the 24 processes is described once, not on every tick")
    }

    @Test
    fun `ttl 0 reads the OS on every call`() {
        proc(5, 1, 1)
        ProcessSnapshots.ttlMs = 0
        val reads0 = ProcessSnapshots.tableReads
        repeat(4) { OsProcessSource.descendants(5) }
        assertEquals(4, ProcessSnapshots.tableReads - reads0)
    }

    @Test
    fun `a gone process drops out at the next table and a new child shows up`() {
        proc(5, 1, 1); proc(6, 5, 2)
        assertEquals(listOf(6L), OsProcessSource.descendants(5).map { it.pid })
        table = table - 6L + (7L to 5L); starts[7] = 3
        now += 2000
        assertEquals(listOf(7L), OsProcessSource.descendants(5).map { it.pid })
        assertNull(OsProcessSource.info(6))
    }

    @Test
    fun `a child older than its parent is not its child (recycled parent pid), nor anything under it`() {
        proc(5, 1, 100); proc(6, 5, 50); proc(7, 6, 60); proc(8, 5, 120)
        assertEquals(listOf(8L), OsProcessSource.descendants(5).map { it.pid })
    }

    @Test
    fun `a recycled pid under another parent is described again`() {
        proc(5, 1, 1); proc(6, 5, 2)
        assertEquals("cmd6", OsProcessSource.descendants(5).single().command)
        table = table + (6L to 9L) + (9L to 1L); starts[9] = 3; starts[6] = 4
        now += 2000
        assertEquals(emptyList(), OsProcessSource.descendants(5))
        assertEquals(4L, OsProcessSource.descendants(9).single().startedAt)
    }

    @Test
    fun `all lists every live process with its parent, info names none`() {
        proc(5, 1, 1); proc(6, 5, 2)
        assertEquals(setOf(5L to 1L, 6L to 5L), OsProcessSource.all().map { it.pid to it.parentPid }.toSet())
        assertNull(OsProcessSource.info(6)!!.parentPid)
    }
}
