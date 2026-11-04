// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [FakeOs] with per-session OS containers (Job Objects on Windows) and a record of raised alerts. */
private class ContainerOs : FakeOs() {
    val members = HashMap<String, MutableSet<Long>>()
    val alerts = mutableListOf<Pair<String, String>>()
    override fun sessionMembers(sessionId: String) = members[sessionId].orEmpty().mapNotNull { procs[it] }
    override val containment: String get() = "job object"
    override fun raiseAlert(outcome: String, detail: String) { alerts += outcome to detail }
}

/** PROC-LEAK-1 (ticket 55312934): what a session's shell starts is the session's, reaped at its end; kinds, graceful stop, stuck clients. */
class ProcessLeakTest {
    private var now = 10_000_000L
    private val os = ContainerOs()
    private val sessions = LinkedHashMap<String, SessionRecord>()
    private val harness = 900_100L
    private val shell = 900_201L
    private val detached = 900_202L
    private val daemon = 900_203L
    private val server = 900_204L
    private val client = 900_205L

    init {
        os.sessionList = { sessions.values.map { it.watched() } }
        // The gradle profile with its graceful stop (as tool-profiles.json ships it), plus a stuck-client profile.
        os.profiles = DEFAULTS.map { if (it.name == "gradle") it.copy(name = "gradle-stop", stop = listOf("gradlew", "--stop")) else it } +
            ProcessToolProfile("infantry-cli", stuck = listOf("ClientMainKt"))
    }

    private fun session(id: String, state: SessionState = SessionState.BUSY, worktree: String? = null) {
        sessions[id] = SessionRecord(
            id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = now, state = state,
            keptProcess = KeptProcess(harness, os.procs[harness]?.startedAt, "claude"), lastOutputAt = now, worktreePath = worktree,
        )
    }

    private val logs = mutableListOf<String>()
    private val stops = mutableListOf<Pair<List<String>, File>>()

    private fun watch(config: ProcessWatchConfig = ProcessWatchConfig(stopGraceSeconds = 0), onStop: () -> Unit = {}) =
        ProcessWatch(
            ProcessLedger(os, clock = { now }), os, { config }, { now }, log = { logs += it },
            stopRunner = { argv, dir, _ -> stops += argv to dir; onStop() },
            waitGone = { _, _ -> },
        )

    /** The harness's shell starts a child, which detaches; the shell exits before any tick, so no tree walk ever saw the child. */
    private fun detachedChild(cmd: String = "node server.js", pid: Long = detached) {
        os.start(harness, null, "claude --session", 1_000)
        os.start(shell, harness, "bash -c 'nohup $cmd &'", 2_000)
        os.start(pid, shell, cmd, 3_000)
        os.members.getOrPut("s1") { HashSet() } += listOf(harness, shell, pid)
        os.exit(shell)
        os.procs[pid] = os.procs.getValue(pid).copy(parentPid = shell) // the parent pid dangles
    }

    @Test fun `a detached child whose shell exited before any tick is the session's, and is reaped when the session ends`() {
        detachedChild()
        session("s1")
        val w = watch()
        w.tick()
        assertEquals("s1", w.report().tracked.single { it.pid == detached }.sessionId, "attributed through the session's container")

        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
        assertEquals("session ended (EXITED)", w.report().rogue.single { it.pid == detached }.reason, "a left-over of an ended session is rogue")
        val reaped = w.reapSession("s1", now, reason = "session closed").map { it.pid }
        assertTrue(detached in reaped, "reaped: $reaped")
        assertTrue(detached !in os.procs, "the detached child is gone")
    }

    @Test fun `without a container the same orphan is never seen - the gap the container closes`() {
        detachedChild()
        os.members.clear()
        session("s1")
        val w = watch()
        w.tick()
        assertTrue(w.report().tracked.none { it.pid == detached })
    }

    @Test fun `the close-time observe takes container members the timer has not seen yet`() {
        os.start(harness, null, "claude --session", 1_000)
        session("s1")
        val w = watch()
        w.tick()
        detachedChild() // appears between two ticks
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
        assertTrue(detached in w.reapSession("s1", now).map { it.pid })
    }

    @Test fun `processes reapKinds limits what a close reaps, by tool profile kind`() {
        detachedChild("node server.js", server)
        os.start(daemon, null, "java GradleDaemon", 4_000)
        os.members.getValue("s1") += daemon
        session("s1")
        val w = watch(ProcessWatchConfig(reapKinds = setOf("gradle-stop"), stopGraceSeconds = 0))
        w.tick()
        sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
        val reaped = w.reapSession("s1", now).map { it.pid }
        assertTrue(daemon in reaped && daemon !in os.procs, "reaped $reaped; log $logs")
        assertTrue(server !in reaped && server in os.procs, "an `other` process is spared when its kind is not listed")
    }

    @Test fun `a build daemon gets its profile's graceful stop in the session's worktree first, and is not killed when it obeys`() {
        val wt = Files.createTempDirectory("proc-leak").toFile()
        try {
            File(wt, "gradlew").writeText("#!/bin/sh\n")
            File(wt, "gradlew.bat").writeText("@echo off\n")
            os.start(harness, null, "claude --session", 1_000)
            os.start(daemon, null, "java GradleDaemon", 4_000)
            os.members["s1"] = mutableSetOf(harness, daemon)
            session("s1", worktree = wt.path)
            val w = watch(ProcessWatchConfig(stopGraceSeconds = 10)) { os.exit(daemon) }
            w.tick()
            sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
            val reaped = w.reapSession("s1", now)
            val (argv, dir) = stops.single()
            assertEquals(wt.canonicalFile, dir.canonicalFile)
            assertTrue(File(argv.first()).parentFile.canonicalFile == wt.canonicalFile && argv.drop(1) == listOf("--stop"), "the worktree's own wrapper: $argv")
            assertTrue(daemon !in os.killed, "stopped, not killed")
            assertTrue(reaped.single { it.pid == daemon }.reason!!.contains("stopped gracefully"))
        } finally {
            wt.deleteRecursively()
        }
    }

    @Test fun `no graceful stop while another daemon of the kind lives - the session's own is killed by pid`() {
        val wt = Files.createTempDirectory("proc-leak").toFile()
        try {
            File(wt, "gradlew").writeText("#!/bin/sh\n")
            File(wt, "gradlew.bat").writeText("@echo off\n")
            os.start(harness, null, "claude --session", 1_000)
            os.start(daemon, null, "java GradleDaemon", 4_000)
            os.start(900_999L, null, "java GradleDaemon", 500) // the owner's own
            os.members["s1"] = mutableSetOf(harness, daemon)
            session("s1", worktree = wt.path)
            val w = watch(ProcessWatchConfig(stopGraceSeconds = 10))
            w.tick()
            sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
            w.reapSession("s1", now)
            assertTrue(stops.isEmpty(), "a stop would reach the owner's daemon too")
            assertTrue(daemon in os.killed && 900_999L in os.procs)
        } finally {
            wt.deleteRecursively()
        }
    }

    @Test fun `no stop without the wrapper in the worktree, and none when the grace is zero`() {
        val wt = Files.createTempDirectory("proc-leak").toFile()
        try {
            os.start(harness, null, "claude --session", 1_000)
            os.start(daemon, null, "java GradleDaemon", 4_000)
            os.members["s1"] = mutableSetOf(harness, daemon)
            session("s1", worktree = wt.path)
            val w = watch(ProcessWatchConfig(stopGraceSeconds = 10))
            w.tick()
            sessions["s1"] = sessions.getValue("s1").copy(state = SessionState.EXITED)
            w.reapSession("s1", now)
            assertTrue(stops.isEmpty() && daemon in os.killed)
        } finally {
            wt.deleteRecursively()
        }
    }

    @Test fun `a client with no CPU for the stuck minutes is reported stuck in its live session, alerted once`() {
        os.start(harness, null, "claude --session", 1_000)
        os.start(client, harness, "java -cp x ClientMainKt ticket get", 2_000)
        os.cpuNs[client] = 5_000L
        session("s1")
        val w = watch(ProcessWatchConfig(stuckMinutes = 15, stopGraceSeconds = 0))
        w.tick()
        now += 10 * 60_000L
        w.tick()
        assertNull(w.report().tracked.single { it.pid == client }.stuck, "10 min quiet is not stuck yet")
        now += 6 * 60_000L
        w.tick()
        val stuck = assertNotNull(w.report().tracked.single { it.pid == client }.stuck)
        assertTrue(stuck.contains("16 min"), stuck)
        w.tick()
        assertEquals(1, os.alerts.count { it.first == "client_stuck" }, "alerted once")
        assertTrue(ProcessesText.render(ProcessesAnswer(w.report()), showAll = false).contains("stuck (1):"))
    }

    @Test fun `a client using CPU, or with stuck detection off, is never stuck`() {
        os.start(harness, null, "claude --session", 1_000)
        os.start(client, harness, "java -cp x ClientMainKt ticket get", 2_000)
        os.cpuNs[client] = 5_000L
        session("s1")
        val busy = watch(ProcessWatchConfig(stuckMinutes = 15, stopGraceSeconds = 0))
        busy.tick()
        repeat(4) { now += 5 * 60_000L; os.cpuNs[client] = os.cpuNs.getValue(client) + 1_000_000L; busy.tick() }
        assertNull(busy.report().tracked.single { it.pid == client }.stuck)

        val off = watch(ProcessWatchConfig(stuckMinutes = 0, stopGraceSeconds = 0))
        off.tick()
        now += 60 * 60_000L
        off.tick()
        assertNull(off.report().tracked.single { it.pid == client }.stuck)
        assertTrue(os.alerts.isEmpty())
    }
}
