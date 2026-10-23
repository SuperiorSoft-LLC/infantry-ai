package com.superiorsoftllc.agenthq.ui.owner

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.RunFinishedPayload
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RUN-CMD-3: the owner's Run tab path end to end — "no way to add or run" is gone.
 *
 * A REAL daemon JVM (`RealDaemonMainKt`, headless, as [OwnerRig] boots it but on the owner's default provider, so runs
 * spawn real processes) on a free port, with a temp
 * `AGENTHQ_HOME` (where `commands.json` lives) and a temp `AGENTHQ_REPO` (a git repo, the root every command's cwd is
 * checked against). Never `~/.agenthq`, never 8787/8788. The client is the real [Controller] (the window's brain) and
 * every assertion reads either its state or the raw frames it received — no fixtures, no hand-written commands.json.
 *
 *  1. empty list; 2. add the echo command; 3. Start it: one stdout "hello-run", exactly one run_finished, exit 0;
 *  4. add a long-running ping; restart: both commands survive; Start the ping, Stop: KILLED_* and its tree is gone;
 *  5. delete both: empty list; restart the daemon on the same home: still empty.
 *
 * RED on base before RUN-CMD-1/2: there was no `command_save` / `command_delete` (contract < 1.94.0), so step 2's
 * [Controller.saveCommand] is refused and nothing can be added.
 */
class RunTabOwnerSmokeTest {
    private val root = Files.createTempDirectory("agenthq-run-cmd-3").toFile()
    private val home = File(root, "home").apply { mkdirs() }
    private val repo = File(root, "repo").apply { mkdirs() }
    private val userHome = File(root, "user-home").apply { mkdirs() }
    private val logs = File(root, "logs").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val daemons = mutableListOf<Process>()
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest fun tearDown() {
        scope.cancel()
        daemons.forEach(::stop)
        Smoke.reportDir?.let { dir -> runCatching { logs.copyRecursively(File(dir, "run-tab/logs"), overwrite = true) } }
        repeat(10) { if (!root.exists() || root.deleteRecursively()) return; Thread.sleep(500) }
    }

    @Test fun `the owner adds a command in the Run tab, starts it, sees its output, stops a long one and deletes both`() {
        gitInit(repo)
        val frames = Collections.synchronizedList(mutableListOf<Envelope>())
        val first = startDaemon("daemon-1")
        val c = connect(first, frames)

        // 1. A fresh home: no commands.
        assertEquals(emptyList(), c.state.value.runs.commands, "a fresh AGENTHQ_HOME starts with no commands")
        assertTrue(c.supportsCommandEdit(), "the daemon must speak command_save / command_delete (contract >= 1.94.0)")

        // 2. Add the echo command, through the same call the Run tab's Save button makes.
        sent("save the echo command", runBlocking { c.saveCommand(ECHO, "Echo hello-run", ECHO_ARGV, null, 5_000) })
        until("the echo command listed", c) { s -> s.runs.commands.map { it.id } == listOf(ECHO) }
        assertEquals(ECHO_ARGV, c.state.value.runs.commands.single().argv, "the daemon lists the argv it was given")
        assertEquals(null, c.state.value.runs.commandError)

        // 3. Start it: the output line, then exactly one run_finished, exit 0.
        frames.clear()
        sent("start the echo command", runBlocking { c.startRun(ECHO) })
        val echo = until("the echo run finished", c) { it.runs.current?.commandId == ECHO && it.runs.current?.state == "FINISHED" }.runs.current!!
        assertEquals(listOf("hello-run"), echo.lines.filter { it.stream == "stdout" }.map { it.text.trim() }, "one stdout line hello-run")
        assertEquals("SUCCEEDED", echo.finished!!.outcome)
        assertEquals(0, echo.finished!!.exitCode)
        val echoFinished = runFinished(frames, echo.runId)
        assertEquals(1, echoFinished.size, "exactly one run_finished for the echo run")
        assertEquals(0, echoFinished.single().exitCode)

        // 4. Add a long-running ping. Both saves must be on disk: a restart on the same AGENTHQ_HOME lists both.
        sent("save the ping command", runBlocking { c.saveCommand(PING, "Ping loop", PING_ARGV, null, 2_000) })
        until("both commands listed", c) { s -> s.runs.commands.map { it.id }.toSet() == setOf(ECHO, PING) }
        val (daemon, owner, frames2) = restart(first, c, "daemon-2")
        assertEquals(
            mapOf(ECHO to ECHO_ARGV, PING to PING_ARGV),
            owner.state.value.runs.commands.associate { it.id to it.argv },
            "both saved commands survive a daemon restart",
        )

        // Start the ping, Stop it: KILLED_* and its process tree gone.
        val daemonBefore = daemon.descendants().map { it.pid() }.toList().toSet()
        sent("start the ping", runBlocking { owner.startRun(PING) })
        val running = until("the ping produced output", owner) {
            it.runs.current?.commandId == PING && (it.runs.current?.lines?.size ?: 0) >= 1
        }.runs.current!!
        assertTrue(running.finished == null, "the ping is still in flight before Stop")
        // The run record carries no pid (RunManager never fills it), so the run's tree is what the daemon JVM gained.
        val tree = daemon.descendants().filter { it.isAlive && it.pid() !in daemonBefore }.toList()
        assertTrue(tree.any { "ping" in it.info().command().orElse("").lowercase() }, "a live ping under the daemon: ${describe(tree)}")

        sent("stop the ping", runBlocking { owner.stopRun(running.runId) })
        val stopped = until("the ping finished", owner) { it.runs.runs[running.runId]?.state == "FINISHED" }.runs.runs.getValue(running.runId)
        val outcome = stopped.finished!!.outcome
        assertTrue(outcome == "KILLED_GRACEFUL" || outcome == "KILLED_FORCED", "a stopped run is KILLED_*, was $outcome")
        assertEquals(1, runFinished(frames2, running.runId).size, "exactly one run_finished after Stop")
        awaitGone("the ping's process tree ${describe(tree)}") { tree.none { it.isAlive } }
        awaitGone("every child the daemon gained for the ping") {
            daemon.descendants().filter { it.isAlive && it.pid() !in daemonBefore }.toList().isEmpty()
        }

        // 5. Delete both: empty.
        sent("delete the echo command", runBlocking { owner.deleteCommand(ECHO) })
        sent("delete the ping command", runBlocking { owner.deleteCommand(PING) })
        until("no commands listed", owner) { it.runs.commands.isEmpty() }
        assertEquals(null, owner.state.value.runs.commandError)

        // ...and a restart on the same AGENTHQ_HOME still lists none.
        val (_, after, _) = restart(daemon, owner, "daemon-3")
        assertEquals(emptyList(), after.state.value.runs.commands, "the deletes survive a daemon restart")
    }

    /** Stops [daemon] (after disconnecting [window]) and boots [name] on the same home, with a fresh, connected window. */
    private fun restart(daemon: Process, window: Controller, name: String): Triple<Process, Controller, MutableList<Envelope>> {
        window.disconnect()
        stop(daemon)
        val next = startDaemon(name)
        val frames = Collections.synchronizedList(mutableListOf<Envelope>())
        return Triple(next, connect(next, frames), frames)
    }

    // ---- the daemon ---------------------------------------------------------------------------------------------------

    private fun startDaemon(name: String): Process {
        val requested = OwnerRig.freePort()
        val argfile = File(root, "$name.args")
        argfile.writeText(
            listOf(
                "-Djava.awt.headless=true",
                "\"-Duser.home=${userHome.path.replace('\\', '/')}\"",
                "-cp",
                OwnerRig.argQuote(Smoke.daemonClasspath),
                OwnerRig.DAEMON_MAIN_CLASS,
            ).joinToString("\n"),
        )
        val log = File(logs, "$name.log")
        val builder = ProcessBuilder(Smoke.java.path, "@${argfile.path}")
            .directory(repo)
            .redirectErrorStream(true)
            .redirectOutput(log)
        builder.environment().apply {
            OwnerRig.scrub(this)
            put("AGENTHQ_PORT", requested.toString())
            // The owner's default provider, NOT the stub: StubProvider's scripted transport also answers run_start, so
            // a stub daemon never runs a real process. No session is ever spawned here, so no CLI is needed.
            put("AGENTHQ_PROVIDER", PROVIDER)
            put("AGENTHQ_STATE_DIR", File(home, "state").path)
            put("AGENTHQ_HOME", home.path)
            put("AGENTHQ_REPO", repo.path)
            put("AGENTHQ_LIMITS_LIVE", "false")
            put("AGENTHQ_HEADLESS", "true")
        }
        val process = builder.start()
        daemons += process
        val boot = OwnerRig.awaitLog(log, process, OwnerRig.BOOT_LINE, "$name's boot line")
        val port = boot.groupValues[3].toInt()
        check(boot.groupValues[1] == PROVIDER) { "$name runs provider ${boot.groupValues[1]}, not $PROVIDER" }
        check(port !in OwnerRig.OWNER_PORTS) { "$name bound $port, one of the owner's ports" }
        OwnerRig.awaitLog(log, process, OwnerRig.HEADLESS_LINE, "$name's headless line")
        wsUrls[process] = "ws://${boot.groupValues[2]}:$port/ws"
        return process
    }

    private val wsUrls = mutableMapOf<Process, String>()

    /** Closing stdin is the headless daemon's stop signal; anything left is killed. */
    private fun stop(process: Process) {
        if (!process.isAlive) return
        val tree = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        runCatching { process.outputStream.close() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(20, TimeUnit.SECONDS)
        }
        tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    // ---- the owner's window -------------------------------------------------------------------------------------------

    /** A fresh [Controller], subscribed to its frames BEFORE connecting, live, with the daemon's command list in hand. */
    private fun connect(daemon: Process, frames: MutableList<Envelope>): Controller {
        val c = Controller(scope)
        val listed = Collections.synchronizedList(mutableListOf<Envelope>())
        scope.launch { c.client.frames.collect { frames += it; if (it.type == Envelope.COMMANDS) listed += it } }
        Thread.sleep(100)
        c.connect(wsUrls.getValue(daemon), null)
        until("connected", c) { it.connection.isLive }
        val end = System.currentTimeMillis() + WAIT_MS
        while (listed.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(25)
        if (listed.isEmpty()) throw AssertionError("the daemon never sent its command list (${Envelope.COMMANDS})")
        return c
    }

    private fun describe(tree: List<ProcessHandle>) =
        tree.map { "${it.pid()}:${File(it.info().command().orElse("?")).name}" }

    private fun runFinished(frames: List<Envelope>, runId: String) = synchronized(frames) { frames.toList() }
        .filter { it.type == Envelope.RUN_FINISHED }
        .map { json.decodeFromString(RunFinishedPayload.serializer(), it.data) }
        .filter { it.runId == runId }

    private fun until(what: String, c: Controller, timeoutMs: Long = WAIT_MS, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        val s = c.state.value
        throw AssertionError("not met: $what; conn=${s.connection} runs=${s.runs} notices=${s.notices}")
    }

    private fun awaitGone(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError("still alive after Stop: $what")
    }

    private fun sent(what: String, result: CommandResult) {
        assertIs<CommandResult.Sent>(result, "the owner's window could not $what: $result")
    }

    private fun gitInit(dir: File) {
        val builder = ProcessBuilder("git", "init", "-b", "main", dir.path).redirectErrorStream(true)
        OwnerRig.scrub(builder.environment())
        val p = builder.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git init failed: $out" }
    }

    private companion object {
        const val WAIT_MS = 60_000L
        const val PROVIDER = "opencode"
        const val ECHO = "echo-hello-run"
        const val PING = "ping-loop"
        val ECHO_ARGV = if (Smoke.windows) listOf("cmd.exe", "/c", "echo", "hello-run") else listOf("sh", "-c", "echo hello-run")
        val PING_ARGV = if (Smoke.windows) {
            listOf("cmd.exe", "/c", "ping", "-n", "600", "127.0.0.1")
        } else {
            listOf("sh", "-c", "while true; do ping -c 1 127.0.0.1; sleep 1; done")
        }
    }
}
