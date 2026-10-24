package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.MessageOrigin
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract 1.125.0: a turn carries WHO it came from as data, set where it is created. */
class MessageOriginTest {
    private class Repo : AgentRepository {
        private val p = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { p[profile.id] = profile }
        override fun get(id: String): AgentProfile? = p[id]
        override fun all(): List<AgentProfile> = p.values.toList()
        override fun delete(id: String): Boolean = p.remove(id) != null
    }

    private val json = Json { ignoreUnknownKeys = true }

    private class Rig {
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val router = MailRouter(manager, Mailbox(MemMailStore(), MailLimits()), AgentProfiles(Repo(), manager))
        val turns = mutableListOf<SessionEvent.Turn>()
        init {
            manager.eventSink = { e ->
                if (e is SessionEvent.Turn) turns += e
                router.onSessionEvent(e)
            }
        }
    }

    @Test
    fun mailFromAnAgentSessionIsATurnWithAnAgentOrigin() {
        val rig = Rig()
        val sender = rig.manager.startNew("hello")
        val recipient = rig.manager.startNew("hello again")
        rig.turns.clear()
        rig.router.send(MailSendRequest(from = sender.id, to = recipient.id, body = "review this"))
        val turn = rig.turns.single { it.prompt.contains("[Infantry AI COMMS]") }
        assertEquals(recipient.id, turn.sessionId)
        assertEquals(MessageOrigin.Agent(sender.id, sender.agentId, sender.role), turn.origin)
    }

    @Test
    fun mailFromTheOwnerHandleIsOwnerAndFromSystemIsSystem() {
        val rig = Rig()
        val agent = rig.manager.startNew("hi")
        rig.turns.clear()
        rig.router.send(MailSendRequest(from = "human", to = agent.id, body = "from me"))
        assertEquals(MessageOrigin.Owner, rig.turns.single { it.prompt.contains("from me") }.origin)
        rig.router.send(MailSendRequest(from = "system", to = agent.id, body = "tick"))
        assertEquals(MessageOrigin.System("mail"), rig.turns.single { it.prompt.contains("tick") }.origin)
        rig.router.send(MailSendRequest(from = "github-bridge", to = agent.id, body = "pushed"))
        assertEquals(MessageOrigin.External("github-bridge", "mail"), rig.turns.single { it.prompt.contains("pushed") }.origin)
    }

    @Test
    fun anOrdinaryPromptTurnIsOwner() {
        val rig = Rig()
        rig.manager.startNew("plain question")
        assertEquals(MessageOrigin.Owner, rig.turns.single().origin)
    }

    @Test
    fun theHeaderIsRenderedFromTheSourceInOnePlace() {
        val msg = Message(id = "m1", threadId = "t1", seq = 1, from = "s-9", to = "s-2", body = "body", sentAt = 0)
        val src = MailSource(msg, MessageOrigin.Agent("s-9", "a-1", "architect"))
        assertTrue(src.render().startsWith("[Infantry AI COMMS] message m1 from s-9 · thread t1"))
        assertEquals("from architect (a-1)", src.origin.label())
    }

    @Test
    fun labelsAreOneLineNamingTheSender() {
        assertEquals("from architect (dd062cba)", MessageOrigin.Agent("s", "dd062cba-1111", "architect").label())
        assertEquals("from owner", MessageOrigin.Owner.label())
        assertEquals("from github-bridge", MessageOrigin.External("github-bridge", "mail").label())
        assertEquals("from system (handover)", MessageOrigin.System("handover").label())
    }

    @Test
    fun theOriginIsOnTheWireOnlyWhenNotOwnerAndAnAbsentOriginDecodesAsNone() {
        val agent = Envelope("turn", "x", "s", origin = MessageOrigin.Agent("s9", "a1", "qa"))
        val text = json.encodeToString(Envelope.serializer(), agent)
        assertTrue(text.contains("\"origin\":{\"type\":\"agent\""), text)
        assertEquals(agent, json.decodeFromString(Envelope.serializer(), text))

        val plain = json.encodeToString(Envelope.serializer(), Envelope("turn", "x", "s"))
        assertFalse(plain.contains("origin"), plain)
        assertNull(json.decodeFromString(Envelope.serializer(), """{"type":"turn","data":"x"}""").origin)
    }

    @Test
    fun theCliAndMcpShowATurnFromItsOriginAndAnOwnerTurnByDefault() {
        val from = Envelope("turn", "x", "s", origin = MessageOrigin.Agent("s9", "dd062cba-1", "architect"))
        assertEquals("[turn from architect (dd062cba)]", com.superiorsoftllc.agenthq.cli.TurnLine.of(from))
        assertEquals("[turn from owner]", com.superiorsoftllc.agenthq.cli.TurnLine.of(Envelope("turn", "x", "s")))
    }
}
