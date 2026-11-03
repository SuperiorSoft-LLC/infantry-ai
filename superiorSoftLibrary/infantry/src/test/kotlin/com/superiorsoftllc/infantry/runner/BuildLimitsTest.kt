// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ticket 66153313: the build limits a worker session gets, and the priority the runner enforces on its process tree. */
class BuildLimitsTest {
    @Test fun `priority names parse loosely and nothing else does`() {
        assertEquals(BuildPriority.BELOW_NORMAL, BuildPriority.parse("BelowNormal"))
        assertEquals(BuildPriority.BELOW_NORMAL, BuildPriority.parse(" below_normal "))
        assertEquals(BuildPriority.BELOW_NORMAL, BuildPriority.parse("below-normal"))
        assertEquals(BuildPriority.IDLE, BuildPriority.parse("idle"))
        assertEquals(BuildPriority.NORMAL, BuildPriority.parse("NORMAL"))
        assertNull(BuildPriority.parse("high"))
        assertNull(BuildPriority.parse(null))
        assertEquals(BuildPriority.BELOW_NORMAL, BuildPriority.DEFAULT)
    }

    @Test fun `default worker cap leaves two cores, never below one`() {
        assertEquals(14, BuildLimits.defaultMaxWorkers(16))
        assertEquals(1, BuildLimits.defaultMaxWorkers(2))
        assertEquals(1, BuildLimits.defaultMaxWorkers(1))
    }

    @Test fun `session env caps Gradle, keeps Kotlin in-process and appends to the inherited GRADLE_OPTS`() {
        val env = BuildLimits(BuildPriority.BELOW_NORMAL, maxWorkers = 6, maxParallelForks = 2).sessionEnv(mapOf("GRADLE_OPTS" to "-Xmx512m")::get)
        assertEquals("belownormal", env[BuildLimits.PRIORITY_ENV])
        assertEquals("6", env[BuildLimits.MAX_WORKERS_ENV])
        assertEquals("2", env[BuildLimits.MAX_FORKS_ENV])
        assertEquals(
            "-Xmx512m -Dorg.gradle.workers.max=6 -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process",
            env["GRADLE_OPTS"],
        )
    }

    @Test fun `STACK-NEUTRAL-6 - this repo's fork properties come from the project additions merged over the bundled profile`() {
        val text = BuildLimitsTest::class.java.getResourceAsStream("/watch/repo-profile-additions.json")!!.readBytes().toString(Charsets.UTF_8)
        val add = ToolProfiles.parse(text).single()
        val base = ToolProfiles.DEFAULTS.first { it.name == add.name }
        val merged = ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(base.copy(env = base.env + add.env)))
        val env = BuildLimits(BuildPriority.BELOW_NORMAL, maxWorkers = 6, maxParallelForks = 2).sessionEnv(mapOf("GRADLE_OPTS" to "-Xmx512m")::get, merged)
        assertEquals(
            "-Xmx512m -Dorg.gradle.workers.max=6 -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process " +
                "-Dorg.gradle.project.infantry.testForks=2 -Dorg.gradle.project.infantry.uiTestForks=2",
            env["GRADLE_OPTS"],
        )
        assertFalse(base.env.any { "testForks" in it.value }, "the bundled profile stays generic")
    }

    @Test fun `zero means unlimited - no cap is passed`() {
        val env = BuildLimits(BuildPriority.IDLE, maxWorkers = 0, maxParallelForks = 0).sessionEnv()
        assertEquals("idle", env[BuildLimits.PRIORITY_ENV])
        assertFalse(BuildLimits.MAX_WORKERS_ENV in env)
        assertFalse(BuildLimits.MAX_FORKS_ENV in env)
        val opts = env.getValue("GRADLE_OPTS")
        assertFalse("workers.max" in opts)
        assertTrue("execution.strategy=in-process" in opts)
    }

    @Test fun `STACK-NEUTRAL-1 - with no gradle profile no GRADLE_OPTS is set, only the generic caps`() {
        val env = BuildLimits(BuildPriority.BELOW_NORMAL, maxWorkers = 6, maxParallelForks = 2).sessionEnv(profiles = emptyList())
        assertEquals(
            mapOf(BuildLimits.PRIORITY_ENV to "belownormal", BuildLimits.MAX_WORKERS_ENV to "6", BuildLimits.MAX_FORKS_ENV to "2"),
            env,
        )
    }

    @Test fun `STACK-NEUTRAL-1 - a non-gradle profile's env templates get the caps, joined and appended to the inherited value`() {
        val p = ToolProfile(
            name = "acme", env = listOf(
                EnvTemplate("ACME_FLAGS", "--jobs=${'$'}{maxWorkers}", onlyIf = EnvTemplate.MAX_WORKERS),
                EnvTemplate("ACME_FLAGS", "--test-procs=${'$'}{maxParallelForks}", onlyIf = EnvTemplate.MAX_PARALLEL_FORKS),
                EnvTemplate("ACME_MODE", "low", inherit = false),
            ),
        )
        val env = BuildLimits(BuildPriority.IDLE, maxWorkers = 4, maxParallelForks = 0)
            .sessionEnv(mapOf("ACME_FLAGS" to "-v", "ACME_MODE" to "high")::get, listOf(p))
        assertEquals("-v --jobs=4", env["ACME_FLAGS"], "forks=0 leaves its template out")
        assertEquals("low", env["ACME_MODE"], "inherit=false replaces")
        assertFalse("GRADLE_OPTS" in env)
    }

    @Test fun `the runner reads the priority from the spec env only, and normal means untouched`() {
        assertEquals(BuildPriority.BELOW_NORMAL, ProcessPriority.requested(ProcessSpec(listOf("x"), env = mapOf(BuildLimits.PRIORITY_ENV to "belownormal"))))
        assertNull(ProcessPriority.requested(ProcessSpec(listOf("x"), env = mapOf(BuildLimits.PRIORITY_ENV to "normal"))))
        assertNull(ProcessPriority.requested(ProcessSpec(listOf("x"))), "a spec without the env (the Run tab, a probe) is never lowered")
    }

    @Test fun `POSIX runs the argv under nice, Windows and normal leave it alone`() {
        val argv = listOf("pi", "--mode", "rpc")
        assertEquals(listOf("/usr/bin/nice", "-n", "10") + argv, ProcessPriority.wrap(argv, BuildPriority.BELOW_NORMAL, windows = false, nice = "/usr/bin/nice"))
        assertEquals(listOf("/usr/bin/nice", "-n", "19") + argv, ProcessPriority.wrap(argv, BuildPriority.IDLE, windows = false, nice = "/usr/bin/nice"))
        assertEquals(argv, ProcessPriority.wrap(argv, BuildPriority.BELOW_NORMAL, windows = true, nice = "/usr/bin/nice"))
        assertEquals(argv, ProcessPriority.wrap(argv, BuildPriority.NORMAL, windows = false, nice = "/usr/bin/nice"))
        assertEquals(argv, ProcessPriority.wrap(argv, null, windows = false, nice = "/usr/bin/nice"))
        assertEquals(argv, ProcessPriority.wrap(argv, BuildPriority.BELOW_NORMAL, windows = false, nice = null), "no nice binary: run anyway")
    }

    /**
     * The real thing on Windows: a session root started through the piped runner with the priority env runs BelowNormal,
     * a grandchild it starts inherits it, and the Job object keeps a child from raising itself back to Normal.
     */
    @Test fun `on Windows the whole tree under a lowered session runs below normal`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        val exited = CountDownLatch(1)
        val listener = object : ProcessListener {
            override fun onStdout(line: String) {}
            override fun onStderr(line: String) {}
            override fun onExit(exitCode: Int) = exited.countDown()
        }
        val spec = ProcessSpec(
            // The second ping starts a second after launch, after the job is set up, and asks for NORMAL priority.
            listOf("cmd", "/c", "ping -n 2 127.0.0.1 >NUL & start /b /wait /normal ping -n 30 127.0.0.1 >NUL"),
            env = mapOf(BuildLimits.PRIORITY_ENV to "belownormal"),
        )
        val proc = PipedProcessRunner().start(spec, listener)
        try {
            val root = assertNotNull(proc.pid)
            Thread.sleep(2_500)
            // Pick the ping by name: when the test JVM has no console (a Gradle daemon, a worker session), Windows also gives
            // cmd a conhost.exe child. conhost is an OS console host started with cmd, before the runner lowers it and binds
            // the job, so it may run Normal; it is not something the session started and is not what this test checks.
            val child = waitFor {
                ProcessHandle.of(root).orElse(null)?.children()
                    ?.filter { it.info().command().orElse("").substringAfterLast('\\').equals("PING.EXE", ignoreCase = true) }
                    ?.findFirst()?.orElse(null)
            }
            assertNotNull(child, "cmd started its ping grandchild")
            assertEquals(BELOW_NORMAL, ProcessPriority.windowsPriorityClass(root), "the session root is lowered")
            assertEquals(BELOW_NORMAL, ProcessPriority.windowsPriorityClass(child.pid()), "a grandchild that asked for normal still runs below normal")
            // `start /normal` makes CreateProcess ask for NORMAL_PRIORITY_CLASS: the job's priority-class limit keeps the
            // grandchild below normal anyway, so a tool cannot opt its build back out.
        } finally {
            proc.killTree()
            exited.await(10, TimeUnit.SECONDS)
        }
    }

    @Test fun `on Windows a spec without the env is not lowered`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        val listener = object : ProcessListener {
            override fun onStdout(line: String) {}
            override fun onStderr(line: String) {}
            override fun onExit(exitCode: Int) {}
        }
        val proc = PipedProcessRunner().start(ProcessSpec(listOf("cmd", "/c", "ping -n 5 127.0.0.1 >NUL")), listener)
        try {
            // The test JVM may itself run lowered (a niced Gradle): the child simply keeps its parent's class.
            assertEquals(ProcessPriority.windowsPriorityClass(ProcessHandle.current().pid()), ProcessPriority.windowsPriorityClass(assertNotNull(proc.pid)))
        } finally {
            proc.killTree()
        }
    }

    private fun <T> waitFor(read: () -> T?): T? {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            read()?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private companion object {
        const val BELOW_NORMAL = 0x4000
    }
}
