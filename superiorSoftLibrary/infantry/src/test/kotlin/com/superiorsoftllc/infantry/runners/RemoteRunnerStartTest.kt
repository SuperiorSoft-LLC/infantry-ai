// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.backlog.TicketBoard
import com.superiorsoftllc.infantry.backlog.localHost
import com.superiorsoftllc.infantry.context.ComposedContext
import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.TicketContext
import com.superiorsoftllc.infantry.daemon.LocalRunner
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import com.superiorsoftllc.infantry.daemon.plugins.LocalBoardHost
import com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy
import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon.RemoteRunnerDaemonPlugin
import com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon.RemoteRunnerLink
import com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon.WssLinkTransport
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.EffectiveContext
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.Placement
import com.superiorsoftllc.infantry.settings.RunnerEntry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import com.superiorsoftllc.infantry.worktree.TicketBaseRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-6 (ticket 4659b768): two in-process daemons, each with its own state dir, joined by the real Infantry_RemoteRunner
 * transport (wss with the pinned fingerprint, port 0). The orchestrator pairs runner r2, a ticket pinned `runner: r2` starts
 * there (r2 clones the project's git remote, cuts the ticket worktree, runs the harness), the branch is pushed to the shared
 * bare repo when the turn ends, and an unpinned ticket still starts on `local`.
 */
class RemoteRunnerStartTest {

    private val dir: File = Files.createTempDirectory("infantry-remote-start").toFile()
    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        closers.asReversed().forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun git(cwd: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "git ${args.joinToString(" ")} in $cwd: $out")
        return out.trim()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    private fun req(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) })

    private val prompting = object : TicketContext {
        override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
        override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
    }

    private fun manager(runner: ScriptedRunner, workDir: File) = SessionManager(
        FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50, defaultWorkingDir = workDir, bindingPolicy = TicketBindingPolicy(),
    )

    private fun localLink(name: String, manager: SessionManager, agents: AgentProfiles): RunnerLink = LocalRunnerLink(
        LocalRunner.services(
            manager, agents,
            info = { RunnerInfo(name, RunnerLink.CONTRACT_VERSION, harnesses = listOf(RunnerHarness(FakeAgentProvider().id, loggedIn = true))) },
            capacity = { 0 },
        ),
    )

    /** Port 0 for the link listener (tests never bind the owner's ports); every other limit is its default. */
    private val limits: (com.superiorsoftllc.infantry.settings.LimitDef) -> Long = { if (it == LimitRegistry.RUNNERS_LINK_PORT) 0L else it.default }

    @Test
    fun `a ticket pinned to runner r2 starts on r2 and pushes its branch, an unpinned one starts on local`() {
        // The project's git remote: a bare repo with one commit on main, and the orchestrator's checkout of it.
        val origin = File(dir, "origin.git").also { it.mkdirs() }
        git(origin, "init", "--bare", "-b", "main")
        val checkout = File(dir, "orch-checkout")
        git(dir, "clone", origin.absolutePath, checkout.absolutePath)
        git(checkout, "checkout", "-b", "main")
        File(checkout, "README.md").writeText("hello\n")
        git(checkout, "add", "README.md")
        git(checkout, "commit", "-m", "init")
        git(checkout, "push", "origin", "main")

        // Runner r2: its own state dir, store, session manager and clone root.
        val r2Dir = File(dir, "r2").also { it.mkdirs() }
        val r2Store = SqlInfantryStore.open(File(r2Dir, "infantry.db").absolutePath).also { closers += it }
        val r2Runner = ScriptedRunner(autoExit = false, emitSessionId = true)
        val r2Manager = manager(r2Runner, r2Dir)
        val r2Agents = AgentProfiles(r2Store.agents, r2Manager)
        val r2Starts = RemoteStarts(cloneRoot = { File(r2Dir, RemoteStarts.DEFAULT_FOLDER) }, eventsKept = { 100 }, log = {})
        r2Manager.eventSink = { event ->
            if (event is SessionEvent.Exit && r2Starts.isRemote(event.sessionId)) r2Starts.onTurnEnd(event.sessionId, event.code)
        }
        val r2Service = RunnerLinkService(
            File(r2Dir, "state"), limits,
            settings = { RunnerLinkSettings.Values(projects = listOf("p1"), harnesses = listOf(RunnerLinkSettings.ANY)) },
            runner = localLink("r2", r2Manager, r2Agents),
            transport = { WssLinkTransport }, host = "127.0.0.1", log = {}, starts = r2Starts,
        ).also { closers += it }

        // The orchestrator: its own state dir, store, session manager, link service and runner pool.
        val oDir = File(dir, "orch").also { it.mkdirs() }
        val oStore = SqlInfantryStore.open(File(oDir, "infantry.db").absolutePath).also { closers += it }
        val oRunner = ScriptedRunner(autoExit = false, emitSessionId = true)
        val oManager = manager(oRunner, oDir)
        val oAgents = AgentProfiles(oStore.agents, oManager)
        val oStateDir = File(oDir, "state")
        val oService = RunnerLinkService(oStateDir, limits, settings = { RunnerLinkSettings.Values() }, runner = null, transport = { WssLinkTransport }, host = "127.0.0.1", log = {})
            .also { closers += it }

        // Pair: the runner opens its pairing listener and shows a code; the orchestrator adds it with the shown fingerprint.
        val pair = r2Service.admin(req("op" to "pair", "name" to "r2"))
        assertEquals("true", RunnerLinkService.field(pair, "ok"), pair.toString())
        val added = oService.admin(
            req(
                "op" to "add", "name" to "r2", "url" to "wss://127.0.0.1:${RunnerLinkService.field(pair, "port")}",
                "code" to RunnerLinkService.field(pair, "code")!!, "fingerprint" to RunnerLinkService.field(pair, "fingerprint")!!,
                "orchestratorName" to "orch",
            ),
        )
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
        r2Service.tick() // paired: the runner now serves the link (a fresh port 0 here; a daemon serves limits.runners.linkPort)
        val port = assertNotNull(r2Service.servingPort(), "the paired runner serves the link")
        RunnerLinkStore.open(oStateDir).let { s -> s.saveRunner(s.runner("r2")!!.copy(url = "wss://127.0.0.1:$port")) }

        // The pool with the Infantry_RemoteRunner binding, as the plugin registers it; a heartbeat round sees r2 up.
        val hub = RunnerHub(localLink(RunnerPool.LOCAL, oManager, oAgents), remotes = { oService.links.paired() })
        hub.register(RemoteRunnerDaemonPlugin.BINDING_ID) { name, _ -> RemoteRunnerLink(name, oService.links) }
        hub.heartbeat(lostAfterMs = 60_000)
        assertEquals(RunnerHealth.UP, hub.runners().single { it.name == "r2" }.state, hub.runners().toString())

        var n = 0
        val board = TicketBoard(
            host = LocalBoardHost(
                oStore, { oManager }, baseRef = TicketBaseRef.fixed("main"), defaultRepo = checkout, agentProfiles = oAgents, runners = hub,
            ),
            idFactory = { "t${n++}" }, context = prompting, profilesAvailable = true,
            runnerEntries = {
                hub.runners().map { e ->
                    RunnerEntry(
                        e.name, e.state, e.health?.freeSlots?.let { if (it < 0) Int.MAX_VALUE else it } ?: 1,
                        e.info?.projects.orEmpty().toSet(), e.info?.harnesses.orEmpty().filter { it.loggedIn }.map { it.id }.toSet(),
                    )
                }
            },
        ).also { closers += AutoCloseable { it.close() } }
        oManager.eventSink = { board.localHost?.publish(it) }
        oManager.addFirstTurnProvider { sessionId, _ -> board.brief(sessionId) }
        val remoteWorker = oAgents.save(AgentProfile(name = "Remote", role = "backend", provider = FakeAgentProvider().id))
        val localWorker = oAgents.save(AgentProfile(name = "Local", role = "frontend", provider = FakeAgentProvider().id))

        // A ticket pinned to r2 starts there: the session lives in r2's manager, in a worktree of r2's own clone.
        val pinned = board.save(TicketSave(title = "Remote work", projectId = "p1", agentId = remoteWorker.id, runner = "r2")).ticket.id
        val started = board.move(TicketMove(pinned, IN_PROGRESS)).ticket
        assertEquals("started", started.start?.reason, started.start.toString())
        val sessionId = assertNotNull(board.get(pinned).sessionId)
        assertEquals("r2", hub.runnerOf(sessionId))
        val remoteSession = assertNotNull(r2Manager.get(sessionId), "the session runs on r2")
        assertTrue(oManager.get(sessionId) == null, "and not on the orchestrator")
        val branch = assertNotNull(board.get(pinned).branch, "the board records the branch r2 cut")
        val worktree = File(assertNotNull(remoteSession.worktreePath, "r2 started it in the ticket worktree"))
        assertTrue(worktree.canonicalPath.startsWith(File(r2Dir, RemoteStarts.DEFAULT_FOLDER).canonicalPath), "in r2's clone root: $worktree")

        // The worker commits; its turn ends; r2 pushes the ticket branch to the project's remote.
        File(worktree, "work.txt").writeText("done\n")
        git(worktree, "add", "work.txt")
        git(worktree, "commit", "-m", "work")
        val head = git(worktree, "rev-parse", "HEAD")
        r2Runner.processes.last().exit(0)
        waitFor("the branch on the bare repo") {
            runCatching { git(origin, "rev-parse", "refs/heads/$branch") == head }.getOrDefault(false)
        }

        // An unpinned ticket starts on local (runners.default = local).
        val unpinned = board.save(TicketSave(title = "Local work", projectId = "p1", agentId = localWorker.id)).ticket.id
        assertEquals("started", board.move(TicketMove(unpinned, IN_PROGRESS)).ticket.start?.reason)
        val localSession = assertNotNull(board.get(unpinned).sessionId)
        assertEquals(Placement.LOCAL, hub.runnerOf(localSession))
        assertNotNull(oManager.get(localSession), "the unpinned ticket's session runs on the orchestrator")
    }

    @Test
    fun `a project with no git remote is refused at placement with the reason`() {
        val checkout = File(dir, "no-remote").also { it.mkdirs() }
        git(checkout, "init", "-b", "main")
        File(checkout, "README.md").writeText("hello\n")
        git(checkout, "add", "README.md")
        git(checkout, "commit", "-m", "init")
        val store = SqlInfantryStore.open(File(dir, "o.db").absolutePath).also { closers += it }
        val runner = ScriptedRunner(autoExit = false, emitSessionId = true)
        val manager = manager(runner, dir)
        val agents = AgentProfiles(store.agents, manager)
        val hub = RunnerHub(localLink(RunnerPool.LOCAL, manager, agents), remotes = { listOf("r2") })
        hub.register(RemoteRunnerDaemonPlugin.BINDING_ID) { _, local -> local }
        val board = TicketBoard(
            host = LocalBoardHost(store, { manager }, baseRef = TicketBaseRef.fixed("main"), defaultRepo = checkout, agentProfiles = agents, runners = hub),
            context = prompting, profilesAvailable = true,
            runnerEntries = { listOf(RunnerEntry(Placement.LOCAL), RunnerEntry("r2", projects = setOf("p1"), loggedInHarnesses = setOf("fake"))) },
        ).also { closers += AutoCloseable { it.close() } }
        val worker = agents.save(AgentProfile(name = "Remote", role = "backend", provider = FakeAgentProvider().id))
        val id = board.save(TicketSave(title = "Remote work", projectId = "p1", agentId = worker.id, runner = "r2")).ticket.id

        val start = board.move(TicketMove(id, IN_PROGRESS)).ticket.start

        assertTrue(start?.reason != "started", start.toString())
        assertTrue(start.toString().contains("git remote"), start.toString())
        assertTrue(manager.list().isEmpty(), "nothing started: ${manager.list().map { it.id }}")
    }
}
