// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RUN-TAB-1: a run whose started process exits while a grandchild lives on (and holds the run's pipes) is still Stopped:
 * killTree ends the grandchild too. `taskkill /T` alone cannot, the parent link is gone.
 */
class RunStopOrphanTest {
    @Test
    fun killTreeEndsAGrandchildThatOutlivesItsParent() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        val pidFile = Files.createTempFile("grandchild", ".pid").toFile().also { it.delete() }
        val outer = "Start-Process powershell -NoNewWindow -ArgumentList '-NoProfile','-Command','\$PID | Out-File -Encoding ascii ${pidFile.path}; Start-Sleep 120'; exit 0"
        val done = CountDownLatch(1)
        val p = PipedProcessRunner().start(
            ProcessSpec(command = listOf("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", outer)),
            object : ProcessListener {
                override fun onStdout(line: String) {}
                override fun onStderr(line: String) {}
                override fun onExit(exitCode: Int) { done.countDown() }
            },
        )
        val end = System.nanoTime() + 30_000_000_000L
        while (!pidFile.exists() && System.nanoTime() < end) Thread.sleep(100)
        assertTrue(pidFile.exists(), "the grandchild never started")
        Thread.sleep(300)
        val gpid = pidFile.readText().trim().toLong()
        assertTrue(ProcessHandle.of(gpid).map { it.isAlive }.orElse(false), "the grandchild should be alive before the stop")
        p.killTree()
        assertTrue(done.await(15, TimeUnit.SECONDS), "the run did not finish after killTree")
        val dead = System.nanoTime() + 10_000_000_000L
        while (ProcessHandle.of(gpid).map { it.isAlive }.orElse(false) && System.nanoTime() < dead) Thread.sleep(100)
        assertFalse(ProcessHandle.of(gpid).map { it.isAlive }.orElse(false), "the grandchild survived the stop")
    }
}
