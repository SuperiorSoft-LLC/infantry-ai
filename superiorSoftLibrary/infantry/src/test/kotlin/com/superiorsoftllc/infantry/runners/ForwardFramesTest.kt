// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.CoreFrameSides
import com.superiorsoftllc.infantry.daemon.FrameTable
import com.superiorsoftllc.infantry.daemon.api.orchestrator.AgentIdentity
import com.superiorsoftllc.infantry.daemon.api.orchestrator.ForwardedFrame
import com.superiorsoftllc.infantry.daemon.api.orchestrator.FrameSide
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon.WssLinkTransport
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-7 (ticket 581cd08a, docs/RUNNER-POOL.md 2.3 b): two in-process daemons, each with its own state dir, joined by the
 * real Infantry_RemoteRunner transport (wss with the pinned fingerprint, port 0). A worker of a session the orchestrator placed on
 * runner r2 asks r2 for orchestrator state; r2 holds the frame, the orchestrator pulls it over the link, re-gates it as the named
 * agent and answers it, and the answer comes back to the worker. A forged identity is refused before anything runs.
 */
class ForwardFramesTest {

    private val dir: File = Files.createTempDirectory("infantry-forward-frames").toFile()
    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        closers.asReversed().forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun req(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) })

    /** Port 0 for the link listener (tests never bind the owner's ports); every other limit is its default. */
    private val limits: (com.superiorsoftllc.infantry.settings.LimitDef) -> Long = { if (it == LimitRegistry.RUNNERS_LINK_PORT) 0L else it.default }

    /** The orchestrator's frames as its connection gates and dispatch would see them: what reached it, and a canned answer. */
    private val reached = CopyOnWriteArrayList<ForwardedFrame>()

    private val sides = mapOf(
        Envelope.TICKET_GET to FrameSide.ORCHESTRATOR,
        PLUGIN_TOOL_CALL to FrameSide.ORCHESTRATOR,
        PURGE to FrameSide.ORCHESTRATOR,
        SPAWN to FrameSide.RUNNER,
    )

    /** Pair runner r2 with the orchestrator (as RemoteRunnerStartTest) and start the orchestrator's forward pump. */
    private fun linked(runnerOwns: (String) -> Boolean): Triple<FrameForwarder, RunnerHub, ForwardedFrameGate> {
        val forwarder = FrameForwarder(owns = runnerOwns, timeoutMs = { 20_000 }, pendingMax = { 8 })
        val r2Service = RunnerLinkService(
            File(dir, "r2"), limits,
            settings = { RunnerLinkSettings.Values(projects = listOf("p1"), harnesses = listOf(RunnerLinkSettings.ANY)) },
            runner = null, transport = { WssLinkTransport }, host = "127.0.0.1", log = {}, forwarder = forwarder,
        ).also { closers += it }
        val oStateDir = File(dir, "orch")
        val oService = RunnerLinkService(oStateDir, limits, settings = { RunnerLinkSettings.Values() }, runner = null, transport = { WssLinkTransport }, host = "127.0.0.1", log = {})
            .also { closers += it }

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
        r2Service.tick()
        val port = assertNotNull(r2Service.servingPort(), "the paired runner serves the link")
        RunnerLinkStore.open(oStateDir).let { s -> s.saveRunner(s.runner("r2")!!.copy(url = "wss://127.0.0.1:$port")) }

        val hub = RunnerHub(object : RunnerLink { override fun ticketIdOf(sessionId: String): String? = null }, remotes = { oService.links.paired() })
        val gate = ForwardedFrameGate(
            runnerOf = hub::runnerOf,
            sideOf = { f -> sides[f.type] },
            pluginOwnerOnly = { it == PURGE },
            dispatch = { f ->
                reached += f
                ForwardedFrame("${f.type}_answer", "{\"for\":\"${f.sessionId}\"}", f.sessionId, f.requestId)
            },
            log = {},
        )
        val pump = ForwardPump(links = { oService.links }, active = hub::hasSessionsOn, execute = gate::answer, waitMs = { 2_000 }, idleMs = { 50 }, log = {})
            .also { closers += it }
        pump.sync(listOf("r2"))
        return Triple(forwarder, hub, gate)
    }

    private fun toolCall(tool: String, clientSession: String, vararg args: Pair<String, String>) =
        "{\"tool\":\"$tool\",\"args\":${req(*args)},\"client\":{\"sessionId\":\"$clientSession\",\"role\":\"backend\",\"projectId\":\"p1\"}}"

    @Test
    fun `a remote worker's ticket get, ticket comment and report script reach the orchestrator and their answers come back`() {
        val worker = "sess-remote"
        val (forwarder, hub) = linked(runnerOwns = { it == worker })
        hub.placed(worker, "r2")

        val get = forwarder.forwardFrame(worker, ForwardedFrame(Envelope.TICKET_GET, "{\"id\":\"t1\"}", worker, "req-1"))
        val comment = forwarder.forwardFrame(worker, ForwardedFrame(PLUGIN_TOOL_CALL, toolCall("infantry_ticket_comment", worker, "id" to "t1", "body" to "done"), worker, "req-2"))
        val report = forwarder.forwardFrame(
            worker,
            ForwardedFrame(PLUGIN_TOOL_CALL, toolCall("infantry_script_run", worker, "name" to "report", "ticketId" to "t1", "status" to "done"), null, "req-3"),
        )

        for ((reply, type, requestId) in listOf(Triple(get, Envelope.TICKET_GET, "req-1"), Triple(comment, PLUGIN_TOOL_CALL, "req-2"), Triple(report, PLUGIN_TOOL_CALL, "req-3"))) {
            val answer = assertNotNull((reply as? OrchestratorReply.Ok)?.value, "$type: $reply")
            assertEquals("${type}_answer", answer.type)
            assertEquals(requestId, answer.requestId, "the worker's own request id is on the answer")
            assertTrue(answer.data.contains(worker), "answered as the agent: ${answer.data}")
        }
        assertEquals(listOf(Envelope.TICKET_GET, PLUGIN_TOOL_CALL, PLUGIN_TOOL_CALL), reached.map { it.type })
        assertTrue(reached.all { it.sessionId == worker }, "every frame reached the orchestrator as the named agent: $reached")
        assertTrue(reached[1].data.contains("infantry_ticket_comment") && reached[2].data.contains("\"report\""), reached.toString())
        assertEquals(0, forwarder.held(), "nothing left waiting on the runner")
    }

    @Test
    fun `a forged identity is refused by the orchestrator and nothing runs`() {
        val worker = "sess-remote"
        // A runner that claims every session as its own.
        val (forwarder, hub) = linked(runnerOwns = { true })
        hub.placed(worker, "r2")
        hub.placed("sess-local", RunnerPool.LOCAL)
        hub.placed("sess-r3", "r3")

        fun refused(sessionId: String, frame: ForwardedFrame): String {
            val answer = assertNotNull((forwarder.forwardFrame(sessionId, frame) as? OrchestratorReply.Ok)?.value, "an answer for $frame")
            assertEquals(Envelope.ERROR, answer.type, answer.toString())
            return answer.data
        }

        // The orchestrator's own (local) session, another runner's session and one never placed: not r2's to speak for.
        for (forged in listOf("sess-local", "sess-r3", "sess-unknown")) {
            assertTrue(refused(forged, ForwardedFrame(Envelope.TICKET_GET, "{}", forged)).contains("is not a session runner"), forged)
        }
        // r2's own session naming another session as the tool call's client.
        assertTrue(refused(worker, ForwardedFrame(PLUGIN_TOOL_CALL, toolCall("infantry_ticket_comment", "sess-local", "id" to "t1"), worker)).contains("client session"))
        // An owner-only frame (the orchestrator's frame gate, as a non-owner) and a runner-side frame.
        assertTrue(refused(worker, ForwardedFrame(PURGE, "{}", worker)).contains("owner credential"))
        assertTrue(refused(worker, ForwardedFrame(SPAWN, "{}", worker)).contains("not an orchestrator-side frame"))
        assertTrue(reached.isEmpty(), "no forged frame reached the orchestrator's dispatch: $reached")
    }

    @Test
    fun `the side attribute defaults to runner and is read only for registered frames`() {
        val frames = FrameTable()
        frames.register("a_frame") { _, _ -> null }
        frames.register("b_frame", "c_frame", side = FrameSide.ORCHESTRATOR) { _, _ -> null }
        frames.register("d_frame", side = "something else") { _, _ -> null }
        assertEquals(FrameSide.RUNNER, frames.side("a_frame"))
        assertEquals(FrameSide.ORCHESTRATOR, frames.side("c_frame"))
        assertEquals(FrameSide.RUNNER, frames.side("d_frame"), "an unknown side reads as runner")
        assertNull(frames.side("unregistered"))
        assertEquals(FrameSide.ORCHESTRATOR, CoreFrameSides.side(Envelope.MAIL_SEND))
        assertNull(CoreFrameSides.side(Envelope.PROMPT))
    }

    @Test
    fun `a session the remote orchestrator did not start is never forwarded`() {
        val starts = RemoteStarts(cloneRoot = { File(dir, "clones") }, outboxMax = { 10 }, log = {})
        val forwarder = FrameForwarder(owns = starts::isLinked, timeoutMs = { 1_000 }, pendingMax = { 1 })
        assertFalse(forwarder.forwards("local-session"), "a daemon's own session: the side tag is never consulted")
        starts.linked("linked-session")
        assertTrue(forwarder.forwards("linked-session"))
        starts.forget("linked-session")
        assertFalse(forwarder.forwards("linked-session"))
        // Nobody pulls: the wait ends as unavailable, with the limit named.
        starts.linked("linked-session")
        val reply = forwarder.forwardFrame("linked-session", ForwardedFrame(Envelope.TICKET_GET, "{}"))
        assertTrue(reply is OrchestratorReply.Unavailable && reply.reason.contains("forwardTimeoutSec"), reply.toString())
        assertEquals(0, forwarder.held())
    }

    private companion object {
        const val PLUGIN_TOOL_CALL = "plugin_tool_call"
        const val PURGE = "board_purge"
        const val SPAWN = "spawn"
    }
}
