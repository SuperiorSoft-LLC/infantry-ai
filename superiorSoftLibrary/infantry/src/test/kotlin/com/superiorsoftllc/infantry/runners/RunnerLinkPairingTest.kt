// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.FrameGate
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
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/** RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): pairing, the runner-scoped credential, the pin, rotation, detach, audit. */
class RunnerLinkPairingTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private var now = 1_000_000L
    private val dir: File = Files.createTempDirectory("runner-link-test").toFile()
    private val runnerStore = RunnerLinkStore(LinkVault.InMemory())
    private val audit = RunnerAudit(File(dir, RunnerAudit.FILE)) { now }
    private val codes = PairCodes(File(dir, PairCodes.ATTEMPTS_FILE), expiryMs = { 10 * 60_000L }, attemptsPerHour = { 5 })
    private var settings = RunnerLinkSettings.Values(projects = listOf("p1"), harnesses = listOf("claude"))
    private val starts = mutableListOf<StartSpec>()
    private val local = object : RunnerLink {
        override fun ticketIdOf(sessionId: String): String? = null
        override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> = OrchestratorReply.Ok(SessionRef("s-1")).also { starts += spec }
        override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> = OrchestratorReply.Ok(true)
    }
    private val server = RunnerLinkServer(runnerStore, audit, codes, settings = { settings }, runner = { local }, clock = { now })

    /** The orchestrator side reaching [server] in process (no transport). */
    private val direct = LinkCall { _, _, id, cred, type, data -> server.handle(type, data, id, cred, PEER) }

    private fun orchestrator(store: RunnerLinkStore = RunnerLinkStore(LinkVault.InMemory())) = store to RunnerPeers(store, direct) { now }

    private fun startData(project: String? = "p1", harness: String? = "claude") =
        F.json.encodeToString(F.StartRequest.serializer(), F.StartRequest(startId = "st", projectId = project, provider = harness, ticketId = "t-1"))

    @Test
    fun `a code pairs once and a reused code is refused`() {
        val code = codes.issue("box", now)
        val (store, peers) = orchestrator()
        val paired = peers.add("box", "wss://h:1", code, "AA", "orch")
        assertEquals(runnerStore.selfId(), paired.runnerId)
        assertEquals(store.selfId(), runnerStore.orchestrator()?.id)
        assertEquals("box", runnerStore.orchestrator()?.runnerName)

        runnerStore.unpair()
        val again = assertFailsWith<LinkFailure> { orchestrator().second.add("box", "wss://h:1", code, "AA", "orch") }
        assertTrue(again.message!!.contains("refused"), again.message)
        assertNull(runnerStore.orchestrator())
    }

    @Test
    fun `an expired code is refused`() {
        val code = codes.issue("box", now)
        now += 10 * 60_000L + 1
        val e = assertFailsWith<LinkFailure> { orchestrator().second.add("box", "wss://h:1", code, "AA", "orch") }
        assertTrue(e.message!!.contains("expired"), e.message)
        assertNull(runnerStore.orchestrator())
    }

    @Test
    fun `attempts past the hourly limit are refused even with the right code`() {
        val code = codes.issue("box", now)
        repeat(5) { assertFalse(server.handle(F.PAIR, pairData("WRONG-CODE"), null, null).ok) }
        assertFalse(server.handle(F.PAIR, pairData(code), null, null).ok)
        assertNull(runnerStore.orchestrator())
    }

    private fun pairData(code: String, id: String = "inf-other") =
        F.json.encodeToString(F.PairRequest.serializer(), F.PairRequest(code = code, orchestratorId = id, orchestratorName = "o"))

    @Test
    fun `a second orchestrator is refused`() {
        orchestrator().second.add("box", "wss://h:1", codes.issue("box", now), "AA", "first")
        val second = server.handle(F.PAIR, pairData(codes.issue("box", now), id = "inf-second"), null, null)
        assertFalse(second.ok)
        assertTrue(second.data.contains("another orchestrator"), second.data)
        // A forged id with no credential reaches nothing either.
        assertFalse(server.handle(F.HELLO, "{}", "inf-second", "made-up").ok)
    }

    @Test
    fun `the runner credential is refused on owner frames and works only for the link family`() {
        val (store, peers) = orchestrator()
        val r = peers.add("box", "wss://h:1", codes.issue("box", now), "AA", "orch")
        assertTrue(server.handle(F.HELLO, "{}", store.selfId(), r.credential).ok)
        for (owner in listOf(Envelope.SETTINGS_SET, Envelope.SETTINGS_GET, "plugin_install", "keystore_set", "update_apply", "remote_on")) {
            val answer = server.handle(owner, "{}", store.selfId(), r.credential)
            assertFalse(answer.ok, owner)
        }
        val seat = FrameGate.orchestratorSeat(store.selfId())
        assertNotNull(FrameGate.refusal(Envelope.SETTINGS_SET, seat))
        assertNull(FrameGate.refusal(F.START, seat))
        // A link frame is never a caller's frame on the owner or an agent seat.
        assertNotNull(FrameGate.refusal(F.START, FrameGate.OWNER_SEAT))
        assertNotNull(FrameGate.refusal(F.START, "agent:s-9"))
        // The credential is none of the other tokens: it is unique per pair and only its hash is kept on the runner.
        assertFalse(runnerStore.orchestrator()!!.credentialHash.contains(r.credential))
    }

    @Test
    fun `a rotated credential works and the old one fails`() {
        val (store, peers) = orchestrator()
        val old = peers.add("box", "wss://h:1", codes.issue("box", now), "AA", "orch").credential
        now += 31L * 86_400_000L
        assertEquals(listOf("runner 'box': credential rotated"), peers.rotateDue(30))
        val fresh = store.runner("box")!!.credential
        assertTrue(fresh != old)
        assertTrue(server.handle(F.HELLO, "{}", store.selfId(), fresh).ok)
        assertFalse(server.handle(F.HELLO, "{}", store.selfId(), old).ok)
        assertEquals(emptyList(), peers.rotateDue(30))
    }

    @Test
    fun `detach from the orchestrator revokes on both sides`() {
        val (store, peers) = orchestrator()
        val cred = peers.add("box", "wss://h:1", codes.issue("box", now), "AA", "orch").credential
        assertEquals("the runner revoked it too", peers.detach("box"))
        assertNull(store.runner("box"))
        assertNull(runnerStore.orchestrator())
        assertFalse(server.handle(F.HELLO, "{}", store.selfId(), cred).ok)
    }

    @Test
    fun `detach on the runner revokes the credential`() {
        val (store, peers) = orchestrator()
        val cred = peers.add("box", "wss://h:1", codes.issue("box", now), "AA", "orch").credential
        assertTrue(runnerStore.unpair())
        assertFalse(server.handle(F.HELLO, "{}", store.selfId(), cred).ok)
    }

    @Test
    fun `remote start passes the allow-lists, empty means none, and every operation is audited`() {
        val (store, peers) = orchestrator()
        val cred = peers.add("box", "wss://h:1", codes.issue("box", now), "AA", "orch").credential
        val id = store.selfId()
        assertTrue(server.handle(F.START, startData(), id, cred, PEER).ok)
        assertEquals(null, starts.single().repo)
        assertFalse(server.handle(F.START, startData(project = "p2"), id, cred, PEER).ok)
        assertFalse(server.handle(F.START, startData(harness = "codex"), id, cred, PEER).ok)
        settings = RunnerLinkSettings.Values()
        // RUNNER-DEFAULTS-1: a refusal names the missing setting and the command that grants it; the default stays "nothing".
        val refused = server.handle(F.START, startData(), id, cred, PEER)
        assertFalse(refused.ok)
        assertTrue(refused.data.contains("infantry settings set ${RunnerLinkSettings.PROJECTS}"), refused.data)
        settings = RunnerLinkSettings.Values(projects = listOf("p1"))
        assertTrue(server.handle(F.START, startData(), id, cred, PEER).data.contains("infantry settings set ${RunnerLinkSettings.HARNESSES}"))
        settings = RunnerLinkSettings.Values()
        assertFalse(server.handle(F.START, startData(), id, cred, PEER).ok)
        settings = RunnerLinkSettings.Values(projects = listOf("*"), harnesses = listOf("*"))
        assertTrue(server.handle(F.START, startData(project = "p2", harness = "codex"), id, cred, PEER).ok)
        assertEquals(2, starts.size)

        val log = audit.tail(50)
        assertTrue(log.any { it.op == F.PAIR && it.result == "ok" })
        assertTrue(log.any { it.op == F.START && it.ticket == "t-1" && it.result.startsWith("refused") && it.who == FrameGate.orchestratorSeat(id) }, log.toString())
        assertTrue(log.all { it.from == PEER }, log.toString())
    }

    // RUNNER-POOL-6a: the wss end-to-end and plain-ws tests moved with the transport to Infantry_RemoteRunner
    // (WssLinkTransportTest). Core is tested through the seam with an in-process transport.

    /** An in-process [RunnerLinkTransport]: [call] reaches the handler [serve] was given, no socket. */
    private class FakeTransport : RunnerLinkTransport {
        var handler: LinkHandler? = null
        override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer {
            if (fingerprint != FP) throw LinkFailure("fingerprint mismatch", presentedFingerprint = FP)
            return (handler ?: throw LinkFailure("nothing serves $url")).handle(type, data, orchestratorId, credential, PEER)
        }
        override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing {
            this.handler = handler
            val owner = this
            return object : LinkServing {
                override val port: Int = 1
                override fun close() { if (owner.handler === handler) owner.handler = null }
            }
        }
        override fun fingerprint(tls: LinkTlsMaterial): String = FP
    }

    private fun service(stateDir: File, transport: () -> RunnerLinkTransport?) =
        RunnerLinkService(stateDir, { it.default }, { settings }, null, transport, host = "127.0.0.1", log = {})

    private fun req(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `with no transport the link commands say they need the plugin`() {
        val svc = service(File(dir, "none")) { null }
        for (op in listOf("pair", "add", "rotate", "detach")) {
            val answer = svc.admin(req("op" to op, "name" to "box", "url" to "wss://h:1", "code" to "C"))
            assertEquals("false", RunnerLinkService.field(answer, "ok"), op)
            assertEquals(RunnerLinkService.NO_TRANSPORT, RunnerLinkService.field(answer, "error"), op)
        }
        assertTrue(RunnerLinkService.NO_TRANSPORT.startsWith("the runner link needs the Infantry_RemoteRunner plugin"))
        svc.tick()
        assertEquals("expired", RunnerLinkService.field(svc.admin(req("op" to "pair_status")), "state"))
    }

    @Test
    fun `runner_admin pairs rotates and detaches through the transport seam`() {
        val transport = FakeTransport()
        val runner = service(File(dir, "runner")) { transport }
        val orch = service(File(dir, "orch")) { transport }
        val pair = runner.admin(req("op" to "pair", "name" to "box"))
        assertEquals("true", RunnerLinkService.field(pair, "ok"), pair.toString())
        assertEquals(FP, RunnerLinkService.field(pair, "fingerprint"))
        assertEquals("false", RunnerLinkService.field(pair, "plain"))
        val code = RunnerLinkService.field(pair, "code")!!
        assertEquals("waiting", RunnerLinkService.field(runner.admin(req("op" to "pair_status")), "state"))

        val wrong = orch.admin(req("op" to "add", "name" to "box", "url" to "wss://h:1", "code" to code, "fingerprint" to "00"))
        assertEquals("false", RunnerLinkService.field(wrong, "ok"))
        assertEquals(FP, RunnerLinkService.field(wrong, "presentedFingerprint"))

        val added = orch.admin(req("op" to "add", "name" to "box", "url" to "wss://h:1", "code" to code, "fingerprint" to FP, "orchestratorName" to "orch"))
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
        val status = runner.admin(req("op" to "pair_status"))
        assertEquals("paired", RunnerLinkService.field(status, "state"))
        assertEquals("orch", RunnerLinkService.field(status, "orchestratorName"))
        assertNull(transport.handler, "the pairing listener closes once paired")

        runner.tick() // paired: the daemon now serves the link
        assertNotNull(transport.handler)
        assertEquals("box", RunnerLinkService.field(orch.admin(req("op" to "rotate", "name" to "box")), "name"))
        val detached = orch.admin(req("op" to "detach", "name" to "box"))
        assertEquals("the runner revoked it too", RunnerLinkService.field(detached, "remote"), detached.toString())
        runner.tick() // detached: no longer served
        assertNull(transport.handler)
        runner.close(); orch.close()
    }

    @Test
    fun `runner_admin is an owner-only frame and not a link frame`() {
        assertTrue(RunnerLinkService.ADMIN in FrameGate.OWNER_ONLY)
        assertFalse(RunnerLinkService.ADMIN.startsWith(FrameGate.RUNNER_LINK_PREFIX))
        assertNotNull(FrameGate.refusal(RunnerLinkService.ADMIN, owner = false))
        assertNull(FrameGate.refusal(RunnerLinkService.ADMIN, owner = true))
    }

    private companion object {
        const val PEER = "100.64.0.9"
        const val FP = "AA:BB:CC"
    }
}
