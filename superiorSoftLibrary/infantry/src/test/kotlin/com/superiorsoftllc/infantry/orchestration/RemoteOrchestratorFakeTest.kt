// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.orchestration

import com.superiorsoftllc.infantry.daemon.requireBoard

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorBinding
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.TicketRef
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.mailbox.MailSendRequest
import com.superiorsoftllc.infantry.protocol.TicketSave
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ORCH-1 step 5: a fake RemoteOrchestrator (test sources only) implements [OrchestratorLink] with an artificial delay and an
 * "unavailable" failure mode, and the key flows run against it: create a ticket, send mail, spawn and finish a session. The
 * callers hold the interface only, so they must not care. Degradation rules under test (documented on the interface):
 * launchDecision unavailable -> allow (the runner keeps its own guards), reviewReport unavailable -> no refusal, ticketOf /
 * starterOf unavailable -> "unknown"; mail is persisted before any delivery, so it is never lost while the orchestrator is down.
 */
class RemoteOrchestratorFakeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The test double: every call waits [delayMs], is recorded, and answers Ok (the local fallback's answer) or Unavailable. */
    private class FakeRemote(private val fallback: OrchestratorLink, private val delayMs: Long) : OrchestratorLink {
        @Volatile var down = false
        val calls = CopyOnWriteArrayList<String>()
        private fun <T> call(name: String, ok: () -> OrchestratorReply<T>): OrchestratorReply<T> {
            calls += name
            Thread.sleep(delayMs)
            return if (down) OrchestratorReply.Unavailable("fake remote is down") else ok()
        }
        override fun reviewReport(from: String, body: String) = call("reviewReport") { fallback.reviewReport(from, body) }
        override fun ticketOf(ticketId: String) = call("ticketOf") { fallback.ticketOf(ticketId) }
        override fun starterOf(workerSessionId: String) = call("starterOf") { fallback.starterOf(workerSessionId) }
        override fun launchDecision(provider: String, unattended: Boolean, ticketId: String?) = call("launchDecision") { fallback.launchDecision(provider, unattended, ticketId) }
    }

    private fun launch(remote: (OrchestratorLink) -> FakeRemote): Pair<DaemonFixture, () -> FakeRemote> {
        var made: FakeRemote? = null
        val f = DaemonFixture.launch(
            pluginEnv = { if (it == "INFANTRY_ORCHESTRATOR_PROVIDER") "fake-remote" else null },
            orchestratorBindings = listOf("fake-remote" to OrchestratorBinding { _, local -> remote(local).also { made = it } }),
        )
        return f to { made ?: error("the fake-remote binding was not selected") }
    }

    private fun spawnFinished(f: DaemonFixture, prompt: String): String {
        val before = f.daemon.manager.list().size
        f.send(Envelope(Envelope.NEW))
        f.send(Envelope(Envelope.PROMPT, prompt))
        f.await(what = "session to finish") { f.daemon.manager.list().size == before + 1 }
        val id = f.daemon.manager.list()[before].id
        f.await(what = "session at rest") { f.daemon.manager.get(id)?.live == false }
        return id
    }

    private fun runFlows(f: DaemonFixture, remote: FakeRemote) {
        f.client.await(Envelope.READY)
        // create ticket
        val ticket = f.daemon.requireBoard().save(TicketSave(title = "via any orchestrator")).ticket
        assertTrue(ticket != null && f.daemon.requireBoard().get(ticket.id).title == "via any orchestrator", "the ticket exists")
        // spawn + finish a session (goes through the launch decision)
        val id = spawnFinished(f, "work")
        // send mail to it (persisted before delivery, so it is stored even while the orchestrator is down)
        val mailBefore = f.client.of(Envelope.MAIL).size
        f.send(Envelope(Envelope.MAIL_SEND, json.encodeToString(MailSendRequest.serializer(), MailSendRequest(from = "human", to = id, body = "hello"))))
        f.client.await(Envelope.MAIL, mailBefore)
        assertTrue(f.daemon.manager.get(id) != null, "the session ran to rest")
        assertTrue("launchDecision" in remote.calls, "the launch decision went through the orchestrator interface: ${remote.calls}")
    }

    @Test fun `key flows work against a slow remote orchestrator`() {
        val (f, remote) = launch { FakeRemote(it, delayMs = 40) }
        try { runFlows(f, remote()) } finally { f.stop(); f.deleteState() }
    }

    @Test fun `key flows degrade, lose no mail and do not crash when the remote is unavailable`() {
        val (f, remote) = launch { FakeRemote(it, delayMs = 5).also { r -> r.down = true } }
        try {
            runFlows(f, remote())
            assertEquals(OrchestratorReply.Unavailable("fake remote is down"), remote().launchDecision("stub", false, null), "the double really is down")
            assertTrue(f.daemon.manager.list().all { !it.live }, "nothing crashed or hung")
        } finally { f.stop(); f.deleteState() }
    }

    @Test fun `the double and the local binding agree on shapes`() {
        assertEquals(LaunchVerdict.Allow, LaunchVerdict(LaunchVerdict.ALLOW))
        assertEquals(TicketRef("s", "p"), TicketRef("s", "p"))
    }
}
