// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerServices
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartWorktree
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-WORKTREE-PATH-1: a ticket started on a runner reports the worktree path ON THE RUNNER, not the orchestrator's checkout.
 * Two sides in one process: the runner's link server cuts the worktree in its own clone of the project's remote and answers its
 * path; the orchestrator's mirror record carries it and the `ticket start` worktree line labels it with the runner.
 */
class RunnerWorktreePathTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val now = 1_000_000L
    private val dir: File = Files.createTempDirectory("runner-wt-path").toFile()

    private fun git(cwd: File, vararg a: String): String =
        ProcessBuilder(listOf("git", "-C", cwd.path) + a).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }

    /** git as a runner image sees it: no global or system config, no identity in the environment (the bug's runner: no ~/.gitconfig). */
    private fun bareGit(cwd: File, vararg a: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("git", "-C", cwd.path) + a).redirectErrorStream(true)
        val env = pb.environment()
        listOf("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL", "EMAIL").forEach { env.remove(it) }
        env["GIT_CONFIG_GLOBAL"] = File(dir, "no-global-gitconfig").path
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        return pb.start().let { p -> p.inputStream.bufferedReader().readText().let { out -> p.waitFor() to out } }
    }

    @Test
    fun `the runner's start reply carries the runner-side worktree and the mirror record and start line show it`() {
        // The orchestrator's checkout and the remote both sides reach.
        val origin = File(dir, "origin.git").also { it.mkdirs() }.also { git(it, "init", "--bare", "-b", "main") }
        val orchestratorCheckout = File(dir, "pc").also { it.mkdirs() }
        git(orchestratorCheckout, "init", "-b", "main"); git(orchestratorCheckout, "config", "user.email", "a@b.c"); git(orchestratorCheckout, "config", "user.name", "t")
        File(orchestratorCheckout, "a.txt").writeText("a"); git(orchestratorCheckout, "add", "."); git(orchestratorCheckout, "commit", "-m", "init")
        git(orchestratorCheckout, "remote", "add", "origin", origin.path); git(orchestratorCheckout, "push", "origin", "main")

        // The runner: its own state dir and clone root, a local runner that starts session s-1 in the spec's worktree.
        val runnerDir = File(dir, "runner").also { it.mkdirs() }
        val cloneRoot = File(runnerDir, "clones")
        var startedIn: StartWorktree? = null
        val local = LocalRunnerLink(LocalRunnerServices(
            ticketIdOf = { null }, info = { RunnerInfo("trw1", F.CONTRACT) },
            start = { spec: StartSpec -> startedIn = spec.worktree; "s-1" },
            stop = { _, _, _ -> true }, deliver = { _, _ -> MailResult(MailResult.DELIVERED) }, health = { RunnerHealth(RunnerHealth.UP) },
        ))
        val audit = RunnerAudit(File(runnerDir, RunnerAudit.FILE)) { now }
        val codes = PairCodes(File(runnerDir, PairCodes.ATTEMPTS_FILE), expiryMs = { 10 * 60_000L }, attemptsPerHour = { 5 })
        val server = RunnerLinkServer(
            RunnerLinkStore(LinkVault.InMemory()), audit, codes,
            settings = { RunnerLinkSettings.Values(projects = listOf("*"), harnesses = listOf("*")) },
            runner = { local }, clock = { now },
            starts = RemoteStarts(cloneRoot = { cloneRoot }, outboxMax = { 100 }, log = {}),
        )
        val orchestratorStore = RunnerLinkStore(LinkVault.InMemory())
        val credential = RunnerPeers(orchestratorStore, LinkCall { _, _, id, c, type, data, _ -> server.handle(type, data, id, c, "peer") }, clock = { now })
            .add("trw1", "wss://h:1", codes.issue("trw1", now), "AA", "orch").credential

        val request = F.StartRequest(
            startId = "29cbb634:1", projectId = "p1", provider = "claude", ticketId = "29cbb634", title = "hello two",
            gitRemote = origin.path, base = "main",
        )
        val answer = server.handle(F.START, F.json.encodeToString(F.StartRequest.serializer(), request), orchestratorStore.selfId(), credential)
        assertTrue(answer.ok, answer.data)
        val reply = F.json.decodeFromString(F.StartAnswer.serializer(), answer.data)
        assertEquals("s-1", reply.sessionId)
        val runnerPath = assertNotNull(reply.worktreePath, "the start reply names the worktree on the runner: ${answer.data}")
        assertEquals(startedIn?.path, runnerPath, "the path the runner's session was started in")
        assertTrue(File(runnerPath).canonicalPath.startsWith(cloneRoot.canonicalPath), "under the runner's clone root: $runnerPath")
        assertNotEquals(orchestratorCheckout.canonicalPath, File(runnerPath).canonicalPath)
        assertTrue(reply.branch!!.startsWith("ticket/29cbb634"), reply.branch)

        // The orchestrator's mirror record: the checkout stays the repo root (where the branch is fetched), the worktree is the runner's.
        val mirror = RemoteSessionMirror(sessionIds = { listOf("s-1") }, clock = { now })
        mirror.placed("s-1", RemoteSessionMirror.Placed("claude", "sonnet", orchestratorCheckout.path, reply.branch, "29cbb634", reply.worktreePath))
        val rec = assertNotNull(mirror.get("s-1"))
        assertEquals(runnerPath, rec.worktreePath)
        assertEquals(orchestratorCheckout.path, rec.repoRoot)

        // `ticket start` labels it with the runner the wire frame names; a local worker prints its path as before.
        val onRunner = rec.copy(runner = "trw1")
        assertEquals("$runnerPath (on runner trw1)", Cli.worktreeLine(onRunner))
        assertEquals("(on runner trw1; its path was not reported)", Cli.worktreeLine(onRunner.copy(worktreePath = null)))
        assertEquals(orchestratorCheckout.path, Cli.worktreeLine(rec.copy(worktreePath = null, workingDir = orchestratorCheckout.path)))
    }

    /**
     * RUNNER-GIT-IDENT-1: a remote start carries the orchestrator's git identity and the runner writes it into its clone, so the
     * worker's first commit in the ticket worktree has an author (a runner image has no ~/.gitconfig); the runner's own
     * `runner.gitName` / `runner.gitEmail` win over it.
     */
    @Test
    fun `a remote start gives the runner's clone a git identity, and the runner's own setting wins`() {
        val origin = File(dir, "id-origin.git").also { it.mkdirs() }.also { git(it, "init", "--bare", "-b", "main") }
        val pc = File(dir, "id-pc").also { it.mkdirs() }
        git(pc, "init", "-b", "main"); git(pc, "config", "user.email", "owner@pc.test"); git(pc, "config", "user.name", "Owner PC")
        File(pc, "a.txt").writeText("a"); git(pc, "add", "."); git(pc, "commit", "-m", "init")
        git(pc, "remote", "add", "origin", origin.path); git(pc, "push", "origin", "main")
        // The orchestrator side reads the identity a start carries from its checkout.
        assertEquals("Owner PC" to "owner@pc.test", RunnerLinkServer.userGitIdentity(pc))

        val runnerDir = File(dir, "id-runner").also { it.mkdirs() }
        val cloneRoot = File(runnerDir, "clones")
        var startedIn: StartWorktree? = null
        val local = LocalRunnerLink(LocalRunnerServices(
            ticketIdOf = { null }, info = { RunnerInfo("idr", F.CONTRACT) },
            start = { spec: StartSpec -> startedIn = spec.worktree; "s-1" },
            stop = { _, _, _ -> true }, deliver = { _, _ -> MailResult(MailResult.DELIVERED) }, health = { RunnerHealth(RunnerHealth.UP) },
        ))
        var values = RunnerLinkSettings.Values(projects = listOf("*"), harnesses = listOf("*"))
        val codes = PairCodes(File(runnerDir, PairCodes.ATTEMPTS_FILE), expiryMs = { 10 * 60_000L }, attemptsPerHour = { 5 })
        val server = RunnerLinkServer(
            RunnerLinkStore(LinkVault.InMemory()), RunnerAudit(File(runnerDir, RunnerAudit.FILE)) { now }, codes,
            settings = { values }, runner = { local }, clock = { now },
            starts = RemoteStarts(cloneRoot = { cloneRoot }, outboxMax = { 100 }, log = {}),
        )
        val orchestratorStore = RunnerLinkStore(LinkVault.InMemory())
        val credential = RunnerPeers(orchestratorStore, LinkCall { _, _, id, c, type, data, _ -> server.handle(type, data, id, c, "peer") }, clock = { now })
            .add("idr", "wss://h:1", codes.issue("idr", now), "AA", "orch").credential
        fun start(ticket: String) {
            val request = F.StartRequest(
                startId = "$ticket:1", projectId = "p1", provider = "claude", ticketId = ticket, title = "ident", gitRemote = origin.path,
                base = "main", gitName = "Owner PC", gitEmail = "owner@pc.test",
            )
            val answer = server.handle(F.START, F.json.encodeToString(F.StartRequest.serializer(), request), orchestratorStore.selfId(), credential)
            assertTrue(answer.ok, answer.data)
        }

        start("aaaa1111")
        val wt = File(assertNotNull(startedIn).path)
        assertEquals("Owner PC", git(wt, "config", "--local", "user.name").trim())
        assertEquals("owner@pc.test", git(wt, "config", "--local", "user.email").trim())
        // The worker's commit goes through with that author even with no global git config (the runner image has none).
        File(wt, "b.txt").writeText("b"); git(wt, "add", "b.txt")
        val (code, out) = bareGit(wt, "-c", "user.useConfigOnly=true", "commit", "-m", "worker")
        assertEquals(0, code, out)
        assertEquals("Owner PC <owner@pc.test>", git(wt, "log", "-1", "--format=%an <%ae>").trim())
        assertEquals("Owner PC <owner@pc.test>", git(wt, "log", "-1", "--format=%cn <%ce>").trim())

        // The runner's own identity overrides the orchestrator's on the next start.
        values = values.copy(gitName = "Runner Box", gitEmail = "runner@box.test")
        start("bbbb2222")
        val wt2 = File(assertNotNull(startedIn).path)
        assertEquals("Runner Box", git(wt2, "config", "--local", "user.name").trim())
        assertEquals("runner@box.test", git(wt2, "config", "--local", "user.email").trim())
    }

    @Test
    fun `an older runner's start answer with no worktree path still decodes, and the local link passes the spec's path`() {
        val old = F.json.decodeFromString(F.StartAnswer.serializer(), """{"sessionId":"s-9","branch":"ticket/x","base":"abc"}""")
        assertNull(old.worktreePath)
        val link = LocalRunnerLink(LocalRunnerServices(
            ticketIdOf = { null }, info = { RunnerInfo("box", F.CONTRACT) }, start = { "s-2" },
            stop = { _, _, _ -> true }, deliver = { _, _ -> MailResult(MailResult.DELIVERED) }, health = { RunnerHealth(RunnerHealth.UP) },
        ))
        val ref = (link.startSession(StartSpec(startId = "t:1", worktree = StartWorktree("/r/wt", "ticket/x", "abc", "/r"))) as OrchestratorReply.Ok).value
        assertEquals("/r/wt", ref.worktreePath)
    }
}
