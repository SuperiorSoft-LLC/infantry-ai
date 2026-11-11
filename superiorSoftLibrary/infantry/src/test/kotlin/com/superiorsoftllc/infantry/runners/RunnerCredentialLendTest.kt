// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerServices
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCredentials
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-CRED-1: an orchestrator lends a remote start its harness key, the project's secrets and a git credential over the link;
 * the runner keeps them in memory for that session, and no value reaches a log, the audit file, a toString or the volume.
 */
class RunnerCredentialLendTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val dummyKey = "sk-dummy-KEY-0451"
    private val dummySecret = "dummy-SECRET-7781"
    private val dummyToken = "ghp_dummyTOKEN9930"
    private val now = 1_000_000L
    private val dir: File = Files.createTempDirectory("runner-cred").toFile()

    private val lent = SessionCredentials(
        env = mapOf("ANTHROPIC_API_KEY" to dummyKey),
        secrets = mapOf("db" to mapOf("value" to dummySecret)),
        git = GitCredential(dummyToken),
    )

    private fun noValue(text: String, where: String) {
        listOf(dummyKey, dummySecret, dummyToken).forEach { assertFalse(text.contains(it), "$where holds a lent value: $text") }
    }

    @Test
    fun `the start frame carries the credentials and no toString shows a value`() {
        val request = F.StartRequest(startId = "t:1", projectId = "p1", provider = "claude", credentials = lent)
        val wire = F.json.encodeToString(F.StartRequest.serializer(), request)
        val back = F.json.decodeFromString(F.StartRequest.serializer(), wire)
        assertEquals(lent, back.credentials)
        // An older orchestrator sends no field: the start lends nothing.
        assertNull(F.json.decodeFromString(F.StartRequest.serializer(), """{"startId":"t:2"}""").credentials)
        assertEquals("1.8.0", F.CONTRACT)

        noValue(request.toString(), "StartRequest.toString")
        noValue(StartSpec("t:1", credentials = lent).toString(), "StartSpec.toString")
        noValue(lent.toString(), "SessionCredentials.toString")
        noValue(lent.git.toString(), "GitCredential.toString")
        assertTrue(lent.toString().contains("ANTHROPIC_API_KEY") && lent.toString().contains("db"), "names stay readable: $lent")
    }

    @Test
    fun `the runner screens a loan and keeps secrets per live session of the project`() {
        val bad = SessionCredentials(
            env = mapOf("ANTHROPIC_API_KEY" to dummyKey, "INFANTRY_TOKEN" to "x", "PATH" to "/evil", "EMPTY_KEY" to ""),
            secrets = mapOf("db" to mapOf("value" to dummySecret), "harness.claude.key" to mapOf("value" to "k"), "a" to mapOf("value" to "1"), "b" to mapOf("value" to "2")),
            git = GitCredential("two\nlines"),
        )
        val s = LentCredentials.screen(bad, maxSecrets = 2)
        assertEquals(mapOf("ANTHROPIC_API_KEY" to dummyKey), s.credentials.env)
        assertEquals(setOf("db", "a"), s.credentials.secrets.keys, "the built-in is dropped, then the cap of 2 keeps the first two")
        assertNull(s.credentials.git)
        assertTrue(s.dropped.any { it.startsWith("env:INFANTRY_TOKEN") } && s.dropped.any { it.startsWith("env:PATH") } && "git" in s.dropped, s.dropped.toString())
        noValue(s.summary(), "Screened.summary")

        val alive = mutableSetOf("s-1", "s-2")
        val store = LentCredentials(alive = { it in alive })
        store.lend("s-1", "p1", lent)
        assertEquals(mapOf("value" to dummySecret), store.secretFields("db", "p1"))
        assertNull(store.secretFields("db", "p2"), "another project's render never sees it")
        assertEquals(setOf("db"), store.secretNames("p1"))
        alive -= "s-1"
        assertNull(store.secretFields("db", "p1"), "a gone session's loan is dropped")
        assertEquals(0, store.size())
        store.lend("s-2", "p1", lent); store.release("s-2")
        assertNull(store.secretFields("db", "p1"))
    }

    @Test
    fun `the git credential goes as a header through GIT_CONFIG env, never in plain text`() {
        val env = LentCredentials.gitEnv(GitCredential(dummyToken, "bot"))
        assertEquals("http.extraHeader", env["GIT_CONFIG_KEY_0"])
        val header = env.getValue("GIT_CONFIG_VALUE_0")
        assertTrue(header.startsWith("Authorization: Basic "))
        assertEquals("bot:$dummyToken", String(java.util.Base64.getDecoder().decode(header.removePrefix("Authorization: Basic "))))
        noValue(env.values.joinToString(), "the env (plain)")
        assertTrue(LentCredentials.gitEnv(null).isEmpty())
    }

    @Test
    fun `the orchestrator lends the harness key, user secrets and the git secret to an allowed runner`() {
        val secrets = mapOf(
            "db" to mapOf("value" to dummySecret), "git.bot" to mapOf("token" to dummyToken, "username" to "bot"),
            "harness.claude.key" to mapOf("value" to "never"), "z" to mapOf("value" to "3"),
        )
        fun lender(allow: Boolean, max: Int = 10) = CredentialLender(
            lendsTo = { allow && it == "r1" },
            harnessEnv = { id, _ -> if (id == "claude") mapOf("ANTHROPIC_API_KEY" to dummyKey, "BLANK" to "") else emptyMap() },
            harnessIds = { listOf("claude", "pi") },
            secretNames = { secrets.keys },
            secret = { n, _ -> secrets[n] },
            gitSecret = { "git.bot" },
            maxSecrets = { max },
        )
        val c = assertNotNull(lender(true).lend("r1", StartSpec("t:1", projectId = "p1", provider = "claude")))
        assertEquals(mapOf("ANTHROPIC_API_KEY" to dummyKey), c.env, "blank values are not lent")
        assertEquals(setOf("db", "z"), c.secrets.keys, "no built-in, and the git secret goes as git only")
        assertEquals(GitCredential(dummyToken, "bot"), c.git)
        assertEquals(setOf("db"), lender(true, max = 1).lend("r1", StartSpec("t:1", provider = "claude"))!!.secrets.keys)
        assertNull(lender(false).lend("r1", StartSpec("t:1", provider = "claude")), "a runner off runners.lendCredentials gets nothing")
        assertNull(lender(true).lend("r2", StartSpec("t:1", provider = "claude")))
        assertEquals(setOf("claude"), lender(true).lentKeys("r1"))
        assertEquals(emptySet(), lender(false).lentKeys("r1"))

        assertEquals(GitCredential("only"), CredentialLender.gitOf(mapOf("value" to "only")))
        assertNull(CredentialLender.gitOf(mapOf("a" to "1", "b" to "2")))
    }

    @Test
    fun `the pool lends every remote start and reports a lent harness signed in`() {
        var seen: StartSpec? = null
        val remote = object : RunnerLink {
            override fun ticketIdOf(sessionId: String): String? = null
            override fun health(): OrchestratorReply<RunnerHealth> = OrchestratorReply.Ok(RunnerHealth(RunnerHealth.UP))
            override fun hello(): OrchestratorReply<RunnerInfo> = OrchestratorReply.Ok(RunnerInfo("r1", F.CONTRACT, listOf(RunnerHarness("claude"), RunnerHarness("pi"))))
            override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> { seen = spec; return OrchestratorReply.Ok(SessionRef("s-1")) }
        }
        val local = object : RunnerLink { override fun ticketIdOf(sessionId: String): String? = null }
        val hub = RunnerHub(local, remotes = { listOf("r1") }, lend = { _, _ -> lent }, lentKeys = { setOf("claude") })
        hub.register("test", { _, _ -> remote })
        val link = assertNotNull(hub.link("r1"))
        link.startSession(StartSpec("t:1", provider = "claude"))
        assertEquals(lent, seen?.credentials)
        val own = SessionCredentials(env = mapOf("X_KEY" to "own"))
        link.startSession(StartSpec("t:2", credentials = own))
        assertEquals(own, seen?.credentials, "credentials a caller set are kept")
        val harnesses = (link.hello() as OrchestratorReply.Ok).value.harnesses
        assertTrue(harnesses.first { it.id == "claude" }.loggedIn)
        assertFalse(harnesses.first { it.id == "pi" }.loggedIn)
        hub.heartbeat(60_000L)
        assertEquals(RunnerHealth.UP, hub.runners().first { it.name == "r1" }.state, "a lent key makes the runner usable, not degraded")
    }

    /** The runner's side, end to end through its link server: env to the session, secrets to the store, nothing in the audit file. */
    private fun runnerStart(accept: Boolean, signedInHere: Boolean = false): Triple<StartSpec?, LentCredentials, File> {
        val runnerDir = File(dir, "runner-$accept-$signedInHere").also { it.mkdirs() }
        var started: StartSpec? = null
        val local = LocalRunnerLink(LocalRunnerServices(
            ticketIdOf = { null }, info = { RunnerInfo("r1", F.CONTRACT, listOf(RunnerHarness("claude", loggedIn = signedInHere))) },
            start = { spec: StartSpec -> started = spec; "s-1" },
            stop = { _, _, _ -> true }, deliver = { _, _ -> MailResult(MailResult.DELIVERED) }, health = { RunnerHealth(RunnerHealth.UP) },
        ))
        val audit = RunnerAudit(File(runnerDir, RunnerAudit.FILE)) { now }
        val codes = PairCodes(File(runnerDir, PairCodes.ATTEMPTS_FILE), expiryMs = { 10 * 60_000L }, attemptsPerHour = { 5 })
        val store = LentCredentials()
        val server = RunnerLinkServer(
            RunnerLinkStore(LinkVault.InMemory()), audit, codes,
            settings = { RunnerLinkSettings.Values(projects = listOf("*"), harnesses = listOf("*"), acceptCredentials = accept) },
            runner = { local }, clock = { now }, lent = store,
        )
        val orchestratorStore = RunnerLinkStore(LinkVault.InMemory())
        val credential = RunnerPeers(orchestratorStore, LinkCall { _, _, id, c, type, data, _ -> server.handle(type, data, id, c, "peer") }, clock = { now })
            .add("r1", "wss://h:1", codes.issue("r1", now), "AA", "orch").credential
        val request = F.StartRequest(startId = "t:1", projectId = "p1", provider = "claude", credentials = lent)
        val answer = server.handle(F.START, F.json.encodeToString(F.StartRequest.serializer(), request), orchestratorStore.selfId(), credential)
        assertTrue(answer.ok, answer.data)
        noValue(answer.data, "the start answer")
        return Triple(started, store, runnerDir)
    }

    @Test
    fun `a runner start hands the key to the session, keeps the secrets in memory and writes no value`() {
        val (spec, store, runnerDir) = runnerStart(accept = true)
        assertEquals(mapOf("ANTHROPIC_API_KEY" to dummyKey), spec?.credentials?.env)
        assertTrue(spec?.credentials?.secrets.orEmpty().isEmpty() && spec?.credentials?.git == null, "the session gets the env only")
        assertEquals(mapOf("value" to dummySecret), store.secretFields("db", "p1"))
        val written = runnerDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(written.isNotEmpty(), "the audit file was written")
        written.forEach { noValue(it.readText(), it.name) }
        assertTrue(written.any { it.readText().contains("lent") }, "the audit names the loan")
    }

    @Test
    fun `runner acceptCredentials false ignores the loan`() {
        val (spec, store, _) = runnerStart(accept = false)
        assertNull(spec?.credentials)
        assertNull(store.secretFields("db", "p1"))
    }

    @Test
    fun `a harness signed in on the runner keeps its own login`() {
        val (spec, store, _) = runnerStart(accept = true, signedInHere = true)
        assertTrue(spec?.credentials?.env.orEmpty().isEmpty(), "the runner's own login wins: ${spec?.credentials}")
        assertEquals(mapOf("value" to dummySecret), store.secretFields("db", "p1"), "secrets are still lent")
    }
}
