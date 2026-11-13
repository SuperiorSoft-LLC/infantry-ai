// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-PAIR-1 (the owner's 2 steps): `docker run` prints an endpoint and a long-lived bearer; the app's Add runner takes just
 * those two. The bearer is reusable by the paired orchestrator (a re-add gets a fresh link credential), refused for any other
 * orchestrator while one is paired, refused once rotated, and its secret never shows in a toString or an error text.
 */
class RunnerBearerTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val dir: File = Files.createTempDirectory("runner-bearer-test").toFile()

    @Test
    fun `a bearer is made once, kept, rotated on demand, and checked`() {
        val b = RunnerBearer(File(dir, "bearer"))
        assertFalse(b.exists())
        val first = b.ensure("box")
        assertEquals("box", first.name)
        assertEquals(first, b.ensure(null), "a second start keeps the same bearer")
        assertEquals(first, RunnerBearer(File(dir, "bearer")).read(), "it is kept on disk (the state volume)")
        assertTrue(b.accepts(first.secret))
        assertFalse(b.accepts("x".repeat(32)))
        assertFalse(b.accepts(null))
        assertFalse(first.secret in first.toString(), "toString never shows the secret")

        val rotated = b.ensure(null, rotate = true)
        assertTrue(rotated.secret != first.secret)
        assertEquals("box", rotated.name, "rotate keeps the name")
        assertFalse(b.accepts(first.secret), "a rotated bearer refuses the old secret")
        assertTrue(b.accepts(rotated.secret))
        assertEquals(RunnerBearer.DEFAULT_NAME, RunnerBearer(File(dir, "other")).ensure(null).name)
        assertFailsWith<IllegalArgumentException> { RunnerBearer(File(dir, "bad")).ensure("Bad Name") }
    }

    @Test
    fun `the printed bearer carries the name and pin and parses back, and a bad paste reads as a fixed reason`() {
        val secret = "0123456789abcdefghjkmnpqrstvwxyz"
        val text = RunnerBearer.Token("box", FP, secret).text()
        assertEquals("irb1.box.$FP.$secret", text)
        val back = assertIs<RunnerBearer.Token.Parsed.Ok>(RunnerBearer.Token.parse("  '$text' ")).token
        assertEquals("box", back.name)
        assertEquals(FP, back.fingerprint)
        assertEquals(secret, back.secret)
        assertFalse(secret in back.toString())
        val plain = assertIs<RunnerBearer.Token.Parsed.Ok>(RunnerBearer.Token.parse("irb1.box.plain.$secret")).token
        assertTrue(plain.plain)

        for (bad in listOf("ABCD-EFGH-JKLM-NPQR", "irb1.box.$FP", "irb1.Bad Name.$FP.$secret", "irb1.box.zz.$secret", "irb1.box.$FP.${secret.dropLast(1)}")) {
            val reason = assertIs<RunnerBearer.Token.Parsed.Bad>(RunnerBearer.Token.parse(bad), bad).reason
            assertFalse(secret.dropLast(1) in reason, "the reason never repeats the paste: $reason")
        }
    }

    @Test
    fun `the endpoint is read as the runner printed it or as the owner shortens it`() {
        fun hp(t: String) = RunnerBearer.Endpoint.parse(t, 8790).let { it.host to it.port }
        assertEquals("203.0.113.5" to 8790, hp("wss://203.0.113.5:8790"))
        assertEquals("box.tail.ts.net" to 18921, hp(" ws://box.tail.ts.net:18921/ "))
        assertEquals("localhost" to 18921, hp("localhost:18921"))
        assertEquals("example.com" to 8790, hp("example.com"))
        assertEquals("[fd00::1]" to 8790, hp("wss://[fd00::1]:8790"))
        for (bad in listOf("", "ftp://h:1", "wss://:8790", "h:99999", "a b:1")) {
            val p = RunnerBearer.Endpoint.parse(bad, 8790)
            assertNull(p.host, bad)
            assertNotNull(p.reason, bad)
        }
    }

    // --- the runner's PAIR answer with a bearer ---------------------------------------------------------------------------------

    private var now = 1_000_000L
    private val runnerStore = RunnerLinkStore(LinkVault.InMemory())
    private val runnerBearer = RunnerBearer(File(dir, "srv-bearer"))
    private val local = object : RunnerLink {
        override fun ticketIdOf(sessionId: String): String? = null
        override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> = OrchestratorReply.Ok(SessionRef("s-1"))
        override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> = OrchestratorReply.Ok(true)
    }
    private val server = RunnerLinkServer(
        runnerStore, RunnerAudit(File(dir, RunnerAudit.FILE)) { now }, codes = null,
        settings = { RunnerLinkSettings.Values(projects = listOf("*"), harnesses = listOf("*")) }, runner = { local }, clock = { now },
        bearer = runnerBearer, log = { logged += it },
    )
    private val logged = mutableListOf<String>()
    private val direct = LinkCall { _, _, id, cred, type, data, _ -> server.handle(type, data, id, cred, PEER) }
    private fun orchestrator(store: RunnerLinkStore = RunnerLinkStore(LinkVault.InMemory())) = store to RunnerPeers(store, direct, clock = { now })

    @Test
    fun `the bearer pairs, the same orchestrator re-adds with it, another takes it over, a rotated one is refused`() {
        val secret = runnerBearer.ensure("box").secret
        val (store, peers) = orchestrator()
        val first = peers.add("box", "wss://h:1", secret, FP, "orch")
        assertEquals(store.selfId(), runnerStore.orchestrator()?.id)
        assertEquals("box", runnerStore.orchestrator()?.runnerName)
        assertTrue(server.handle(F.HELLO, "{}", store.selfId(), first.credential).ok)

        // Long-lived: the same orchestrator pastes it again (a reinstall, a second Add) and gets a fresh credential.
        val again = RunnerPeers(store, direct, clock = { now }).add("box", "wss://h:1", secret, FP, "orch", replaceSameRunner = true)
        assertTrue(again.credential != first.credential)
        assertTrue(server.handle(F.HELLO, "{}", store.selfId(), again.credential).ok)

        // RUNNER-REPAIR-1: holding the live bearer is the owner's authority: another orchestrator takes the runner over (a new app
        // after a reinstall, a recreated container keeping its volume). The old link's credential stops working, the answer says so.
        val other = server.handle(F.PAIR, pairData(secret, id = "inf-second"), null, null)
        assertTrue(other.ok, other.data)
        val taken = F.json.decodeFromString(F.PairAnswer.serializer(), other.data)
        assertEquals("orch", taken.replacedOrchestrator)
        assertEquals("inf-second", runnerStore.orchestrator()?.id)
        assertFalse(server.handle(F.HELLO, "{}", store.selfId(), again.credential).ok, "the replaced orchestrator's credential is refused")
        assertTrue(server.handle(F.HELLO, "{}", "inf-second", taken.credential).ok)
        assertTrue(logged.any { "replaces orch" in it }, logged.toString())
        assertFalse(logged.any { secret in it }, "the log never carries the bearer")

        // A one-time code never moves a runner paired with another orchestrator: only the bearer does.
        val (_, other2) = orchestrator()
        val coded = assertFailsWith<LinkFailure> { other2.add("box", "wss://h:1", "nope-not-a-code-xxxxxxxxxxxxxxxx", FP, "third") }
        assertTrue("inf-second" == runnerStore.orchestrator()?.id, coded.message)

        // A wrong or rotated bearer is refused with the fixed text, never the presented value.
        runnerStore.unpair()
        val wrong = server.handle(F.PAIR, pairData("x".repeat(32)), null, null)
        assertFalse(wrong.ok)
        assertTrue(wrong.data.contains(RunnerLinkServer.WRONG_BEARER.substringBefore(':')), wrong.data)
        runnerBearer.ensure(null, rotate = true)
        val stale = assertFailsWith<LinkFailure> { orchestrator().second.add("box", "wss://h:1", secret, FP, "orch") }
        assertFalse(secret in stale.message.orEmpty(), "the refusal never repeats the bearer")
        assertNull(runnerStore.orchestrator())
    }

    private fun pairData(code: String, id: String = "inf-other") =
        F.json.encodeToString(F.PairRequest.serializer(), F.PairRequest(code = code, orchestratorId = id, orchestratorName = "o"))

    // --- the 2 steps through runner_admin (runner: op bearer; orchestrator: op add with endpoint + bearer) --------------------------

    /** An in-process transport keyed by port, which checks the pin the way the real TLS client does. */
    private class PortTransport : RunnerLinkTransport {
        val served = HashMap<Int, LinkHandler>()
        override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer {
            val port = url.substringAfterLast(':').toInt()
            val h = served[port] ?: throw LinkFailure("cannot reach the runner link at $url: java.net.ConnectException")
            if (fingerprint?.let(RunnerPairLink::normalizeFingerprint) != FP) throw LinkFailure("fingerprint mismatch", presentedFingerprint = FP)
            return h.handle(type, data, orchestratorId, credential, PEER)
        }
        override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing {
            if (port in served) throw java.net.BindException("Address already in use: bind")
            served[port] = handler
            val owner = this
            return object : LinkServing {
                override val port: Int = port
                override fun close() { if (owner.served[port] === handler) owner.served.remove(port) }
            }
        }
        override fun fingerprint(tls: LinkTlsMaterial): String = FP
    }

    private var settings = RunnerLinkSettings.Values()

    private fun service(stateDir: File, transport: RunnerLinkTransport) =
        RunnerLinkService(stateDir, { it.default }, { settings }, null, { transport }, host = "127.0.0.1", log = {})

    private fun req(vararg fields: Pair<String, String?>) =
        JsonObject(fields.filter { it.second != null }.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `step 1 prints endpoint and bearer and serves at once, step 2 pairs from just those two`() {
        val transport = PortTransport()
        val runner = service(File(dir, "svc-runner"), transport)
        val granted = mutableMapOf<String, List<String>>()
        runner.grant = { key, ids -> granted[key] = ids; settings = if (key == RunnerLinkSettings.PROJECTS) settings.copy(projects = ids) else settings.copy(harnesses = ids) }
        val orch = service(File(dir, "svc-orch"), transport)

        val printed = runner.admin(req("op" to "bearer", "name" to "box", "hosts" to "203.0.113.5"))
        assertEquals("true", RunnerLinkService.field(printed, "ok"), printed.toString())
        val port = transport.served.keys.single()
        assertEquals("wss://203.0.113.5:$port", RunnerLinkService.field(printed, "endpoint"))
        assertEquals("true", RunnerLinkService.field(printed, "serving"), "the link is served unpaired, so the bearer works at once")
        assertEquals(listOf("*"), granted[RunnerLinkSettings.PROJECTS], "empty grants become every project")
        assertEquals(listOf("*"), granted[RunnerLinkSettings.HARNESSES], "and every harness")
        assertEquals(JsonArray(listOf(JsonPrimitive("*"))), printed["projects"])
        val bearer = RunnerLinkService.field(printed, "bearer")!!
        assertTrue(bearer.startsWith("irb1.box.$FP."), "the bearer carries the name and the pin")

        // Printing it again (every container start) gives the same bearer.
        assertEquals(bearer, RunnerLinkService.field(runner.admin(req("op" to "bearer")), "bearer"))

        // Step 2: the orchestrator reaches the runner at the endpoint the owner pastes (here the published port on localhost).
        val added = orch.admin(req("op" to "add", "endpoint" to "localhost:$port", "bearer" to bearer, "orchestratorName" to "pc"))
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
        assertEquals("box", RunnerLinkService.field(added, "name"))
        assertEquals("paired", RunnerLinkService.field(runner.admin(req("op" to "pair_status")), "state"))
        assertNotNull(RunnerLinkStore.open(File(dir, "svc-orch")).runner("box"))
        assertFalse(bearer.substringAfterLast('.') in added.toString(), "the answer never carries the bearer's secret")

        // A narrowed grant is kept on the next print.
        settings = settings.copy(projects = listOf("p1"))
        granted.clear()
        runner.admin(req("op" to "bearer"))
        assertNull(granted[RunnerLinkSettings.PROJECTS], "a list the owner narrowed is not widened")

        // RUNNER-REPAIR-1: a second app (a reinstall, a new state dir) pastes the same endpoint and bearer: it takes the runner
        // over and is told so; the first app's check then says the runner moved and how to bring it back, not "update it".
        val orch2 = service(File(dir, "svc-orch2"), transport)
        val moved = orch2.admin(req("op" to "add", "endpoint" to "localhost:$port", "bearer" to bearer, "orchestratorName" to "laptop"))
        assertEquals("true", RunnerLinkService.field(moved, "ok"), moved.toString())
        assertEquals("pc", RunnerLinkService.field(moved, "replaced"))
        assertEquals("laptop", RunnerLinkStore.open(File(dir, "svc-runner")).orchestrator()?.name)
        val stale = orch.admin(req("op" to "check", "name" to "box")).toString()
        assertTrue("no longer paired with this app" in stale && "Add runner again" in stale, stale)
        assertFalse("too old" in stale, stale)
        runner.close(); orch.close(); orch2.close()
    }

    /**
     * RUNNER-BEARER-STALE-1 + RUNNER-ENDPOINT-1 (owner, 2026-11-12): `docker logs` gave the right endpoint with a stale bearer, the
     * exec gave the live bearer with the container's bridge address. Now a bare `infantry runner bearer` (no flags, as the owner runs
     * it in the container) reads the docker run env, and the live bearer is printed again on every rotation and pairing.
     */
    @Test
    fun `a bare bearer print uses the docker run env, and a rotation or a pairing prints the live bearer again`() {
        val transport = PortTransport()
        val env = mapOf(RunnerLinkService.ENV_HOST to "localhost", RunnerLinkService.ENV_PUBLIC_PORT to "18790")
        val console = mutableListOf<String>()
        val runner = RunnerLinkService(
            File(dir, "env-runner"), { it.default }, { settings }, null, { transport }, host = "127.0.0.1", log = {},
            announce = { console += it }, env = { env[it] },
        )
        runner.grant = { key, ids -> settings = if (key == RunnerLinkSettings.PROJECTS) settings.copy(projects = ids) else settings.copy(harnesses = ids) }
        val orch = service(File(dir, "env-orch"), transport)

        val first = runner.admin(req("op" to "bearer", "name" to "box"))
        assertEquals("wss://localhost:18790", RunnerLinkService.field(first, "endpoint"), "the env, not this machine's own address")
        assertEquals("false", RunnerLinkService.field(first, "hostGuessed"))
        assertTrue(console.isEmpty(), "the first bearer is printed by the start banner, not twice")
        val exec = runner.admin(req("op" to "bearer"))
        assertEquals(RunnerLinkService.field(first, "endpoint"), RunnerLinkService.field(exec, "endpoint"), "exec and start banner agree")

        // A rotation: the newest block in the console holds the live bearer.
        val rotated = runner.admin(req("op" to "bearer", "rotate" to "true"))
        val live = RunnerLinkService.field(rotated, "bearer")!!
        assertTrue(live != RunnerLinkService.field(first, "bearer"))
        assertTrue(console.any { it == "bearer:   $live" }, console.joinToString(" | "))
        assertTrue(console.any { it == "endpoint: wss://localhost:18790" }, console.joinToString(" | "))

        // A pairing with the live bearer: the block is printed again with the current 'last paired with'.
        console.clear()
        val port = transport.served.keys.single()
        val added = orch.admin(req("op" to "add", "endpoint" to "localhost:$port", "bearer" to live, "orchestratorName" to "pc"))
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
        assertTrue(console.any { it == "bearer:   $live" }, console.joinToString(" | "))
        assertTrue(console.any { it.startsWith("last paired with orchestrator pc;") }, console.joinToString(" | "))
        runner.close(); orch.close()
    }

    @Test
    fun `a wrong pin or an unreachable endpoint fails with the reason and the next step, never the secret`() {
        val transport = PortTransport()
        val runner = service(File(dir, "e-runner"), transport)
        val orch = service(File(dir, "e-orch"), transport)
        val bearer = RunnerLinkService.field(runner.admin(req("op" to "bearer", "name" to "box")), "bearer")!!
        val secret = bearer.substringAfterLast('.')
        val port = transport.served.keys.single()

        val forged = bearer.replace(FP, "cd".repeat(32))
        val pinned = orch.admin(req("op" to "add", "endpoint" to "localhost:$port", "bearer" to forged))
        assertEquals("false", RunnerLinkService.field(pinned, "ok"))
        assertFalse(secret in pinned.toString())

        val far = orch.admin(req("op" to "add", "endpoint" to "localhost:${port + 1}", "bearer" to bearer))
        assertEquals("false", RunnerLinkService.field(far, "ok"))
        val why = RunnerLinkService.field(far, "error")!!
        assertTrue("-p " in why && "reachable" in why, why)
        assertFalse(secret in why)

        val notBearer = orch.admin(req("op" to "add", "endpoint" to "localhost:$port", "bearer" to "ABCD-EFGH"))
        assertEquals("false", RunnerLinkService.field(notBearer, "ok"))
        assertNull(RunnerLinkStore.open(File(dir, "e-orch")).runner("box"))
        runner.close(); orch.close()
    }

    @Test
    fun `an old runner's detach refusal is explained as a stale image`() {
        val old = "runner_link_pair refused: this runner is paired with another orchestrator (inf-x); run `infantry runner detach` on the runner first"
        assertTrue(RunnerPeers.staleHint(old).contains("older than this app"))
        assertEquals("", RunnerPeers.staleHint("runner_link_pair refused: wrong bearer"))
    }

    private companion object {
        const val PEER = "100.64.0.9"
        val FP = "ab".repeat(32)
    }
}
