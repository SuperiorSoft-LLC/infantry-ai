package com.superiorsoftllc.agenthq.triggers

import com.superiorsoftllc.agenthq.daemon.api.board.PromptStart
import com.superiorsoftllc.agenthq.mailbox.MailResult
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.mailbox.MessageState
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.TriggerAction
import com.superiorsoftllc.agenthq.protocol.TriggerSave
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRIGGER-NUDGE (contract 1.105.0): "every N minutes, nudge role:pm in this project with a prompt", end to end below
 * the wire -- on a REAL store, so the 45.sqm columns (next/last fire, pending mail) are exercised across a restart.
 */
class TriggerNudgeTest {

    private val path = Files.createTempFile("agenthq-nudge-test-", ".db").toFile().also { it.delete() }.absolutePath
    private val stores = mutableListOf<SqlAgentHqStore>()
    private val services = mutableListOf<TriggerService>()
    private var now = 1_800_000_000_000L

    /** Every nudge the service handed over, and what each one answered. */
    private val nudges: MutableList<TriggerNudge> = java.util.Collections.synchronizedList(mutableListOf())
    private var answer: (TriggerNudge) -> PromptDelivery = { PromptDelivery.Mailed("m-${nudges.size}") }
    private val pendingMail: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @AfterTest
    fun cleanUp() {
        services.forEach { it.close() }
        stores.forEach { it.close() }
    }

    private fun service(): TriggerService {
        val store = SqlAgentHqStore.open(path).also { stores += it }
        return TriggerService(
            repo = store.triggers,
            attention = com.superiorsoftllc.agenthq.daemon.MemAttention(),
            startNext = { null },
            sessions = { null },
            deliverPrompt = { nudge -> nudges += nudge; answer(nudge).also { if (it is PromptDelivery.Mailed) pendingMail += it.mailId } },
            mailPending = { it in pendingMail },
            clock = { now },
            pumpMs = 3_600_000, // driven by hand
        ).also { services += it }
    }

    private fun nudgeSave(intervalMs: Long? = 30 * 60_000L, id: String? = null, name: String = "nudge pm") = TriggerSave(
        id = id,
        name = name,
        kind = "schedule",
        intervalMs = intervalMs,
        coalesceMs = 0,
        projectId = "project-1",
        action = TriggerAction(kind = "prompt", agentId = "role:pm", prompt = "check the board"),
    )

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within $timeoutMs ms" }
            Thread.sleep(10)
        }
    }

    private fun dispositions(service: TriggerService, id: String) = service.events(id).map { it.disposition }

    private fun settled(service: TriggerService, id: String, count: Int) =
        awaitTrue { service.events(id).size == count && service.events(id).none { it.disposition == "started" } && service.list().single { it.id == id }.state != "running" }

    @Test
    fun `a busy target gets the nudge as queued mail and the trigger never trips`() {
        val service = service()
        val t = service.save(nudgeSave())

        service.fire(t.id)
        settled(service, t.id, 1)

        assertEquals(listOf("mailed"), dispositions(service, t.id))
        assertEquals("armed", service.list().single().state, "a mailed nudge settles the run; a busy PM is not a failure")
        val nudge = nudges.single()
        assertEquals(TriggerNudge(t.id, "nudge pm", "project-1", "role:pm", "check the board"), nudge)
        assertEquals(now, service.list().single().lastFiredAt)
    }

    @Test
    fun `at most one nudge is pending per trigger, and the next fire after delivery mails again`() {
        val service = service()
        val t = service.save(nudgeSave())
        service.fire(t.id)
        settled(service, t.id, 1)

        // The PM is still mid-turn: the first mail is still queued, so the next two slots add nothing.
        now += 1_000 // events sort by time; a frozen clock would order them by random id
        service.fire(t.id)
        now += 1_000
        service.fire(t.id)
        assertEquals(listOf("mailed", "coalesced_pending", "coalesced_pending"), dispositions(service, t.id))
        assertEquals(1, nudges.size)
        assertEquals("armed", service.list().single().state)

        // The PM came to rest and read it: the next fire mails a fresh nudge.
        pendingMail.clear()
        now += 1_000
        service.fire(t.id)
        settled(service, t.id, 4)
        assertEquals(2, nudges.size)
        assertEquals("mailed", dispositions(service, t.id).last())
    }

    @Test
    fun `a target busy elsewhere with no session to mail is deferred, never tripped`() {
        answer = { PromptDelivery.Busy("profile lane 'role:pm' is at capacity") }
        val service = service()
        val t = service.save(nudgeSave())
        service.fire(t.id)
        settled(service, t.id, 1)
        assertEquals(listOf("deferred_busy"), dispositions(service, t.id))
        assertEquals("armed", service.list().single().state)
    }

    @Test
    fun `a refused delivery still trips, as every other non-success does`() {
        answer = { PromptDelivery.Refused("no agent profile has role 'pm'") }
        val service = service()
        val t = service.save(nudgeSave())
        service.fire(t.id)
        awaitTrue { service.list().single().state == "tripped" }
        assertEquals(listOf("started"), dispositions(service, t.id))
    }

    @Test
    fun `the schedule's next slot and last fire survive a restart, and a missed slot fires once on boot`() {
        val first = service()
        val t = first.save(nudgeSave())
        val dueAt = now + 30 * 60_000L
        assertEquals(dueAt, first.list().single().nextFireAt)
        first.close()

        // The daemon was down for two whole intervals.
        now += 65 * 60_000L
        val second = service()
        assertEquals(dueAt, second.list().single().nextFireAt, "the persisted slot is restored, not recomputed from boot")

        second.pumpWindows()
        settled(second, t.id, 1)
        second.pumpWindows()
        assertEquals(1, nudges.size, "two missed slots fold into ONE catch-up fire")
        val after = second.list().single()
        assertEquals(now, after.lastFiredAt)
        assertEquals(now + 30 * 60_000L, after.nextFireAt, "the schedule resumes one interval after the catch-up")

        // The pending mail is durable too: a third boot still coalesces against it.
        second.close()
        val third = service()
        now += 30 * 60_000L
        third.pumpWindows()
        assertEquals(listOf("mailed", "coalesced_pending"), dispositions(third, t.id))
    }

    @Test
    fun `any role and an odd interval - role qa every 7 minutes`() {
        val service = service()
        val t = service.save(
            TriggerSave(
                name = "qa sweep", kind = "schedule", intervalMs = 7 * 60_000L, coalesceMs = 0, projectId = "project-9",
                action = TriggerAction(kind = "prompt", agentId = "role:qa", prompt = "re-run the flaky suite"),
            ),
        )
        assertEquals(now + 7 * 60_000L, t.nextFireAt)
        now += 7 * 60_000L - 1
        service.pumpWindows()
        assertTrue(nudges.isEmpty(), "not due a millisecond early")
        now += 1
        service.pumpWindows()
        settled(service, t.id, 1)
        assertEquals(TriggerNudge(t.id, "qa sweep", "project-9", "role:qa", "re-run the flaky suite"), nudges.single())
        assertEquals(now + 7 * 60_000L, service.list().single().nextFireAt)

        // The second slot, 7 minutes on, while the first mail still waits: coalesced; once delivered, the third mails.
        now += 7 * 60_000L
        service.pumpWindows()
        assertEquals(listOf("mailed", "coalesced_pending"), dispositions(service, t.id))
        pendingMail.clear()
        now += 7 * 60_000L
        service.pumpWindows()
        settled(service, t.id, 3)
        assertEquals(2, nudges.size)
    }

    @Test
    fun `a one-minute schedule runs all hour without tripping - mail is not a start`() {
        val service = service()
        val t = service.save(nudgeSave(intervalMs = 60_000L)) // default ceiling: 6 starts per hour
        repeat(90) { slot ->
            now += 60_000L
            service.pumpWindows()
            settled(service, t.id, slot + 1)
            pendingMail.clear() // the target read each nudge before the next slot
        }
        assertEquals(90, nudges.size, "90 one-minute slots, 90 nudges")
        assertEquals("armed", service.list().single().state)
        assertTrue(dispositions(service, t.id).all { it == "mailed" })
    }

    @Test
    fun `a prompt fire that starts a session still counts toward the ceiling`() {
        var n = 0
        answer = { PromptDelivery.Started("s-${n++}") }
        val service = service()
        val t = service.save(nudgeSave(intervalMs = 60_000L).copy(ceilingRuns = 3))
        repeat(3) {
            service.fire(t.id)
            awaitTrue { service.events(t.id).size == it + 1 && service.list().single().state == "armed" }
            now += 1_000
        }
        service.fire(t.id)
        awaitTrue { service.list().single().state == "tripped" }
        assertEquals("refused_ceiling", dispositions(service, t.id).last())
    }

    @Test
    fun `a nudge caught mid hand-off by a restart comes back armed, not tripped`() {
        val first = service()
        val t = first.save(nudgeSave())
        stores.last().triggers.upsert(first.list().single().copy(state = "running"), t.intervalMs)
        first.close()
        assertEquals("armed", service().list().single().state, "the next slot nudges again; a trip would silence the night")
    }

    @Test
    fun `an edit that keeps the interval keeps the slot and a new interval restarts it`() {
        val service = service()
        val t = service.save(nudgeSave())
        val slot = service.list().single().nextFireAt
        now += 10 * 60_000L
        assertEquals(slot, service.save(nudgeSave(id = t.id, name = "renamed")).nextFireAt)
        assertEquals(now + 15 * 60_000L, service.save(nudgeSave(intervalMs = 15 * 60_000L, id = t.id)).nextFireAt)
    }

    @Test
    fun `interval and project are validated`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> { service.save(nudgeSave(intervalMs = 59_999)) }
        assertEquals(60_000L, service.save(nudgeSave(intervalMs = 60_000)).intervalMs, "one minute is the floor, inclusive")
        assertFailsWith<IllegalArgumentException>("a project only scopes a prompt") {
            service.save(TriggerSave(name = "x", kind = "manual", projectId = "project-1"))
        }
    }

    @Test
    fun `arm enables a disabled trigger, the UI's enable toggle`() {
        val service = service()
        val t = service.save(nudgeSave())
        assertEquals("disabled", service.disable(t.id).state)
        val enabled = service.arm(t.id)
        assertEquals("armed", enabled.state)
        assertTrue(enabled.enabled)
        assertFailsWith<IllegalArgumentException>("an armed trigger has nothing to arm") { service.arm(t.id) }
    }

    @Test
    fun `a manual start_next trigger is unchanged`() {
        val service = service()
        val t = service.save(TriggerSave(name = "manual", kind = "manual", coalesceMs = 0))
        service.fire(t.id)
        settled(service, t.id, 1)
        assertEquals(listOf("nothing_to_start"), dispositions(service, t.id))
        assertTrue(nudges.isEmpty())
        assertNull(service.list().single().nextFireAt)
    }

    // --- TriggerNudges: which session a nudge is mailed to ------------------------------------------------------

    private fun session(id: String, role: String?, projectId: String?, agentId: String? = "agent-$id", state: SessionState = SessionState.IDLE, spawnedAt: Long = 0) =
        SessionRecord(
            id = id, provider = "claude", workingDir = ".", spawnedAt = spawnedAt, lastHeartbeat = 0, state = state,
            providerSessionId = "p-$id", runCount = 1, agentId = agentId, role = role, projectId = projectId,
        )

    private fun nudges(sessions: List<SessionRecord>, sent: MutableList<MailSendRequest>, started: MutableList<String?> = mutableListOf(), launch: PromptStart = PromptStart.Started("s-new")) =
        TriggerNudges(
            sessions = { sessions },
            send = { request ->
                sent += request
                MailResult.Sent(Message(id = "m-1", threadId = request.threadId!!, seq = 1, from = request.from, to = request.to, subject = request.subject, body = request.body, sentAt = 0, state = MessageState.QUEUED))
            },
            startSession = { _, _, projectId -> started += projectId; launch },
            clock = { 42 },
        )

    private val pmNudge = TriggerNudge("trg-1", "nudge", "project-1", "role:pm", "check the board")

    @Test
    fun `a nudge is mailed to the pm of the trigger's project, the busy one first`() {
        val sent = mutableListOf<MailSendRequest>()
        val delivery = nudges(
            listOf(
                session("other", "pm", "project-2", state = SessionState.BUSY, spawnedAt = 9),
                session("idle-old", "pm", "project-1", spawnedAt = 1),
                session("busy", "pm", "project-1", state = SessionState.BUSY, spawnedAt = 2),
                session("dev", "fullstack", "project-1", spawnedAt = 5),
            ),
            sent,
        ).deliver(pmNudge)
        assertEquals(PromptDelivery.Mailed("m-1"), delivery)
        val mail = sent.single()
        assertEquals("agent-busy", mail.to, "mailed to the agent, so it lands when that agent's turn ends")
        assertEquals("trigger:trg-1", mail.from)
        assertEquals("check the board", mail.body)
        assertEquals("trigger:trg-1:42", mail.threadId, "one thread per fire: an all-night trigger never hits the thread ceiling")
    }

    @Test
    fun `any role resolves in the trigger's project - a qa nudge never reaches the pm`() {
        val sent = mutableListOf<MailSendRequest>()
        val qaNudge = TriggerNudge("trg-2", "qa sweep", "project-1", "role:qa", "re-run")
        nudges(listOf(session("pm", "pm", "project-1", state = SessionState.BUSY), session("qa", "qa", "project-1")), sent).deliver(qaNudge)
        assertEquals("agent-qa", sent.single().to)
        val started = mutableListOf<String?>()
        val none = nudges(listOf(session("pm", "pm", "project-1")), mutableListOf(), started).deliver(qaNudge)
        assertEquals(PromptDelivery.Started("s-new"), none, "no qa session in the project: one is started, the pm is not mailed")
    }

    @Test
    fun `a profile target is mailed through that agent's session`() {
        val sent = mutableListOf<MailSendRequest>()
        nudges(listOf(session("a", "fullstack", "project-1", agentId = "agent-1234")), sent)
            .deliver(TriggerNudge("trg-3", "x", null, "agent-1234", "go"))
        assertEquals("agent-1234", sent.single().to)
    }

    @Test
    fun `with no pm session in the project one is started there`() {
        val sent = mutableListOf<MailSendRequest>()
        val started = mutableListOf<String?>()
        val delivery = nudges(listOf(session("other", "pm", "project-2")), sent, started).deliver(pmNudge)
        assertEquals(PromptDelivery.Started("s-new"), delivery)
        assertTrue(sent.isEmpty())
        assertEquals(listOf<String?>("project-1"), started)
    }

    @Test
    fun `a start refused for capacity is busy, any other refusal is a refusal`() {
        val busy = nudges(emptyList(), mutableListOf(), launch = PromptStart.Refused("profile lane 'role:pm' is at capacity")).deliver(pmNudge)
        assertTrue(busy is PromptDelivery.Busy)
        val refused = nudges(emptyList(), mutableListOf(), launch = PromptStart.Refused("no agent profile has role 'pm'")).deliver(pmNudge)
        assertEquals(PromptDelivery.Refused("no agent profile has role 'pm'"), refused)
    }
}
