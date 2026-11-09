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
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-GRANT-AT-PAIR-1 (docs/wiki/RunnerPairing-design.md, step 7; owner decision D24): a code issued on the runner console with
 * `--grant-on-pair` may grant the project it is added from, ONCE, at pairing, by ADDING ids to `runner.projects` /
 * `runner.harnesses`. Everything else stays as it was: a code without the grant grants nothing, `*` is refused, harnesses must be
 * installed and inside the console's ceiling, a used code grants nothing, and no link frame changes grants after pairing.
 */
class RunnerGrantAtPairTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private var now = 1_000_000L
    private val dir: File = Files.createTempDirectory("runner-grant-test").toFile()
    private val store = RunnerLinkStore(LinkVault.InMemory())
    private val audit = RunnerAudit(File(dir, RunnerAudit.FILE)) { now }
    private val codes = PairCodes(File(dir, PairCodes.ATTEMPTS_FILE), expiryMs = { 10 * 60_000L }, attemptsPerHour = { 5 })

    /** The runner's own settings and what its console says is installed here. */
    private var settings = RunnerLinkSettings.Values()
    private var installed = listOf("claude", "echo")

    /** What a `--grant-on-pair` code does: ADD ids to the runner's own allow-lists (`RunnerLinkService`'s grant-add seam). */
    private val grantAdd: (String, List<String>) -> Unit = { key, ids ->
        settings = if (key == RunnerLinkSettings.PROJECTS) settings.copy(projects = (settings.projects + ids).distinct())
        else settings.copy(harnesses = (settings.harnesses + ids).distinct())
    }

    private val server = RunnerLinkServer(
        store, audit, codes, settings = { settings }, runner = { null }, clock = { now },
        installedHarnesses = { installed }, grantAdd = grantAdd,
    )

    private fun pairData(
        code: String,
        projects: List<String> = emptyList(),
        harnesses: List<String> = emptyList(),
        id: String = "inf-orchestrator",
    ): String = F.json.encodeToString(
        F.PairRequest.serializer(),
        F.PairRequest(code = code, orchestratorId = id, orchestratorName = "pc", grantProjects = projects, grantHarnesses = harnesses),
    )

    private fun answer(data: String): RunnerLinkServer.Answer = server.handle(F.PAIR, data, null, null, "10.0.0.9")

    private fun pairAnswer(a: RunnerLinkServer.Answer): F.PairAnswer {
        assertTrue(a.ok, a.data)
        return F.json.decodeFromString(F.PairAnswer.serializer(), a.data)
    }

    private fun audits(op: String): List<RunnerAudit.Entry> = audit.tail(50).filter { it.op == op }

    @Test
    fun `a code without the grant carries no authority and says so`() {
        settings = RunnerLinkSettings.Values(projects = listOf("p1"), harnesses = listOf("claude"))
        val issued = codes.issue("box", now) // no --grant-on-pair
        val a = pairAnswer(answer(pairData(issued, projects = listOf("p2"), harnesses = listOf("echo"))))

        assertEquals(emptyList(), a.grantedProjects, "nothing is granted by a code the console did not mark")
        assertEquals(emptyList(), a.grantedHarnesses)
        assertTrue(a.grantRefusal.orEmpty().contains("grant not allowed by this code"), a.grantRefusal.orEmpty())
        assertEquals(listOf("p1"), settings.projects, "the runner's own allow-list is untouched")
        assertEquals(listOf("claude"), settings.harnesses)
        assertTrue(audits(RunnerLinkServer.PAIR_GRANT).isEmpty(), "no grant line when nothing was granted")
        assertTrue(store.orchestrator() != null, "the pairing itself still happened, exactly as before")
    }

    @Test
    fun `a grant-flagged code grants the asked project and harnesses, adding to what is already there`() {
        settings = RunnerLinkSettings.Values(projects = listOf("p1"), harnesses = listOf("claude"))
        val a = pairAnswer(answer(pairData(codes.issue("box", now, grant = true), projects = listOf("p2"), harnesses = listOf("claude", "echo"))))

        assertEquals(listOf("p2"), a.grantedProjects)
        assertEquals(listOf("echo"), a.grantedHarnesses, "an id already allowed is not granted twice")
        assertNull(a.grantRefusal)
        assertEquals(listOf("p1", "p2"), settings.projects, "an add, never a replace")
        assertEquals(listOf("claude", "echo"), settings.harnesses)
        val line = audits(RunnerLinkServer.PAIR_GRANT).single()
        assertTrue(line.result.contains("projects=[p2]"), line.result)
        assertTrue(line.result.contains("harnesses=[echo]"), line.result)
    }

    @Test
    fun `a star in the request is refused and does not burn the code`() {
        val issued = codes.issue("box", now, grant = true)
        val refused = answer(pairData(issued, projects = listOf("*")))
        assertFalse(refused.ok)
        assertTrue(refused.data.contains("may not name '*'"), refused.data)
        assertNull(store.orchestrator(), "a refused request pairs nothing")
        assertTrue(settings.projects.isEmpty())

        // The code is still live: the same owner, asking for real ids, pairs and gets them.
        val a = pairAnswer(answer(pairData(issued, projects = listOf("p1"))))
        assertEquals(listOf("p1"), a.grantedProjects)
        assertEquals(listOf("p1"), settings.projects)
    }

    @Test
    fun `more projects than limits runners grantProjectsMax is refused before the code is spent`() {
        val issued = codes.issue("box", now, grant = true)
        val refused = answer(pairData(issued, projects = listOf("p1", "p2")))
        assertFalse(refused.ok)
        assertTrue(refused.data.contains("limits.runners.grantProjectsMax"), refused.data)
        assertTrue(settings.projects.isEmpty())

        val a = pairAnswer(answer(pairData(issued, projects = listOf("p1"))))
        assertEquals(listOf("p1"), a.grantedProjects)
    }

    @Test
    fun `the console's harness ceiling bounds what a grant can add`() {
        // The owner said `--grant-harnesses-max echo`: claude is installed here, but not inside the ceiling.
        val a = pairAnswer(
            answer(
                pairData(codes.issue("box", now, grant = true, harnessCeiling = listOf("echo")), projects = listOf("p1"), harnesses = listOf("claude", "echo")),
            ),
        )
        assertEquals(listOf("echo"), a.grantedHarnesses)
        assertEquals(listOf("echo"), settings.harnesses)
    }

    @Test
    fun `a harness that is not installed here is never granted`() {
        installed = listOf("echo")
        val a = pairAnswer(answer(pairData(codes.issue("box", now, grant = true), projects = listOf("p1"), harnesses = listOf("claude", "echo"))))
        assertEquals(listOf("echo"), a.grantedHarnesses)
        assertEquals(listOf("echo"), settings.harnesses)
    }

    @Test
    fun `a replayed code grants nothing`() {
        val issued = codes.issue("box", now, grant = true)
        pairAnswer(answer(pairData(issued, projects = listOf("p1"))))
        assertEquals(listOf("p1"), settings.projects)
        store.unpair() // as a detach on either side would leave it
        installed += "codex"

        val replay = answer(pairData(issued, projects = listOf("p9"), harnesses = listOf("codex")))
        assertFalse(replay.ok)
        assertTrue(replay.data.contains("refused"), replay.data)
        assertEquals(listOf("p1"), settings.projects, "a spent code cannot grant again")
        assertTrue("codex" !in settings.harnesses)
    }

    @Test
    fun `no frame after pairing changes grants`() {
        val paired = pairAnswer(answer(pairData(codes.issue("box", now, grant = true), projects = listOf("p1"), harnesses = listOf("echo"))))
        // A second PAIR with the live credential (the shell of a re-grant) is refused outright...
        val again = server.handle(F.PAIR, pairData("", projects = listOf("p1", "p2")), store.orchestrator()?.id, paired.credential)
        assertFalse(again.ok)
        assertTrue(again.data.contains("already paired"), again.data)
        // ...and the read frames a paired orchestrator may send leave the allow-lists exactly as they were.
        assertTrue(server.handle(F.HELLO, "{}", store.orchestrator()?.id, paired.credential).ok)
        server.handle(F.CHECK, "{}", store.orchestrator()?.id, paired.credential)
        assertEquals(listOf("p1"), settings.projects)
        assertEquals(listOf("echo"), settings.harnesses)
    }

    @Test
    fun `an id that is not an id is refused`() {
        val refused = answer(pairData(codes.issue("box", now, grant = true), projects = listOf("a project id with spaces")))
        assertFalse(refused.ok)
        assertTrue(refused.data.contains("is not an id"), refused.data)
    }

    /**
     * The orchestrator side: the grant travels in the PAIR frame only when the link says `grant=1` AND the owner confirmed, and the
     * answer's granted ids come back in [AddOutcome].
     */
    @Test
    fun `the client sends the grant only for a grant-carrying link the owner confirmed`() {
        val sending = mutableListOf<F.PairRequest>()
        val link = LinkCall { _, _, id, cred, type, data, _ ->
            val request = F.json.decodeFromString(F.PairRequest.serializer(), data)
            sending += request
            server.handle(type, data, id, cred, "10.0.0.9")
        }
        /** A fresh client per case: each pairing pairs one orchestrator, and one store is one orchestrator. */
        fun peers() = RunnerPeers(RunnerLinkStore(LinkVault.InMemory()), link, clock = { now })
        /** A fresh live code (each pairing spends one) in a link that says whether the code carries the grant. */
        fun freshLink(grant: Boolean) = RunnerPairLink(
            name = "t1", hosts = listOf("127.0.0.1"), port = 8790, code = codes.issue("t1", now, grant = grant),
            fingerprint = "abababababababababababababababababababababababababababababababab", grant = grant,
        )

        // 1. A grant link, the owner confirmed: the project and the harnesses travel with the code.
        val confirmed = peers().addLink(freshLink(grant = true), "t1", "pc", allowPlain = false, PairGrants(listOf("p1"), listOf("echo")))
        assertEquals(listOf("p1"), sending.last().grantProjects)
        assertEquals(listOf("echo"), sending.last().grantHarnesses)
        assertEquals(listOf("p1"), confirmed.grantedProjects)
        assertEquals(listOf("echo"), confirmed.grantedHarnesses)
        store.unpair()

        // 2. The same link, no confirmation: the pairing happens, and nothing is asked for.
        val plain = peers().addLink(freshLink(grant = true), "t1", "pc", allowPlain = false, PairGrants(emptyList(), emptyList()))
        assertEquals(emptyList<String>(), sending.last().grantProjects)
        assertEquals(emptyList<String>(), plain.grantedProjects)
        store.unpair()

        // 3. A link with no grant=1 never carries a grant, even when one is asked for: the runner would refuse it anyway.
        val noAuthority = peers().addLink(freshLink(grant = false), "t1", "pc", allowPlain = false, PairGrants(listOf("p1"), emptyList()))
        assertEquals(emptyList<String>(), sending.last().grantProjects)
        assertEquals(emptyList<String>(), noAuthority.grantedProjects)
    }

    /** The pairing link carries `grant=1` exactly when the console issued the code with the grant, and never the code in `toString`. */
    @Test
    fun `the link says grant=1 only for a grant-carrying code`() {
        fun uri(grant: Boolean) = RunnerPairLink("t1", listOf("127.0.0.1"), 8790, "ABCD-EFGH-JKLM-NPQR", null, plain = true, grant = grant)
        assertTrue(uri(grant = true).toUri().contains("&grant=1"))
        assertFalse(uri(grant = false).toUri().contains("grant"))
        val parsed = (RunnerPairLink.parse(uri(grant = true).toUri()) as RunnerPairLink.Parsed.Ok).link
        assertTrue(parsed.grant)
        assertTrue(parsed.toString().contains("grant=true"))
        assertFalse(parsed.toString().contains("ABCD-EFGH-JKLM-NPQR"), "toString never prints the code")
    }

    /**
     * The runner's console polls `pair_status` every 500 ms while it waits. The grant reads the installed harnesses and writes the
     * settings BEFORE the answer, so the code is spent for a moment before the pairing is recorded: that poll used to read
     * "expired", close the pairing listener under the answer and lose a pairing the runner had in fact made. The poll must read
     * "waiting" and the orchestrator must get its answer.
     */
    @Test
    fun `a console poll during a slow pairing answer does not expire it`() {
        val stateDir = Files.createTempDirectory("runner-grant-service").toFile()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var servedHandler: LinkHandler? = null
        val transport = object : RunnerLinkTransport {
            override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer =
                throw LinkFailure("not used here")

            override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing {
                servedHandler = handler
                return object : LinkServing {
                    override val port: Int = 18990
                    override fun close() = Unit
                }
            }

            override fun fingerprint(tls: LinkTlsMaterial): String = "AA"
        }
        val hellos = java.util.concurrent.atomic.AtomicInteger()
        val local = object : RunnerLink {
            override fun ticketIdOf(sessionId: String): String? = null
            override fun hello(): OrchestratorReply<RunnerInfo> {
                // The `pair` op itself lists the installed harnesses; only the PAIR answer's own read is the slow one.
                if (hellos.incrementAndGet() > 1) {
                    started.countDown()
                    release.await()
                }
                return OrchestratorReply.Ok(RunnerInfo("box", F.CONTRACT, listOf(RunnerHarness("echo", loggedIn = true))))
            }
            override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> = OrchestratorReply.Ok(SessionRef("s-1"))
            override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> = OrchestratorReply.Ok(true)
        }
        val service = RunnerLinkService(
            stateDir, { it.default }, { settings }, local, transport = { transport }, clock = { now },
        )
        val paired = service.admin(JsonObject(mapOf("op" to JsonPrimitive("pair"), "name" to JsonPrimitive("t1"), "grantOnPair" to JsonPrimitive("true"))))
        assertEquals("true", RunnerLinkService.field(paired, "ok"))
        val code = RunnerLinkService.field(paired, "code")!!
        val h = servedHandler ?: error("the transport was not given a handler")

        // The PAIR runs on another thread and blocks in hello() (the installed harnesses); the console polls meanwhile.
        val answer = java.util.concurrent.atomic.AtomicReference<LinkAnswer?>(null)
        val worker = Thread { answer.set(h.handle(F.PAIR, pairData(code, projects = listOf("p1"), harnesses = listOf("echo")), null, null, "127.0.0.1")) }
        worker.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "the pairing should reach hello()")
        val polled = service.admin(JsonObject(mapOf("op" to JsonPrimitive("pair_status"))))
        assertEquals("waiting", RunnerLinkService.field(polled, "state"), "an answer in flight is not an expired code")
        release.countDown()
        worker.join(5_000)

        val paired2 = F.json.decodeFromString(F.PairAnswer.serializer(), answer.get()!!.data)
        assertEquals(listOf("p1"), paired2.grantedProjects, "the grant still arrives with the answer")
        assertEquals(listOf("echo"), paired2.grantedHarnesses)
        assertEquals("paired", RunnerLinkService.field(service.admin(JsonObject(mapOf("op" to JsonPrimitive("pair_status")))), "state"))
        service.close()
    }

    /** The wire defaults keep an older peer working: a request with the old fields decodes, and an answer without grants decodes. */
    @Test
    fun `an older peer's frames still decode`() {
        val old = F.json.decodeFromString(F.PairRequest.serializer(), """{"code":"ABCD-EFGH-JKLM-NPQR","orchestratorId":"inf-1"}""")
        assertEquals(emptyList(), old.grantProjects)
        assertEquals(emptyList(), old.grantHarnesses)
        val oldAnswer = F.json.decodeFromString(F.PairAnswer.serializer(), """{"runnerId":"r","runnerName":"t1","credential":"c","contract":"1.5.0"}""")
        assertEquals(emptyList(), oldAnswer.grantedProjects)
        assertNull(oldAnswer.grantRefusal)
        assertEquals("1.6.0", RunnerLinkWire.CONTRACT, "the grant at pairing is contract 1.6.0")
    }
}
