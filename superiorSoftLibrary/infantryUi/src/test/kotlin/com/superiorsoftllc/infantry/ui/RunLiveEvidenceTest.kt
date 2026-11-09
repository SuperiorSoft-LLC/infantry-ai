// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * IN-APP BUILD T6 live evidence: a REAL in-process daemon (real Ktor + WebSocket, real [PipedProcessRunner],
 * real child processes) with two saved commands in a real commands.json. The client is the real [Controller].
 * Windows-only: the saved commands are `cmd.exe /c ...`.
 */
private val WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

class RunLiveEvidenceTest {
    private companion object { val OWNER = "o".repeat(44) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun boot(): Int {
        val dir = Files.createTempDirectory("infantry-run-live").toFile()
        val commands = File(dir, "commands.json")
        commands.writeText(
            """[
              {"id":"echo-hello","name":"Echo hello","argv":["cmd.exe","/c","echo","hello"],"graceMillis":2000},
              {"id":"sleeper","name":"Sleeper","argv":["cmd.exe","/c","ping","-n","60","127.0.0.1"],"graceMillis":2000}
            ]""",
        )
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = PipedProcessRunner(),
            repo = dir,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            // SEC-FIX-1: runs need the owner credential; this test's own, never the operator's owner.token.
            ownerToken = OWNER,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            commandsFile = commands,
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    private fun until(what: String, c: Controller, timeoutMs: Long = 20_000, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; runs=${c.state.value.runs}")
    }

    private fun connected(frames: MutableList<Envelope>): Controller {
        val c = Controller(scope)
        // Subscribed BEFORE connect so no frame can be missed.
        scope.launch { c.client.frames.collect { frames += it } }
        Thread.sleep(100)
        val url = "ws://127.0.0.1:${boot()}/ws"
        c.client.ownerFor(url, OWNER)
        c.connect(url, null)
        until("connected", c) { it.connection.state == ConnState.CONNECTED }
        until("commands listed", c) { it.runs.commands.size == 2 }
        return c
    }

    private fun runFrames(frames: List<Envelope>) =
        frames.filter { it.type in setOf(Envelope.RUN, Envelope.RUN_OUTPUT, Envelope.RUN_FINISHED) }

    @Test fun `echo hello runs to SUCCEEDED and the frames arrive run, output, finished`() {
        assumeTrue("Windows-only: the saved commands are cmd.exe /c ... (no cmd.exe on Linux/macOS)", WINDOWS) // CI-TESTS-2
        val frames = Collections.synchronizedList(mutableListOf<Envelope>())
        val c = connected(frames)
        assertEquals(setOf("echo-hello", "sleeper"), c.state.value.runs.commands.map { it.id }.toSet())

        assertIs<CommandResult.Sent>(runBlocking { c.startRun("echo-hello") })
        val s = until("run finished", c) { it.runs.current?.state == "FINISHED" }.runs.current!!

        val fin = s.finished!!
        assertEquals("SUCCEEDED", fin.outcome)
        assertEquals(0, fin.exitCode)
        assertEquals(listOf("hello"), s.lines.filter { it.stream == "stdout" }.map { it.text.trim() }, "the echoed line, once")
        assertEquals((1..s.lines.size).map { it.toLong() }, s.lines.map { it.sequence }, "sequence is 1..n with no gap")
        assertEquals(null, s.gap)

        // The raw frames, in order: the answer first, every output next, exactly one finished LAST.
        val types = runFrames(frames.toList()).map { it.type }
        assertEquals(Envelope.RUN, types.first(), "run is the answer to run_start")
        assertEquals(Envelope.RUN_FINISHED, types.last(), "run_finished closes the run")
        assertEquals(1, types.count { it == Envelope.RUN_FINISHED }, "exactly one run_finished")
        assertTrue(types.count { it == Envelope.RUN_OUTPUT } >= 1, "output arrived between: $types")
    }

    @Test fun `a sleeping command is stopped and ends KILLED`() {
        assumeTrue("Windows-only: the saved commands are cmd.exe /c ... (no cmd.exe on Linux/macOS)", WINDOWS) // CI-TESTS-2
        val frames = Collections.synchronizedList(mutableListOf<Envelope>())
        val c = connected(frames)

        assertIs<CommandResult.Sent>(runBlocking { c.startRun("sleeper") })
        // It is really running: ping prints a reply line before we stop it.
        val running = until("sleeper produced output", c) { (it.runs.current?.lines?.size ?: 0) >= 1 }.runs.current!!
        assertTrue(running.state != "FINISHED" && running.finished == null, "still in flight, was ${running.state}")

        assertIs<CommandResult.Sent>(runBlocking { c.stopRun(running.runId) })
        val s = until("sleeper finished", c) { it.runs.current?.state == "FINISHED" }.runs.current!!

        val outcome = s.finished!!.outcome
        assertTrue(outcome == "KILLED_GRACEFUL" || outcome == "KILLED_FORCED", "stopped run must be KILLED_*, was $outcome")

        val types = runFrames(frames.toList()).map { it.type }
        assertEquals(Envelope.RUN, types.first())
        assertEquals(Envelope.RUN_FINISHED, types.last())
        assertEquals(1, types.count { it == Envelope.RUN_FINISHED }, "exactly one run_finished after a Stop")
        assertTrue(types.indexOf(Envelope.RUN_OUTPUT) in 1 until types.lastIndex, "output sits between run and finished: $types")
    }
}
