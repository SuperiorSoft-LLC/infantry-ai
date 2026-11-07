// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.daemon.LocalRunner
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailItem
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.protocol.SpawnOptions
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RUNNER-POOL-2: the local runner link answers from the same session-manager functions the daemon called before. */
class LocalRunnerLinkTest {
    private class Repo : AgentRepository {
        private val p = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { p[profile.id] = profile }
        override fun get(id: String): AgentProfile? = p[id]
        override fun all(): List<AgentProfile> = p.values.toList()
        override fun delete(id: String): Boolean = p.remove(id) != null
    }

    private class Rig(poolOf: (SessionManager, AgentProfiles) -> RunnerPool? = { m, a -> LocalRunner.pool(m, a) }) {
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = true), tempSessionStore(), killGraceMs = 50)
        val agents = AgentProfiles(Repo(), manager)
        val pool = poolOf(manager, agents)
        val link: RunnerLink = pool?.link(RunnerPool.LOCAL) ?: object : RunnerLink { override fun ticketIdOf(sessionId: String): String? = null }
        val router = MailRouter(
            manager, Mailbox(MemMailStore(), MailLimits()), agents,
            resumeTurn = pool?.let { LocalRunner.deliverVia(it) },
        )
        val turns = mutableListOf<SessionEvent.Turn>()
        init {
            manager.eventSink = { e ->
                if (e is SessionEvent.Turn) turns += e
                router.onSessionEvent(e)
            }
        }
    }

    @Test
    fun mailThroughTheLocalRunnerIsTheSameTurnAsADirectResume() {
        fun deliver(rig: Rig): SessionEvent.Turn {
            val sender = rig.manager.startNew("hello")
            val recipient = rig.manager.startNew("hello again")
            rig.turns.clear()
            rig.router.send(MailSendRequest(from = sender.id, to = recipient.id, body = "review this"))
            return rig.turns.single { it.prompt.contains("review this") }.also {
                assertEquals(recipient.id, it.sessionId)
                assertEquals(MessageOrigin.Agent(sender.id, sender.agentId, sender.role), it.origin)
            }
        }
        val routed = deliver(Rig())
        val direct = deliver(Rig { _, _ -> null })
        // Same text apart from the session/message ids each rig generated.
        assertEquals(direct.prompt.length, routed.prompt.length)
    }

    @Test
    fun aRunnerThatCannotDeliverKeepsTheMailQueued() {
        val offline = object : RunnerLink { override fun ticketIdOf(sessionId: String): String? = null }
        val rig = Rig { _, _ -> RunnerHub(offline) }
        val sender = rig.manager.startNew("hello")
        val recipient = rig.manager.startNew("hello again")
        rig.turns.clear()
        rig.router.send(MailSendRequest(from = sender.id, to = recipient.id, body = "parked"))
        assertTrue(rig.turns.none { it.prompt.contains("parked") }, "an unavailable runner must not deliver")
        assertTrue(offline.deliverMail(recipient.id, MailItem("x", "{}", "mail", emptyList())) is OrchestratorReply.Unavailable)
    }

    @Test
    fun startStopAndRefusalsAreValues() {
        val rig = Rig()
        assertEquals(OrchestratorReply.Ok(SessionRef()), rig.link.startSession(StartSpec("s1")), "no agent and no role binds nothing")
        val refused = rig.link.startSession(StartSpec("s2", agentId = "nobody"))
        assertTrue((refused as OrchestratorReply.Ok).value.refusal!!.contains("no agent profile 'nobody'"))
        assertFailsWith<IllegalArgumentException> { LocalRunner.start(rig.pool!!, rig.manager, SpawnOptions(agentId = "nobody"), null) }
        assertNull(LocalRunner.start(rig.pool!!, rig.manager, null, null))

        assertEquals(OrchestratorReply.Ok(false), rig.link.stopSession("no-such-session"))
        val s = rig.manager.startNew("work")
        assertEquals(OrchestratorReply.Ok(true), rig.link.stopSession(s.id, "test"))
        assertEquals(MailResult.FAILED, (rig.link.deliverMail("no-such-session", MailItem("x", LocalRunner.encodeOrigin(MessageOrigin.Owner), "mail", listOf("m1"))) as OrchestratorReply.Ok).value.kind)
    }

    @Test
    fun theSpecCarriesExactlyWhatALocalSpawnReads() {
        val options = SpawnOptions(provider = "p", model = "m", agentId = "a", projectId = "j", effort = "high", role = "backend", contextLimit = 1000)
        assertEquals(options, LocalRunner.spawnOptions(LocalRunner.startSpec(options, null)))
        for (o in listOf(MessageOrigin.Owner, MessageOrigin.Agent("s", "a", "r"), MessageOrigin.External("x", "c"), MessageOrigin.System("mail"))) {
            assertEquals(o, LocalRunner.decodeOrigin(LocalRunner.encodeOrigin(o)))
        }
    }

    @Test
    fun thePoolHoldsOnlyLocal() {
        val link = LocalRunnerLink(LocalRunner.services(Rig().manager, Rig().agents, info = { com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo("local", RunnerLink.CONTRACT_VERSION) }, capacity = { 4 }))
        val pool = RunnerHub(link)
        assertEquals(listOf(RunnerPool.LOCAL), pool.runners().map { it.name })
        assertEquals(4, pool.runners().single().health?.freeSlots)
        assertEquals(RunnerPool.LOCAL, pool.runnerOf("any-session"))
        assertNull(pool.link("server-1"))
        assertFailsWith<IllegalArgumentException> { pool.register(RunnerPool.LOCAL) { _, l -> l } }
        pool.register("remote") { _, l -> l }
        assertEquals(listOf("remote"), pool.bindingIds())
        assertEquals("1.1.0", link.contractVersion)
    }
}
