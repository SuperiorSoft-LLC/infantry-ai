// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.LocalRunner
import com.superiorsoftllc.infantry.daemon.api.orchestrator.CommandResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerBinding
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCommand
import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-UNIFY-2 (docs/RUNNER-UNIFY.md 5.1): every owner command on a session goes through the runner it lives on. Pins: a local
 * session's command reaches the local link, a placed one the remote link, and nothing else is asked; `runners.unifiedCommands` off
 * runs a held session directly; over the wire the runner runs a command only on a session its orchestrator started (`unknown`
 * otherwise, never an error), and a delete that went through forgets that session's link state.
 */
class RunnerCommandTest {
    private class Recording(val name: String, var answer: CommandResult = CommandResult.Done) : RunnerLink {
        val got = mutableListOf<Pair<String, SessionCommand>>()
        override fun ticketIdOf(sessionId: String): String? = null
        override fun hello(): OrchestratorReply<RunnerInfo> = OrchestratorReply.Ok(RunnerInfo(name, RunnerLink.CONTRACT_VERSION))
        override fun command(sessionId: String, command: SessionCommand): OrchestratorReply<CommandResult> {
            got += sessionId to command
            return OrchestratorReply.Ok(answer)
        }
    }

    private val local = Recording("local")
    private val remote = Recording("box")
    private val hub = RunnerHub(local, remotes = { listOf("box") }).also { h ->
        h.register("test", RunnerBinding { _, _ -> remote })
        h.placed("s-remote", "box")
    }
    private val direct = mutableListOf<String>()
    private fun run(id: String, c: SessionCommand, unified: Boolean = true) =
        LocalRunner.command(hub, id, c, unified, held = { it == "s-local" }, direct = { sid, _ -> direct += sid; CommandResult.Done })

    @Test
    fun `a local session's command goes to the local runner, a placed one to its runner`() {
        val interrupt = SessionCommand(SessionCommand.INTERRUPT)
        assertTrue(run("s-local", interrupt).ok)
        assertTrue(run("s-remote", SessionCommand(SessionCommand.TURN, "hi")).ok)
        assertEquals(listOf("s-local" to interrupt), local.got)
        assertEquals(listOf("s-remote" to SessionCommand(SessionCommand.TURN, "hi")), remote.got)
        assertTrue(direct.isEmpty(), "unified: nothing skips the pool")
    }

    @Test
    fun `unifiedCommands off runs a session this daemon holds directly, a remote one still goes to its runner`() {
        run("s-local", SessionCommand(SessionCommand.KILL, force = true), unified = false)
        run("s-remote", SessionCommand(SessionCommand.KILL, force = true), unified = false)
        assertEquals(listOf("s-local"), direct)
        assertTrue(local.got.isEmpty())
        assertEquals(listOf("s-remote"), remote.got.map { it.first })
    }

    @Test
    fun `a runner that is not connected answers unavailable with its name`() {
        val lonely = RunnerHub(local, remotes = { listOf("box") }).also { it.placed("s-remote", "box") }
        val r = LocalRunner.command(lonely, "s-remote", SessionCommand(SessionCommand.INTERRUPT), true, held = { false }, direct = { _, _ -> error("not held") })
        assertEquals(CommandResult.UNAVAILABLE, r.kind)
        assertTrue(r.reason.orEmpty().contains("'box'"), r.reason)
    }

    // Over the wire: an orchestrator and a runner paired through an in-process transport.

    private val dir: File = Files.createTempDirectory("runner-command").toFile()
    private val remoteStarts = RemoteStarts(cloneRoot = { File(dir, "clones") }, outboxMax = { 8 }, log = {})
    private val onRunner = Recording("box", CommandResult(CommandResult.IN_FLIGHT, "a turn is running"))
    private val transport = InProcess()
    private val runner = RunnerLinkService(
        File(dir, "runner"), { it.default }, { RunnerLinkSettings.Values(harnesses = listOf("*")) }, onRunner, { transport },
        host = "127.0.0.1", log = {}, starts = remoteStarts,
    )
    private val orch = RunnerLinkService(
        File(dir, "orch"), { it.default }, { RunnerLinkSettings.Values() }, null, { transport }, host = "127.0.0.1", log = {},
    )

    private fun pair() {
        val code = RunnerLinkService.field(runner.admin(op("pair", "name" to "box")), "code")
        assertNotNull(code)
        val added = orch.admin(op("add", "name" to "box", "url" to "wss://h:1", "code" to code, "fingerprint" to FP, "orchestratorName" to "orch"))
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
        runner.tick()
    }

    private fun send(sessionId: String, kind: String, text: String = ""): F.CommandAnswer {
        val a = orch.links.call("box", F.COMMAND, F.json.encodeToString(F.CommandRequest.serializer(), F.CommandRequest(sessionId, kind, text)))
        assertEquals(F.REPLY, a.type, a.data)
        return F.json.decodeFromString(F.CommandAnswer.serializer(), a.data)
    }

    @Test
    fun `the runner runs a command only on a session its orchestrator started, and its answer crosses unchanged`() {
        pair()
        assertEquals(CommandResult.UNKNOWN, send("s-other", SessionCommand.INTERRUPT).kind, "a session it did not start is a ghost, not an error")
        assertTrue(onRunner.got.isEmpty(), "nothing ran for it")

        remoteStarts.linked("s-mine")
        val a = send("s-mine", SessionCommand.QUEUE_ADD, "next")
        assertEquals(CommandResult.IN_FLIGHT, a.kind)
        assertEquals("a turn is running", a.reason)
        assertEquals(listOf("s-mine" to SessionCommand(SessionCommand.QUEUE_ADD, "next")), onRunner.got)
    }

    @Test
    fun `a delete the runner refused keeps the session linked, one it ran forgets it`() {
        pair()
        remoteStarts.linked("s-mine")
        onRunner.answer = CommandResult(CommandResult.REFUSED, "session 's-mine' is still running (BUSY); kill it first, then remove it")
        assertEquals(CommandResult.REFUSED, send("s-mine", SessionCommand.DELETE).kind)
        assertTrue(remoteStarts.isLinked("s-mine"), "a refused delete keeps the session")
        onRunner.answer = CommandResult.Done
        assertEquals(CommandResult.DONE, send("s-mine", SessionCommand.DELETE).kind)
        assertFalse(remoteStarts.isLinked("s-mine"), "a delete that ran forgets the link state")
    }

    private fun op(name: String, vararg fields: Pair<String, String?>) =
        JsonObject((fields.filter { it.second != null } + ("op" to name)).associate { (k, v) -> k to JsonPrimitive(v!!) })

    private class InProcess : RunnerLinkTransport {
        private var handler: LinkHandler? = null

        override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer =
            (handler ?: throw LinkFailure("nothing serves $url")).handle(type, data, orchestratorId, credential, PEER)

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
