// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 3 gate, at the process level.
 *
 * [SessionLifecycleTest] proves the state machine with fakes; this proves the parts a fake cannot:
 * the REAL [PipedProcessRunner] spawning a real OS child, delivering the prompt on stdin and closing
 * it (EOF), capturing the pid, killing the process tree, and -- above all -- resuming across a
 * "daemon restart" (a fresh [SessionManager] over the same on-disk store) by re-invoking the child
 * with the stored provider session id.
 *
 * The child is [FakeProviderCli], a real JVM process launched from the test runtime classpath, so no
 * model or network is involved and the test is deterministic. The one thing it deliberately does NOT
 * cover is the Ktor WebSocket layer, which is a thin Envelope wrapper over these events.
 */
class SessionProcessIntegrationTest {

    private val javaExe: String = run {
        val home = File(System.getProperty("java.home"))
        listOf(File(home, "bin/java.exe"), File(home, "bin/java"))
            .firstOrNull { it.isFile }?.absolutePath
            ?: error("no java executable under ${home.path}/bin")
    }

    private val classpath: String = System.getProperty("java.class.path").orEmpty()

    private fun provider(): AgentProvider = object : AgentProvider {
        override val id = "fakejava"

        override fun spec(request: RunRequest): ProcessSpec = ProcessSpec(
            command = buildList {
                add(javaExe)
                add("-cp")
                add(classpath)
                add("com.superiorsoftllc.infantry.session.FakeProviderCliKt")
                request.resumeSessionId?.let { add("--session"); add(it) }
            },
            workingDir = request.workingDir,
        )

        override fun sessionIdFromLine(line: String): String? =
            SESSION_ID.find(line)?.groupValues?.get(1)

        private val SESSION_ID = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"")
    }

    private fun await(timeoutMs: Long = 15_000, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("condition not met within ${timeoutMs}ms: $message")
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "infantry-it-${UUID.randomUUID()}").apply { mkdirs() }

    @Test
    fun realProcess_completes_thenResumesAcrossManagerRestart_keepsContext() {
        val dir = tempDir()
        val storeFile = File(dir, "sessions.json")

        // 1) First daemon: a real run that completes on its own.
        val managerA = SessionManager(
            provider(), PipedProcessRunner(), JsonSessionStore(storeFile), defaultWorkingDir = dir,
        )
        val session = managerA.startNew("hello turn one", workingDir = dir)
        await(message = "provider session id observed") { managerA.get(session.id)!!.providerSessionId != null }
        val providerSessionId = managerA.get(session.id)!!.providerSessionId!!
        await(message = "run reaches IDLE") { managerA.get(session.id)!!.state == SessionState.IDLE }
        assertTrue(
            // Read THROUGH the store, not the raw file. The daemon writes sessions.json via
            // tmpFile + Files.move, whose replacement window (see JsonSessionStore) leaves the file
            // momentarily absent or locked -- on Windows that surfaces as a FileNotFoundException
            // for a raw storeFile.readText(), fc7942c8's symptom. load() retries the window and
            // falls back to the .tmp sibling, so the assertion reads what a restarted daemon
            // (loadAndReconcile) would read, which is the property this check really owns:
            // "the provider session id must be persisted while the run is alive".
            JsonSessionStore(storeFile).load().any { it.providerSessionId == providerSessionId },
            "the provider session id must be persisted while the run is alive",
        )

        // 2) The daemon dies and comes back: a fresh manager over the same on-disk store.
        val managerB = SessionManager(
            provider(), PipedProcessRunner(), JsonSessionStore(storeFile),
            defaultWorkingDir = dir, pidProbe = { false },
        )
        managerB.loadAndReconcile()

        // 3) Resume = a real child re-invoked with the SAME provider session id, carrying context.
        val stdout = mutableListOf<String>()
        managerB.eventSink = { event -> if (event is SessionEvent.Stdout) stdout += event.line }
        val resumed = managerB.resume(session.id, "second turn")
        await(message = "resumed run reaches IDLE") { managerB.get(session.id)!!.state == SessionState.IDLE }

        assertEquals(1, resumed.resumeCount)
        assertEquals(providerSessionId, managerB.get(session.id)!!.providerSessionId)
        assertTrue(
            stdout.any { it.contains(providerSessionId) },
            "the resumed child must be invoked with the stored session id (context intact)",
        )
        assertTrue(
            stdout.any { it.contains("second turn") },
            "the resume prompt must reach the child",
        )
    }

    @Test
    fun realProcess_kill_escalatesToForce_andFreesThePid() {
        val dir = tempDir()
        val manager = SessionManager(
            provider(), PipedProcessRunner(), JsonSessionStore(File(dir, "sessions.json")),
            killGraceMs = 300, defaultWorkingDir = dir,
        )
        val session = manager.startNew("SLEEP now", workingDir = dir)
        await(message = "sleeping child is BUSY with a pid") {
            val now = manager.get(session.id)!!
            now.childPid != null && now.state == SessionState.BUSY
        }
        val pid = manager.get(session.id)!!.childPid!!
        assertTrue(
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false),
            "the child must be alive before the kill",
        )

        val killed = manager.kill(session.id)

        assertEquals(SessionState.EXITED, killed.state)
        assertEquals(KillOutcome.FORCED, killed.killOutcome)
        assertEquals(null, killed.childPid)
        await(message = "the killed child is gone") {
            !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }
    }
}
