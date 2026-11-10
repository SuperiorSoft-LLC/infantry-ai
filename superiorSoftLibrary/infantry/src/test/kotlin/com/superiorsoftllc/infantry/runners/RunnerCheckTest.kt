// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import com.superiorsoftllc.infantry.worktree.GitCli
import com.superiorsoftllc.infantry.worktree.GitResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire.CheckRow
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-CHECK-1 (docs/wiki/RunnerPairing-design.md 4, step 4): the health check over the link. Every row is computed on the runner
 * and carries the fix for a failing one; the orchestrator adds the `link` row (reached, in ms) and the `git:origin` row, and turns
 * an old runner's refusal of the unknown frame into one `contract` row.
 */
class RunnerCheckTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private var now = 1_000_000L
    private val dir: File = Files.createTempDirectory("runner-check-test").toFile()
    private val runnerStore = RunnerLinkStore(LinkVault.InMemory())
    private val audit = RunnerAudit(File(dir, RunnerAudit.FILE)) { now }
    private val codes = PairCodes(File(dir, PairCodes.ATTEMPTS_FILE), expiryMs = { 10 * 60_000L }, attemptsPerHour = { 5 })
    private var settings = RunnerLinkSettings.Values()
    private var limits: Map<String, Long> = emptyMap()
    private val cloneRoot = File(dir, "clones").also { it.mkdirs() }
    private var gitResult = GitResult(emptyList(), 0, "", "")
    private val gitCalls = mutableListOf<List<String>>()
    private var ownIdentity: Pair<String, String>? = null
    private val gitTimeouts = mutableListOf<Long>()
    private val harnesses = listOf(RunnerHarness("alpha", listOf("m1"), loggedIn = false), RunnerHarness("echo", loggedIn = true))

    private val local = object : RunnerLink {
        override fun ticketIdOf(sessionId: String): String? = null
        override fun hello(): OrchestratorReply<RunnerInfo> = OrchestratorReply.Ok(RunnerInfo("box", F.CONTRACT, harnesses))
        override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> = OrchestratorReply.Ok(SessionRef("s-1"))
        override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> = OrchestratorReply.Ok(true)
    }

    /** The runner's link server, with the fake git seam and the test's limit catalog. */
    private val server = RunnerLinkServer(
        runnerStore, audit, codes, settings = { settings }, runner = { local }, clock = { now },
        limit = { def -> limits[def.key] ?: def.default },
        git = { ms -> gitTimeouts += ms; GitCli { _, args -> gitCalls += args; gitResult } },
        cloneRoot = { cloneRoot },
        ownGitIdentity = { ownIdentity },
    )

    /** The orchestrator store a check is made for; paired once (a runner accepts one orchestrator), then reused. */
    private var paired: Pair<RunnerLinkStore, String>? = null

    private fun pair(): Pair<RunnerLinkStore, String> = paired ?: run {
        val store = RunnerLinkStore(LinkVault.InMemory())
        val cred = RunnerPeers(store, LinkCall { _, _, id, c, type, data, _ -> server.handle(type, data, id, c, PEER) }, clock = { now })
            .add("box", "wss://h:1", codes.issue("box", now), "AA", "orch").credential
        (store to cred).also { paired = it }
    }

    /** One in-process CHECK on the runner, decoded into rows. */
    private fun checkRows(
        project: String? = null,
        harness: String? = null,
        gitRemote: String? = null,
        contract: String? = F.CONTRACT,
        gitName: String? = null,
        gitEmail: String? = null,
    ): List<CheckRow> {
        val (store, credential) = pair()
        val request = F.CheckRequest(projectId = project, harness = harness, gitRemote = gitRemote, contract = contract, gitName = gitName, gitEmail = gitEmail)
        val answer = server.handle(F.CHECK, F.json.encodeToString(F.CheckRequest.serializer(), request), store.selfId(), credential)
        assertTrue(answer.ok, answer.data)
        return F.json.decodeFromString(F.CheckAnswer.serializer(), answer.data).rows
    }

    private fun row(rows: List<CheckRow>, id: String): CheckRow = assertNotNull(rows.firstOrNull { it.id == id }, "no row '$id' in ${rows.map { it.id }}")

    @Test
    fun `the contract row compares the runner's contract with the orchestrator's`() {
        val ok = row(checkRows(), "contract")
        assertTrue(ok.ok, ok.detail)
        assertTrue(ok.detail.contains(F.CONTRACT), ok.detail)

        val bad = row(checkRows(contract = "0.9.0"), "contract")
        assertFalse(bad.ok)
        assertTrue(bad.detail.contains("0.9.0") && bad.detail.contains(F.CONTRACT), bad.detail)
        assertTrue(bad.fix!!.contains("update the runner"), bad.fix!!)

        // An older orchestrator that sends no contract is not a mismatch.
        assertTrue(row(checkRows(contract = null), "contract").ok)
    }

    @Test
    fun `the git identity row says who a worker commits as, and fails with the fix when nobody gives one`() {
        val none = row(checkRows(), RunnerLinkServer.IDENTITY_ROW)
        assertFalse(none.ok, none.detail)
        assertTrue(none.fix!!.contains(RunnerLinkSettings.GIT_NAME) && none.fix!!.contains(RunnerLinkSettings.GIT_EMAIL), none.fix!!)

        ownIdentity = "Box User" to "box@x.test"
        assertTrue(row(checkRows(), RunnerLinkServer.IDENTITY_ROW).detail.contains("Box User <box@x.test>"))

        val sent = row(checkRows(gitName = "Owner", gitEmail = "o@x.test"), RunnerLinkServer.IDENTITY_ROW)
        assertTrue(sent.ok && sent.detail.contains("Owner <o@x.test>") && sent.detail.contains("orchestrator"), sent.detail)

        settings = settings.copy(gitName = "Runner", gitEmail = "r@x.test")
        val own = row(checkRows(gitName = "Owner", gitEmail = "o@x.test"), RunnerLinkServer.IDENTITY_ROW)
        assertTrue(own.ok && own.detail.contains("Runner <r@x.test>"), own.detail)
        // Half a setting is no identity: the orchestrator's is used.
        settings = settings.copy(gitEmail = "")
        assertTrue(row(checkRows(gitName = "Owner", gitEmail = "o@x.test"), RunnerLinkServer.IDENTITY_ROW).detail.contains("Owner <o@x.test>"))
    }

    @Test
    fun `every installed harness is checked, and the requested one only`() {
        // The default: one row per installed harness. alpha is installed but not signed in, echo is signed in.
        val all = checkRows()
        assertTrue(row(all, "harness:echo").ok)
        val alpha = row(all, "harness:alpha")
        assertFalse(alpha.ok)
        assertTrue(alpha.detail.contains("not signed in"), alpha.detail)
        assertTrue(alpha.fix!!.contains("log alpha in on this runner"), alpha.fix!!)
        assertTrue(alpha.detail.contains("docker exec -it"), "the container hint rides on the detail line: ${alpha.detail}")
        assertNull(all.firstOrNull { it.id == "grant:harness" }, "no harness named, no grant row")

        // Only the requested harness, plus its grant row.
        val one = checkRows(harness = "echo")
        assertTrue(one.any { it.id == "harness:echo" })
        assertNull(one.firstOrNull { it.id == "harness:alpha" })
        assertNotNull(one.firstOrNull { it.id == "grant:harness" })

        // A harness the runner does not have.
        val missing = row(checkRows(harness = "ghost"), "harness:ghost")
        assertFalse(missing.ok)
        assertTrue(missing.detail.contains("not installed"), missing.detail)
        assertTrue(missing.fix!!.contains("install the ghost CLI"), missing.fix!!)
    }

    @Test
    fun `a grant row fails with the exact settings line that adds the id`() {
        val project = row(checkRows(project = "p1", harness = "echo"), "grant:project")
        assertFalse(project.ok)
        assertTrue(project.detail.contains("empty = none"), project.detail)
        assertEquals("on this runner: infantry settings set ${RunnerLinkSettings.PROJECTS} '[\"p1\"]'", project.fix)

        val harness = row(checkRows(harness = "echo"), "grant:harness")
        assertEquals("on this runner: infantry settings set ${RunnerLinkSettings.HARNESSES} '[\"echo\"]'", harness.fix)

        // The add line keeps what is already granted and adds the id.
        settings = RunnerLinkSettings.Values(projects = listOf("p0"), harnesses = listOf("echo"))
        val next = row(checkRows(project = "p1"), "grant:project")
        assertEquals("on this runner: infantry settings set ${RunnerLinkSettings.PROJECTS} '[\"p0\",\"p1\"]'", next.fix)

        // Granted: ok, and `*` counts.
        settings = RunnerLinkSettings.Values(projects = listOf("p1"), harnesses = listOf("*"))
        assertTrue(row(checkRows(project = "p1", harness = "echo"), "grant:project").ok)
        assertTrue(row(checkRows(harness = "echo"), "grant:harness").ok)
    }

    @Test
    fun `the git row reads the remote heads within the named timeout`() {
        gitResult = GitResult(listOf("ls-remote", "--heads", "https://example.com/x.git"), 0, "abc\trefs/heads/main\ndef\trefs/heads/dev\n", "")
        val ok = row(checkRows(gitRemote = "https://example.com/x.git"), "git")
        assertTrue(ok.ok, ok.detail)
        assertTrue(ok.detail.contains("2 branch heads"), ok.detail)
        assertEquals(listOf("ls-remote", "--heads", "https://example.com/x.git"), gitCalls.single())
        assertEquals(listOf(LimitRegistry.RUNNERS_CHECK_GIT_SEC.default * 1_000L), gitTimeouts)
    }

    @Test
    fun `a failing git row says the exit and the first stderr line, with the credential stripped`() {
        gitResult = GitResult(
            listOf("ls-remote"), 128, "",
            "fatal: could not read from 'https://bob:s3cr3t@example.com/x.git'\nsecond line is not shown",
        )
        val row = row(checkRows(gitRemote = "https://bob:s3cr3t@example.com/x.git"), "git")
        assertFalse(row.ok)
        assertTrue(row.detail.contains("exited 128"), row.detail)
        assertTrue(row.detail.contains("https://example.com/x.git"), row.detail)
        assertFalse(row.detail.contains("s3cr3t"), "the token must not cross the link: ${row.detail}")
        assertFalse(row.detail.contains("second line"), row.detail)
        assertFalse(row.fix!!.contains("s3cr3t"), row.fix!!)
        assertTrue(row.fix!!.contains("read+push access"), row.fix!!)

        // The scrubbing is its own guarantee, whatever git prints.
        assertEquals("https://example.com/x.git", RunnerLinkServer.scrubCredentials("https://bob:s3cr3t@example.com/x.git"))
        assertEquals("git@example.com:x.git", RunnerLinkServer.scrubCredentials("git@example.com:x.git"))
        assertEquals("ssh://example.com:22/x.git", RunnerLinkServer.scrubCredentials("ssh://bob:pw@example.com:22/x.git"))
    }

    @Test
    fun `the disk row compares free space with checkMinFreeMb`() {
        val ok = row(checkRows(), "disk")
        assertTrue(ok.ok, ok.detail)
        assertTrue(ok.detail.contains(LimitRegistry.RUNNERS_CHECK_MIN_FREE_MB.key), ok.detail)
        limits = mapOf(LimitRegistry.RUNNERS_CHECK_MIN_FREE_MB.key to Long.MAX_VALUE - 1)
        val full = row(checkRows(), "disk")
        assertFalse(full.ok)
        assertTrue(full.fix!!.contains("free disk space"), full.fix!!)
    }

    @Test
    fun `the orchestrator adds the link row and the git origin row`() {
        val transport = InProcess()
        val runner = RunnerLinkService(
            File(dir, "runner"), { it.default }, { settings }, local, { transport }, host = "127.0.0.1", log = {}, clock = { now },
        )
        val orch = RunnerLinkService(File(dir, "orch"), { it.default }, { RunnerLinkSettings.Values() }, null, { transport }, host = "127.0.0.1", log = {}, clock = { now })
        pairThrough(runner, orch)
        runner.tick()

        val rows = rowsOf(orch.admin(op("check", "name" to "box", "projectId" to "p1", "harness" to "echo")))
        assertTrue(row(rows, "link").ok, row(rows, "link").detail)
        assertTrue(row(rows, "link").detail.contains("reached wss://h:1 in"), row(rows, "link").detail)
        // No project remote: the runner cannot check git, and the orchestrator says the checkout has no origin at all.
        val origin = row(rows, "git:origin")
        assertFalse(origin.ok)
        assertTrue(origin.fix!!.contains("remote add origin"), origin.fix!!)
        assertNull(rows.firstOrNull { it.id == "git" })
        assertTrue(row(rows, "contract").ok)
        assertTrue(row(rows, "harness:echo").ok)
        assertFalse(row(rows, "grant:project").ok)

        // With a project remote the runner's git row comes back (a local path: the runner's real git seam answers it, no network).
        val localRemote = File(dir, "git/nope.git").absolutePath
        orch.projectRemoteOf = { localRemote }
        val withRemote = rowsOf(orch.admin(op("check", "name" to "box", "projectId" to "p1", "harness" to "echo")))
        assertNull(withRemote.firstOrNull { it.id == "git:origin" })
        val git = row(withRemote, "git")
        assertFalse(git.ok, "a path that is not a repository fails the row: ${git.detail}")
        assertTrue(git.fix!!.contains("read+push access"), git.fix!!)
        assertTrue(git.fix!!.contains(localRemote), git.fix!!)
        runner.close(); orch.close()
    }

    @Test
    fun `a check right after pairing, before any tick, is answered by the paired runner`() {
        // RUNNER-CHECK-STALE-1: the pairing listener keeps the port until the next tick; it has no local runner.
        val transport = InProcess()
        val runner = RunnerLinkService(
            File(dir, "runner"), { it.default }, { settings }, local, { transport }, host = "127.0.0.1", log = {}, clock = { now },
        )
        val orch = RunnerLinkService(File(dir, "orch"), { it.default }, { RunnerLinkSettings.Values() }, null, { transport }, host = "127.0.0.1", log = {}, clock = { now })
        pairThrough(runner, orch)
        // no runner.tick() here

        val rows = rowsOf(orch.admin(op("check", "name" to "box", "projectId" to "p1", "harness" to "echo")))
        assertTrue(row(rows, "contract").ok, row(rows, "contract").detail)
        assertTrue(row(rows, "harness:echo").ok, row(rows, "harness:echo").detail)
        runner.close(); orch.close()
    }

    @Test
    fun `an old runner becomes one contract row`() {
        val transport = InProcess().also { it.refuses += F.CHECK }
        val runner = RunnerLinkService(
            File(dir, "old-runner"), { it.default }, { RunnerLinkSettings.Values() }, null, { transport }, host = "127.0.0.1", log = {},
        )
        val orch = RunnerLinkService(File(dir, "old-orch"), { it.default }, { RunnerLinkSettings.Values() }, null, { transport }, host = "127.0.0.1", log = {}, clock = { now })
        pairThrough(runner, orch)
        runner.tick()

        val rows = rowsOf(orch.admin(op("check", "name" to "box")))
        val contract = row(rows, "contract")
        assertFalse(contract.ok)
        assertTrue(contract.detail.contains("runner too old for checks"), contract.detail)
        assertTrue(contract.fix!!.contains("update the runner"), contract.fix!!)
        assertTrue(row(rows, "link").ok, "the link row still says it was reached: ${row(rows, "link").detail}")
        assertEquals(listOf("link", "contract"), rows.map { it.id })
        orch.close(); runner.close()
    }

    // --- helpers ---

    /** Pairs [runner] with [orch] through the transport they share (the PAIR path `runner add` takes). */
    private fun pairThrough(runner: RunnerLinkService, orch: RunnerLinkService) {
        val code = RunnerLinkService.field(runner.admin(op("pair", "name" to "box")), "code")
        assertNotNull(code, "the runner issued no pair code")
        val added = orch.admin(op("add", "name" to "box", "url" to "wss://h:1", "code" to code, "fingerprint" to FP, "orchestratorName" to "orch"))
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
    }

    private fun op(name: String, vararg fields: Pair<String, String?>) =
        JsonObject((fields.filter { it.second != null } + ("op" to name)).associate { (k, v) -> k to JsonPrimitive(v!!) })

    private fun rowsOf(answer: JsonObject): List<CheckRow> {
        assertEquals("true", RunnerLinkService.field(answer, "ok"), answer.toString())
        val rows = answer["rows"] as JsonArray
        return rows.map { F.json.decodeFromString(CheckRow.serializer(), it.toString()) }
    }

    /**
     * An in-process [RunnerLinkTransport]: [call] reaches the handler [serve] was given, no socket. [refuses] lists the frames an
     * older runner does not implement: it answers those with an error, as its `else ->` branch does.
     */
    private class InProcess : RunnerLinkTransport {
        private var handler: LinkHandler? = null
        val refuses = mutableSetOf<String>()
        override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer {
            if (type in refuses) return LinkAnswer(F.ERROR, "$type refused: not a runner link operation this runner offers (contract 1.3.0)")
            return (handler ?: throw LinkFailure("nothing serves $url")).handle(type, data, orchestratorId, credential, PEER)
        }
        override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing {
            this.handler = handler
            return object : LinkServing {
                override val port: Int = 1
                override fun close() { this@InProcess.handler = null }
            }
        }
        override fun fingerprint(tls: LinkTlsMaterial): String = FP
    }

    private companion object {
        const val PEER = "100.64.0.9"
        const val FP = "AA:BB:CC"
    }
}
