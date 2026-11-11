// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** QUIT-ORPHAN-1: a child (and its descendant) of a daemon that ends, even by a hard kill, ends with it. */
class ChildReaperTest {
    @Test fun `a child and its grandchild die when the owning JVM is killed`() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        val java = File(System.getProperty("java.home"), "bin/java").path
        val owner = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.superiorsoftllc.infantry.runner.ChildReaperOwner")
            .redirectErrorStream(true).start()
        val pids = owner.inputStream.bufferedReader().readLine().split(",").map { it.toLong() }
        assertEquals(2, pids.size)
        val spawned = pids.map { ProcessHandle.of(it).orElse(null) }
        spawned.forEach { assertNotNull(it, "child alive before the kill") }
        owner.destroyForcibly().waitFor()
        spawned.forEach { h -> repeat(50) { if (h.isAlive) Thread.sleep(100) }; assertFalse(h.isAlive, "pid ${h.pid()} survived its owner") }
    }

    @Test fun `arming reports no problem`() {
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) assertNull(ChildReaper.problem)
    }
}

/** Arms the reaper, starts `cmd /c ping` (cmd = child, ping = grandchild), prints both pids, then waits to be killed. */
object ChildReaperOwner {
    @JvmStatic fun main(args: Array<String>) {
        ChildReaper.ensure()
        val cmd = ProcessBuilder("cmd", "/c", "ping -n 120 127.0.0.1 >NUL").start()
        Thread.sleep(1500)
        val ping = cmd.descendants().findFirst().map { it.pid() }.orElse(-1L)
        println("${cmd.pid()},$ping")
        System.out.flush()
        Thread.sleep(120_000)
    }
}
