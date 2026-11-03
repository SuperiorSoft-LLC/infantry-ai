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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CMD-1: [LocalRuns] refuses a cwd outside the project before anything starts, and stop kills the whole tree. */
class RunsSurfaceTest {
    private val root = Files.createTempDirectory("runs-root").toFile().canonicalFile
    private val sub = File(root, "sub").also { it.mkdirs() }
    private val outside = Files.createTempDirectory("runs-outside").toFile().canonicalFile
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val runs = LocalRuns(root, File(root, "cfg"), { if (it == "p1") root.path else null }, PipedProcessRunner(), null, { null })
    private val noop = object : RunListener { override fun onExit(exitCode: Int) {} }
    private val echo = if (windows) listOf("cmd", "/c", "echo", "hi") else listOf("echo", "hi")

    @Test fun cwdOutsideTheProjectIsRefusedBeforeStart() {
        for (bad in listOf("..", "../x", "..\\x", outside.path, "/etc", "C:\\Windows", "sub/../..")) {
            assertFailsWith<IllegalArgumentException>(bad) { runs.start(RunSpec(echo, cwd = bad, projectId = "p1"), noop) }
        }
        assertFailsWith<IllegalArgumentException> { runs.start(RunSpec(echo, cwd = "missing", projectId = "p1"), noop) }
        assertFailsWith<IllegalArgumentException> { runs.start(RunSpec(echo, projectId = "nope"), noop) }
        assertFailsWith<IllegalArgumentException> { runs.start(RunSpec(emptyList()), noop) }
    }

    @Test fun aCwdInsideRunsAndReportsExit() {
        val lines = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        var code = -1
        runs.start(RunSpec(echo, cwd = "sub", projectId = "p1"), object : RunListener {
            override fun onLine(stream: String, line: String) { lines += line }
            override fun onExit(exitCode: Int) { code = exitCode; done.countDown() }
        })
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(0, code)
        assertTrue(lines.any { it.contains("hi") })
    }

    @Test fun killTreeEndsTheChild() {
        val done = CountDownLatch(1)
        val argv = if (windows) listOf("ping", "-n", "60", "127.0.0.1") else listOf("sleep", "60")
        val h = runs.start(RunSpec(argv), object : RunListener { override fun onExit(exitCode: Int) { done.countDown() } })
        assertTrue(h.isAlive)
        assertFalse(h.terminal)
        h.killTree()
        assertTrue(done.await(20, TimeUnit.SECONDS), "the child ended after killTree")
        assertFalse(h.isAlive)
    }
}
