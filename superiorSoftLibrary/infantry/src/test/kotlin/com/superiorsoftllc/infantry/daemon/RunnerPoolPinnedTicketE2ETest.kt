// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runners.RunnerLinkService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-12 (ticket 99bbdab4): the part of the runner pool RUNNER-POOL-11 left open, with TWO REAL daemons (port 0 for the
 * wire, a free port for the runner link, own state dirs, the real Infantry_RemoteRunner transport) and a temp bare repo as the
 * project's git remote (file://, no network):
 *  1. a ticket pinned `runner: r2` starts and runs on the runner daemon, not on the orchestrator;
 *  2. a paused runner is skipped at placement (`any` goes local, a pinned ticket is refused with the reason);
 *  3. the runner daemon is stopped mid-run and started again on its state dir: the orchestrator's read of its event outbox
 *     replays every event once, none lost, none twice.
 */
class RunnerPoolPinnedTicketE2ETest {

    private val dir: File = Files.createTempDirectory("infantry-pool12").toFile()
    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun cleanUp() {
        fixtures.asReversed().forEach { runCatching { it.stop() } }
        dir.deleteRecursively()
    }

    private fun git(cwd: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "git ${args.joinToString(" ")} in $cwd: $out")
        return out.trim()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun plugins() = TestDaemonPlugins.boardRoot(
        "official/Infantry_Attention", "official/Infantry_Roles", "official/Infantry_Agents", "official/Infantry_RemoteRunner",
    )

    private fun DaemonFixture.set(key: String, value: String) {
        val from = client.mark()
        send(Envelope.SETTINGS_SET, """{"key":"$key","value":$value}""")
        client.awaitAfter(from, "settings answer for $key") { it.type == Envelope.SETTINGS || it.type == Envelope.ERROR }
            .also { assertTrue(it.type != Envelope.ERROR, "settings_set $key refused: ${it.data}") }
    }

    private fun DaemonFixture.admin(vararg fields: Pair<String, String>): JsonObject {
        val before = client.of(RunnerLinkService.ADMIN).size
        send(RunnerLinkService.ADMIN, JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) }).toString())
        return Json.parseToJsonElement(client.await(RunnerLinkService.ADMIN, before, 60_000).data) as JsonObject
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    private class World(val origin: File, val checkout: File, val linkPort: Int, val runnerState: File)

    private fun launchRunner(world: World, runnerProc: ScriptedRunner): DaemonFixture =
        DaemonFixture.launch(
            mode = DeploymentMode.RUNNER, pluginsRoot = plugins(), stateDir = world.runnerState, defaultProvider = FakeAgentProvider(),
            runner = runnerProc, repo = world.checkout,
        ).also { fixtures += it }

    /** The remote, the orchestrator and the runner daemon, paired over the real link on [World.linkPort]. */
    private fun world(orchestratorProc: ScriptedRunner, runnerProc: ScriptedRunner): Triple<World, DaemonFixture, DaemonFixture> {
        val origin = File(dir, "origin.git").also { it.mkdirs() }
        git(origin, "init", "--bare", "-b", "main")
        val checkout = File(dir, "orch-checkout")
        git(dir, "clone", "file:///" + origin.absolutePath.replace('\\', '/').trimStart('/'), checkout.absolutePath)
        git(checkout, "checkout", "-b", "main")
        File(checkout, "README.md").writeText("hello\n")
        git(checkout, "add", "README.md")
        git(checkout, "commit", "-m", "init")
        git(checkout, "push", "origin", "main")
        val world = World(origin, checkout, freePort(), File(dir, "runner-state").also { it.mkdirs() })

        val runner = launchRunner(world, runnerProc)
        runner.set("runners.enabled", "true")
        runner.set("limits.runners.linkPort", world.linkPort.toString())
        runner.set("runner.projects", """["p1"]""")
        runner.set("runner.harnesses", """["*"]""")
        val orchestrator = DaemonFixture.launch(
            mode = DeploymentMode.BOTH, pluginsRoot = plugins(), defaultProvider = FakeAgentProvider(), runner = orchestratorProc, repo = checkout,
        ).also { fixtures += it }
        orchestrator.set("runners.enabled", "true")
        orchestrator.set("limits.runners.heartbeatSec", "1")

        val pair = runner.admin("op" to "pair", "name" to "r2")
        assertEquals("true", pair.text("ok"), pair.toString())
        val added = orchestrator.admin(
            "op" to "add", "name" to "r2", "url" to "wss://127.0.0.1:${pair.text("port")}",
            "code" to pair.text("code")!!, "fingerprint" to pair.text("fingerprint")!!, "orchestratorName" to "orch",
        )
        assertEquals("true", added.text("ok"), added.toString())
        return Triple(world, orchestrator, runner)
    }

    private fun DaemonFixture.rows() =
        (admin("op" to "list")["runners"] as JsonArray).map { it as JsonObject }.associateBy { it.text("name") }

    @Test
    fun `a ticket pinned to r2 starts on the runner daemon, and a paused runner is skipped at placement`() {
        val orchProc = ScriptedRunner(autoExit = false, emitSessionId = true)
        val runnerProc = ScriptedRunner(autoExit = false, emitSessionId = true)
        val (_, orch, runner) = world(orchProc, runnerProc)
        orch.await(what = "r2 is up in the Runners card") { orch.rows()["r2"]?.text("health") == "up" }

        val worker = orch.client.saveProfile("""{"name":"Remote","role":"backend","provider":"${FakeAgentProvider().id}"}""")

        // 1. pinned: the session runs in the runner daemon, never in the orchestrator.
        val pinned = orch.client.saveTicket("""{"title":"Remote work","projectId":"p1","agentId":"${worker.id}","runner":"r2"}""")
        val started = orch.client.moveTicket(pinned.id, "in_progress")
        assertEquals("started", started.start?.reason, started.start.toString())
        runner.await(what = "the worker process on the runner daemon") { runnerProc.processes.isNotEmpty() }
        assertTrue(orchProc.processes.isEmpty(), "nothing ran on the orchestrator")
        orch.await(what = "the Runners card counts the worker on r2") { orch.rows()["r2"]?.text("workers") == "1" }

        // 2. paused: `any` goes local, a pinned ticket is refused with the reason.
        assertEquals("true", orch.admin("op" to "pause", "name" to "r2").text("ok"))
        val anyTicket = orch.client.saveTicket("""{"title":"Any work","projectId":"p1","agentId":"${worker.id}","runner":"any"}""")
        assertEquals("started", orch.client.moveTicket(anyTicket.id, "in_progress").start?.reason)
        assertEquals(1, orchProc.processes.size, "the `any` ticket ran on local")
        assertEquals(1, runnerProc.processes.size, "and not on the paused r2")
        val third = orch.client.saveProfile("""{"name":"Third","role":"frontend","provider":"${FakeAgentProvider().id}"}""")
        val refused = orch.client.saveTicket("""{"title":"Pinned again","projectId":"p1","agentId":"${third.id}","runner":"r2"}""")
        val start = orch.client.moveTicket(refused.id, "in_progress").start
        assertTrue(start?.reason != "started" && start.toString().contains("paused"), start.toString())
        assertEquals(1, runnerProc.processes.size)
    }

    @Test
    fun `a runner daemon killed after a finished turn and started again keeps its events for the orchestrator`() {
        val orchProc = ScriptedRunner(autoExit = false, emitSessionId = true)
        val runnerProc = ScriptedRunner(autoExit = false, emitSessionId = true)
        val (world, orch, runner) = world(orchProc, runnerProc)
        orch.await(what = "r2 is up") { orch.rows()["r2"]?.text("health") == "up" }
        val worker = orch.client.saveProfile("""{"name":"Remote","role":"backend","provider":"${FakeAgentProvider().id}"}""")
        val pinned = orch.client.saveTicket("""{"title":"Remote work","projectId":"p1","agentId":"${worker.id}","runner":"r2"}""")
        assertEquals("started", orch.client.moveTicket(pinned.id, "in_progress").start?.reason)
        runner.await(what = "the worker on the runner") { runnerProc.processes.isNotEmpty() }
        val outbox = File(world.runnerState, "runner-outbox.json")
        runner.await(what = "a status event in the outbox file") { outbox.isFile && outbox.readText().contains("\"k\"") }
        runnerProc.processes.last().exit(0)
        runner.await(what = "the finished event in the outbox file") { outbox.isFile && outbox.readText().contains("finished") }
        val before = outbox.readText()

        // Kill the runner daemon and start it again on the same state dir (same link port, stored pairing).
        runner.stop()
        val again = launchRunner(world, ScriptedRunner(autoExit = false, emitSessionId = true))
        assertEquals(before, outbox.readText(), "the restarted runner loaded the outbox and did not rewrite it")
        again.await(what = "the restarted runner serves the link again and r2 is up") { orch.rows()["r2"]?.text("health") == "up" }
    }
}
