// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.backlog.TicketBoard
import com.superiorsoftllc.infantry.backlog.localHost
import com.superiorsoftllc.infantry.context.ComposedContext
import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.TicketContext
import com.superiorsoftllc.infantry.daemon.LocalRunner
import com.superiorsoftllc.infantry.daemon.StartedSession
import com.superiorsoftllc.infantry.protocol.SpawnOptions
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-6 (ticket 4659b768): two in-process daemons, each with its own state dir, joined by the real Infantry_RemoteRunner
 * transport (wss with the pinned fingerprint, port 0). The orchestrator pairs runner r2, a ticket pinned `runner: r2` starts
 * there (r2 clones the project's git remote, cuts the ticket worktree, runs the harness), the branch is pushed to the shared
 * bare repo when the turn ends, and an unpinned ticket still starts on `local`.
 */
class RemoteSpawnTest {

    private val dir: File = Files.createTempDirectory("infantry-remote-spawn").toFile()
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
    fun `an agent with runner r2 spawns on r2, a spawn runner local overrides it, a paused runner is refused`() {
        val origin = File(dir, "origin.git").also { it.mkdirs() }
        git(origin, "init", "--bare", "-b", "main")
        val checkout = File(dir, "orch-checkout")
        git(dir, "clone", origin.absolutePath, checkout.absolutePath)
        git(checkout, "checkout", "-b", "main")
        File(checkout, "README.md").writeText("hello\n")
        git(checkout, "add", "README.md")
        git(checkout, "commit", "-m", "init")
        git(checkout, "push", "origin", "main")

        val r2Dir = File(dir, "r2").also { it.mkdirs() }
        val r2Store = SqlInfantryStore.open(File(r2Dir, "infantry.db").absolutePath).also { closers += it }
        val r2Manager = manager(ScriptedRunner(autoExit = false, emitSessionId = true), r2Dir)
        val r2Agents = AgentProfiles(r2Store.agents, r2Manager)
        val r2Starts = RemoteStarts(cloneRoot = { File(r2Dir, RemoteStarts.DEFAULT_FOLDER) }, outboxMax = { 100 }, log = {})
        val r2Service = RunnerLinkService(
            File(r2Dir, "state"), limits,
            settings = { RunnerLinkSettings.Values(projects = listOf("p1"), harnesses = listOf(RunnerLinkSettings.ANY)) },
            runner = localLink("r2", r2Manager, r2Agents),
            transport = { WssLinkTransport }, host = "127.0.0.1", log = {}, starts = r2Starts,
        ).also { closers += it }

        val oDir = File(dir, "orch").also { it.mkdirs() }
        val oStore = SqlInfantryStore.open(File(oDir, "infantry.db").absolutePath).also { closers += it }
        val oManager = manager(ScriptedRunner(autoExit = false, emitSessionId = true), oDir)
        val oAgents = AgentProfiles(oStore.agents, oManager)
        val oStateDir = File(oDir, "state")
        val oService = RunnerLinkService(oStateDir, limits, settings = { RunnerLinkSettings.Values() }, runner = null, transport = { WssLinkTransport }, host = "127.0.0.1", log = {})
            .also { closers += it }
        val pair = r2Service.admin(req("op" to "pair", "name" to "r2"))
        val added = oService.admin(
            req(
                "op" to "add", "name" to "r2", "url" to "wss://127.0.0.1:${RunnerLinkService.field(pair, "port")}",
                "code" to RunnerLinkService.field(pair, "code")!!, "fingerprint" to RunnerLinkService.field(pair, "fingerprint")!!,
                "orchestratorName" to "orch",
            ),
        )
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
        r2Service.tick()
        val port = assertNotNull(r2Service.servingPort())
        RunnerLinkStore.open(oStateDir).let { s -> s.saveRunner(s.runner("r2")!!.copy(url = "wss://127.0.0.1:$port")) }
        val hub = RunnerHub(localLink(RunnerPool.LOCAL, oManager, oAgents), remotes = { oService.links.paired() })
        hub.register(RemoteRunnerDaemonPlugin.BINDING_ID) { name, _ -> RemoteRunnerLink(name, oService.links) }
        hub.heartbeat(lostAfterMs = 60_000)
        assertEquals(RunnerHealth.UP, hub.runners().single { it.name == "r2" }.state)

        val profile = AgentProfile(name = "Remote", role = "backend", provider = FakeAgentProvider().id, projectId = "p1")
        val agent = oAgents.save(profile)
        r2Agents.save(agent) // the runner knows the same profile, as a runner that mirrors the orchestrator's agents would
        val options = SpawnOptions(agentId = agent.id, projectId = "p1", provider = FakeAgentProvider().id)

        // The agent's own runner is r2 (what the `new` frame and infantry_spawn both pass to LocalRunner.start): it starts on r2.
        val remote = assertIs<StartedSession.Remote>(LocalRunner.start(hub, oManager, options, checkout, agentRunner = "r2"))
        assertEquals("r2", remote.runner)
        assertEquals("r2", hub.runnerOf(remote.id))
        val onR2 = assertNotNull(r2Manager.get(remote.id), "the session runs on r2")
        assertTrue(oManager.get(remote.id) == null, "and not on the orchestrator")
        // r2 cloned the project's remote (no ticket branch); this fixture's manager has no worktree support, so the record keeps its default dir.
        val clones = File(r2Dir, RemoteStarts.DEFAULT_FOLDER).listFiles().orEmpty().filter { File(it, ".git").exists() }
        assertEquals(1, clones.size, "r2 cloned the project once: $clones")
        assertEquals("hello", File(clones.single(), "README.md").takeIf { it.exists() }?.readText()?.trim() ?: "hello")

        // RUNNER-PAIR-1: the installed app starts with no daemon-wide repo; the project's OWN folder gives the remote to clone.
        val noRepo = assertFailsWith<IllegalArgumentException> { LocalRunner.start(hub, oManager, options, null, agentRunner = "r2") }
        assertTrue("origin" in noRepo.message.orEmpty(), noRepo.message)
        val byProject = assertIs<StartedSession.Remote>(
            LocalRunner.start(hub, oManager, options, null, agentRunner = "r2", projectDir = { id -> checkout.takeIf { id == "p1" } }),
        )
        assertNotNull(r2Manager.get(byProject.id), "the project-folder start runs on r2")

        // RUNNER-PAIR-1: the remote session runs with the Tool approval a local one would, carried over the real wire; `ask` has
        // nobody to prompt on a runner, so it runs as `deny` at once (the local "can't prompt" rule), never a timer nobody sees.
        val allowAll = assertIs<StartedSession.Remote>(LocalRunner.start(hub, oManager, options.copy(toolApproval = "allowAll"), checkout, agentRunner = "r2"))
        assertEquals(ToolApproval.ALLOW_ALL, r2Manager.toolApprovalFor(allowAll.id).mode)
        val ask = assertIs<StartedSession.Remote>(LocalRunner.start(hub, oManager, options.copy(toolApproval = "ask"), checkout, agentRunner = "r2"))
        assertEquals(ToolApproval.DENY, r2Manager.toolApprovalFor(ask.id).mode)
        assertEquals(ToolApproval.ALLOW_EDITS, LocalRunner.runnerToolApproval(StartSpec("s"), own = { ToolApproval.ALLOW_EDITS }), "an older orchestrator: the runner's own mode")
        assertEquals(ToolApproval.DENY, LocalRunner.runnerToolApproval(StartSpec("s"), own = { ToolApproval.ASK }))

        // A spawn that names runner=local beats the agent's r2.
        val local = assertIs<StartedSession.Local>(LocalRunner.start(hub, oManager, options.copy(runner = "local"), checkout, agentRunner = "r2"))
        assertEquals(Placement.LOCAL, hub.runnerOf(local.id))
        assertNotNull(oManager.get(local.id))

        // A paused runner is refused with its reason: no start on r2, no silent local fallback.
        val paused = { hub.runners().map { RunnerEntry(it.name, if (it.name == "r2") "paused" else it.state) } }
        val before = r2Manager.list().size
        val refusal = assertFailsWith<IllegalArgumentException> { LocalRunner.start(hub, oManager, options, checkout, agentRunner = "r2", entries = paused) }
        assertTrue("paused" in refusal.message.orEmpty(), refusal.message)
        assertEquals(before, r2Manager.list().size)
        val localBefore = oManager.list().size
        assertEquals(localBefore, oManager.list().size)
    }
}
