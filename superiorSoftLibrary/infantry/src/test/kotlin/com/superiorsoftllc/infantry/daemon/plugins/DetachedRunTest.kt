// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.runs.RunListener
import com.superiorsoftllc.infantry.daemon.api.runs.RunSpec
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RUN-TAB-1: a detached run launches a process that outlives the run (hidden, harmless: it only sleeps; checked and killed by
 * PID), reports "launched detached (PID n)" and exits 0 at once; a normal run is unchanged (tracked, Stop kills it).
 */
class DetachedRunTest {
    private val root = Files.createTempDirectory("detached-root").toFile().canonicalFile
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val runs = LocalRuns(root, File(root, "cfg"), { root.path }, PipedProcessRunner(), null, { null })
    private val sleeper = if (windows) listOf("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", "Start-Sleep 120") else listOf("sleep", "120")

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test fun aDetachedRunLaunchesAndEndsAtOnceWhileTheProcessLivesOn() {
        val lines = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        var code = -1
        val h = runs.start(RunSpec(sleeper, detached = true), object : RunListener {
            override fun onLine(stream: String, line: String) { lines += line }
            override fun onExit(exitCode: Int) { code = exitCode; done.countDown() }
        })
        val pid = assertNotNull(h.pid)
        try {
            assertTrue(done.await(20, TimeUnit.SECONDS), "a detached run must end at once")
            assertEquals(0, code)
            assertEquals(listOf("launched detached (PID $pid)"), lines.toList())
            Thread.sleep(500)
            assertTrue(alive(pid), "the launched process must outlive the run")
        } finally {
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
        val end = System.nanoTime() + 10_000_000_000L
        while (alive(pid) && System.nanoTime() < end) Thread.sleep(100)
        assertFalse(alive(pid), "killed by PID")
    }

    @Test fun aNormalRunStaysTrackedAndStopKillsIt() {
        val done = CountDownLatch(1)
        val h = runs.start(RunSpec(sleeper), object : RunListener { override fun onExit(exitCode: Int) { done.countDown() } })
        val pid = assertNotNull(h.pid)
        Thread.sleep(500)
        assertTrue(h.isAlive && alive(pid), "a normal run is tracked and alive")
        h.killTree()
        assertTrue(done.await(15, TimeUnit.SECONDS), "Stop must end a normal run")
        assertFalse(alive(pid))
    }
}
