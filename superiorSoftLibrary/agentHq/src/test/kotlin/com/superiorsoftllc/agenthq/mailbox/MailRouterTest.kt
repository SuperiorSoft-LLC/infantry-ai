package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 6 gate: the mailbox drives real sessions.
 *
 * The closing test is the phase gate itself -- **two agents negotiate a handoff unattended** -- and
 * the ones before it pin the delivery policy it rests on, including the requirement that a delivery
 * failure is always visible rather than silent.
 *
 * The doubles live in [FakeAgentProvider] / [ScriptedRunner] (TestAgents.kt) and are shared with the
 * agent-facing HTTP suite.
 */
class MailRouterTest {

    /** In-memory agent repository for testing. */
    private class FakeAgentRepository : AgentRepository {
        private val profiles = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) {
            profiles[profile.id] = profile
        }
        override fun get(id: String): AgentProfile? = profiles[id]
        override fun all(): List<AgentProfile> = profiles.values.toList()
        override fun delete(id: String): Boolean = profiles.remove(id) != null
    }

    /** A wired harness: manager + mailbox + router, with auto-delivery on every status event. */
    private class Harness(
        val manager: SessionManager,
        val mailbox: Mailbox,
        val router: MailRouter,
        val runner: ScriptedRunner,
        val agentRepository: FakeAgentRepository,
    ) {
        fun createAgentProfile(id: String, name: String, role: String): AgentProfile {
            val profile = AgentProfile(id = id, name = name, role = role)
            agentRepository.save(profile, System.currentTimeMillis())
            return profile
        }
    }

    private fun harness(runner: ScriptedRunner): Harness {
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val agentRepository = FakeAgentRepository()
        val agentProfiles = AgentProfiles(agentRepository, manager)
        val router = MailRouter(
            manager = manager,
            mailbox = mailbox,
            agentProfiles = agentProfiles,
        )
        manager.eventSink = { event -> router.onSessionEvent(event) }
        return Harness(manager, mailbox, router, runner, agentRepository)
    }

    // ----- delivery policy ----------------------------------------------------------------------

    @Test
    fun a_message_to_an_idle_session_resumes_its_console() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        val agent = h.manager.startNew("bootstrap") // finishes immediately -> IDLE, resumable
        val runsBefore = agent.runCount

        val sent = h.router.send(MailSendRequest(from = "human", to = agent.id, body = "wake up")) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        assertTrue(h.manager.get(agent.id)!!.runCount > runsBefore, "delivery starts a run on the recipient")
        assertTrue(
            runner.processes.any { it.input.contains("[agentHq mailbox]") && it.input.contains("wake up") },
            "the message must reach the console as its prompt",
        )
    }

    @Test
    fun a_busy_recipient_keeps_the_message_queued_until_it_is_free() {
        val runner = ScriptedRunner(autoExit = false)
        val h = harness(runner)
        val worker = h.manager.startNew("long job") // stays BUSY

        val sent = h.router.send(MailSendRequest(from = "human", to = worker.id, body = "later")) as MailResult.Sent
        assertEquals(MessageState.QUEUED, h.mailbox.message(sent.message.id)!!.state, "busy recipient defers delivery")

        // The run ends; the status event triggers auto-delivery and the message lands.
        runner.current!!.exit(0)

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
    }

    @Test
    fun an_unknown_recipient_is_a_visible_permanent_failure() {
        val h = harness(ScriptedRunner())
        val sent = h.router.send(MailSendRequest(from = "human", to = "ghost", body = "anyone there?")) as MailResult.Sent

        val failed = h.mailbox.message(sent.message.id)!!
        assertEquals(MessageState.UNDELIVERABLE, failed.state)
        assertNotNull(failed.failureReason)
        assertTrue(failed.failureReason!!.contains("unknown recipient"))
    }

    @Test
    fun a_non_resumable_recipient_is_a_visible_permanent_failure() {
        // The child never reports a session id and exits non-zero: FAILED with nothing to resume.
        val runner = ScriptedRunner(autoExit = true, emitSessionId = false, autoExitCode = 1)
        val h = harness(runner)
        val broken = h.manager.startNew("crash")

        val sent = h.router.send(MailSendRequest(from = "human", to = broken.id, body = "hello?")) as MailResult.Sent
        val failed = h.mailbox.message(sent.message.id)!!
        assertEquals(MessageState.UNDELIVERABLE, failed.state)
        assertTrue(failed.failureReason!!.contains("not resumable"))
    }

    @Test
    fun waiting_only_counts_senders_that_are_actually_at_rest() {
        val runner = ScriptedRunner(autoExit = false)
        val h = harness(runner)
        val asker = h.manager.startNew("idle")
        val other = h.manager.startNew("other")
        // Park both sessions so a message can actually be delivered to `other`.
        runner.processes[0].exit(0)
        runner.processes[1].exit(0)

        h.router.send(MailSendRequest(from = asker.id, to = other.id, body = "blocked on you", needsInput = true))
        assertEquals(listOf(asker.id), h.router.waiting().map { it.session })

        // The asker picks up other work and is no longer "waiting" -- no false signal.
        h.manager.resume(asker.id, "do something else")
        assertTrue(h.router.waiting().isEmpty(), "an agent with a run in flight is not waiting on anyone")
    }

    // ----- agent profile ID resolution ------------------------------------------------------------

    @Test
    fun an_agent_profile_id_resolves_to_its_live_session() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        // Create the agent profile first (simulating a saved agent)
        h.createAgentProfile("architect-agent", "Architect Agent", "architect")
        // Create a session with an agentId (simulating a spawned agent)
        val agentSession = h.manager.startNew("bootstrap", agentId = "architect-agent", role = "architect")

        // Send to the agent profile ID instead of the session ID
        val sent = h.router.send(MailSendRequest(from = "human", to = "architect-agent", body = "wake up")) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        assertTrue(
            runner.processes.any { it.input.contains("[agentHq mailbox]") && it.input.contains("wake up") },
            "the message must reach the console as its prompt",
        )
    }

    @Test
    fun an_agent_with_no_live_session_is_refused() {
        val h = harness(ScriptedRunner())
        // Create the agent profile but NO session exists with this agentId
        h.createAgentProfile("nonexistent-agent", "Nonexistent Agent", "architect")
        val sent = h.router.send(MailSendRequest(from = "human", to = "nonexistent-agent", body = "hello?")) as MailResult.Sent

        val failed = h.mailbox.message(sent.message.id)!!
        assertEquals(MessageState.UNDELIVERABLE, failed.state)
        assertNotNull(failed.failureReason)
        assertEquals("agent nonexistent-agent has no running session — start it first", failed.failureReason)
    }

    @Test
    fun a_session_id_still_works_directly() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        val agent = h.manager.startNew("bootstrap")

        // Send directly to the session ID (existing behavior)
        val sent = h.router.send(MailSendRequest(from = "human", to = agent.id, body = "direct")) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        assertTrue(
            runner.processes.any { it.input.contains("[agentHq mailbox]") && it.input.contains("direct") },
            "the message must reach the console as its prompt",
        )
    }

    /**
     * The agent-id addressing gap AGENT-UX-13 closes: an agent that is BUSY is at work, not absent. A
     * handoff addressed to its profile id must WAIT (QUEUED) and land when its run ends, exactly like
     * a session-addressed message to a busy session -- never a permanent UNDELIVERABLE.
     */
    @Test
    fun an_agent_id_addressing_a_busy_agent_queues_then_delivers_when_free() {
        val runner = ScriptedRunner(autoExit = false)
        val h = harness(runner)
        h.createAgentProfile("worker-agent", "Worker Agent", "backend")
        // The agent's only session is BUSY: `resumable` is false while a run is in flight.
        h.manager.startNew("long job", agentId = "worker-agent", role = "backend")

        val sent = h.router.send(
            MailSendRequest(from = "human", to = "worker-agent", body = "handoff for you"),
        ) as MailResult.Sent

        assertEquals(
            MessageState.QUEUED,
            h.mailbox.message(sent.message.id)!!.state,
            "a busy agent's handoff must WAIT, not become a permanent failure",
        )

        // The run ends; the session rests and the deferred handoff is pumped to its console.
        runner.current!!.exit(0)

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        assertTrue(
            runner.processes.any {
                it.input.contains("[agentHq mailbox]") && it.input.contains("handoff for you")
            },
            "the deferred handoff must reach the agent's console once it is free",
        )
    }

    /** A message to an agent id no profile knows fails cleanly, naming the unknown recipient. */
    @Test
    fun a_message_to_an_unknown_agent_id_fails_cleanly() {
        val h = harness(ScriptedRunner())
        val sent = h.router.send(
            MailSendRequest(from = "human", to = "ghost-agent", body = "anyone there?"),
        ) as MailResult.Sent

        val failed = h.mailbox.message(sent.message.id)!!
        assertEquals(MessageState.UNDELIVERABLE, failed.state)
        assertNotNull(failed.failureReason)
        assertTrue(failed.failureReason!!.contains("unknown recipient"), failed.failureReason!!)
    }

    /**
     * The receiver's half of a handoff: the message is not merely written to a store, it reaches the
     * recipient's console carrying the id, the sender and the exact commands that acknowledge and
     * answer it. That is what makes the receiving agent able to ACT, not just read.
     */
    @Test
    fun a_delivered_handoff_tells_the_receiver_how_to_act() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        val sender = h.manager.startNew("done")
        val receiver = h.manager.startNew("idle")

        val sent = h.router.send(
            MailSendRequest(
                from = sender.id,
                to = receiver.id,
                subject = "Handoff: token rotation",
                body = "Please take over the endpoint work.",
                needsInput = true,
            ),
        ) as MailResult.Sent

        val prompt = runner.processes.last { it.input.contains("[agentHq mailbox]") }.input.toString()
        assertTrue(prompt.contains(sent.message.id), "the receiver must see the message id")
        assertTrue(prompt.contains("-- answer this message --"), prompt)
        // MAIL-FOOTER-1: CLI commands, ready to paste: the reply names the FULL id, read takes the 8-char one.
        assertTrue(
            prompt.contains("agenthq send ${sender.id} \"<your answer, one line>\" --reply-to ${sent.message.id}"),
            "the receiver must be told how to answer: $prompt",
        )
        assertTrue(prompt.contains("agenthq read ${sent.message.id.take(8)}\n"), prompt)
        assertTrue(prompt.contains("agenthq threads"), prompt)
        assertTrue(prompt.contains("\"replyTo\":\"${sent.message.id}\""), "the MCP equivalent: $prompt")
        assertFalse(prompt.contains("curl"), "no stale HTTP recipe: $prompt")
    }

    /**
     * UX-13 slice (b) -- QUESTION/ANSWER by agent id: A asks B a question by B's AGENT id, and B's
     * reply round-trips back to A's session (not merely to a store). The asker's needs-input signal
     * clears only when the answer lands, which is the whole point of one agent asking another.
     */
    @Test
    fun an_agent_id_question_round_trips_back_to_the_asker() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        h.createAgentProfile("asker-agent", "Asker", "architect")
        h.createAgentProfile("answerer-agent", "Answerer", "backend")
        val asker = h.manager.startNew("asker", agentId = "asker-agent", role = "architect")
        val answerer = h.manager.startNew("answerer", agentId = "answerer-agent", role = "backend")

        val question = h.router.send(
            MailSendRequest(
                from = asker.id,
                to = "answerer-agent",
                subject = "Question: which route?",
                body = "Should the rotate endpoint live under /token?",
                needsInput = true,
            ),
        ) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(question.message.id)!!.state)
        assertEquals(listOf(asker.id), h.router.waiting().map { it.session }, "the asker is blocked on the answer")

        // B answers the question in the same thread; the reply must come back to A's session.
        h.mailbox.markRead(question.message.id)
        val answer = h.router.send(
            MailSendRequest(
                from = answerer.id,
                to = asker.id,
                body = "Yes: /token/rotate.",
                replyTo = question.message.id,
            ),
        ) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(answer.message.id)!!.state)
        assertEquals(MessageState.ANSWERED, h.mailbox.message(question.message.id)!!.state)
        assertTrue(h.router.waiting().isEmpty(), "the asker is no longer blocked once the answer lands")
        assertTrue(
            runner.processes.any { it.input.contains("[agentHq mailbox]") && it.input.contains("/token/rotate") },
            "the answer must reach the asker's console",
        )
    }

    // ----- fan-out (slice c) --------------------------------------------------------------------

    /**
     * UX-13 slice (c) -- FAN-OUT: one sender dispatches to N agents by id; each copy is delivered to
     * its recipient, and the recipients' replies gather back into the ONE collecting thread, which is
     * how the sender collects answers. No new store, no new delivery seam -- only the grouping.
     */
    @Test
    fun a_fan_out_dispatches_to_n_agents_and_collects_replies_in_one_thread() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        val coordinator = h.manager.startNew("coordinator")
        h.createAgentProfile("w1", "Worker One", "backend")
        h.createAgentProfile("w2", "Worker Two", "backend")
        h.manager.startNew("w1 job", agentId = "w1", role = "backend")
        h.manager.startNew("w2 job", agentId = "w2", role = "backend")

        val sent = h.router.fanOut(
            MailFanOutRequest(from = coordinator.id, recipients = listOf("w1", "w2"), body = "report your status"),
        ) as FanOutResult.Sent

        assertEquals(2, sent.messages.size)
        val threadId = sent.messages.first().threadId
        assertTrue(sent.messages.all { it.threadId == threadId }, "one fan-out = one collecting thread")
        assertTrue(
            sent.messages.all { h.mailbox.message(it.id)!!.state == MessageState.DELIVERED },
            "every copy reaches its recipient's console",
        )

        // Each worker answers in the shared thread (a spawned agent replies AS the id it was addressed
        // by); both replies come back to the coordinator in that same thread.
        for (m in sent.messages) {
            h.router.send(MailSendRequest(from = m.to, to = coordinator.id, body = "status ok", replyTo = m.id))
        }

        val thread = h.mailbox.thread(threadId)
        assertEquals(4, thread.size)
        assertTrue(thread.all { it.delivered }, "both directions were delivered in the one thread")
        assertEquals(2, thread.count { it.replyTo != null }, "the sender gathers one reply per recipient")
    }

    /** A fan-out to no one is refused before anything is stored -- never a silent no-op. */
    @Test
    fun a_fan_out_with_no_recipients_is_refused() {
        val h = harness(ScriptedRunner())
        val result = h.router.fanOut(MailFanOutRequest(from = "human", recipients = emptyList(), body = "anyone?"))
        assertTrue(result is FanOutResult.Refused, "expected refusal, got $result")
    }

    /** Per-recipient delivery holds under fan-out: a busy agent's copy waits, an idle agent's lands now. */
    @Test
    fun a_fan_out_queues_a_busy_recipients_copy_until_it_is_free() {
        val runner = ScriptedRunner(autoExit = false)
        val h = harness(runner)
        h.createAgentProfile("busy-worker", "Busy", "backend")
        h.createAgentProfile("idle-worker", "Idle", "backend")
        h.manager.startNew("busy job", agentId = "busy-worker", role = "backend") // process[0], stays BUSY
        h.manager.startNew("idle job", agentId = "idle-worker", role = "backend") // process[1] -> IDLE
        runner.processes[1].exit(0)

        val sent = h.router.fanOut(
            MailFanOutRequest(from = "human", recipients = listOf("busy-worker", "idle-worker"), body = "do it"),
        ) as FanOutResult.Sent

        fun stateFor(recipient: String) = h.mailbox.message(sent.messages.first { it.to == recipient }.id)!!.state
        assertEquals(MessageState.QUEUED, stateFor("busy-worker"), "a busy recipient's copy waits")
        assertEquals(MessageState.DELIVERED, stateFor("idle-worker"), "an idle recipient's copy lands now")

        // The busy worker finishes; its deferred copy is drained by agent id when the session rests.
        runner.processes[0].exit(0)
        assertEquals(MessageState.DELIVERED, stateFor("busy-worker"))
    }

    /**
     * PATCH 0a197fe3 applied to fan-out: each of N recipients must receive its copy exactly once.
     * Fan-out is the re-entrancy case the exactly-once guard exists to stop -- N pumps in a row,
     * each resolving an agent id and resuming a console, each of which can fire a status event that
     * re-enters pump. A double delivery would resume the same console twice with the same body.
     */
    @Test
    fun a_fan_out_delivers_each_copy_exactly_once() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        h.createAgentProfile("w1", "Worker One", "backend")
        h.createAgentProfile("w2", "Worker Two", "backend")
        h.manager.startNew("w1 job", agentId = "w1", role = "backend")
        h.manager.startNew("w2 job", agentId = "w2", role = "backend")

        val sent = h.router.fanOut(
            MailFanOutRequest(from = "human", recipients = listOf("w1", "w2"), body = "exactly-once fan-out"),
        ) as FanOutResult.Sent

        assertEquals(2, sent.messages.size)
        for (m in sent.messages) {
            assertEquals(MessageState.DELIVERED, h.mailbox.message(m.id)!!.state)
        }
        val deliveryCount = runner.processes.count {
            it.input.contains("[agentHq mailbox]") && it.input.contains("exactly-once fan-out")
        }
        assertEquals(
            sent.messages.size,
            deliveryCount,
            "each recipient's copy must reach a console exactly once, but $deliveryCount deliveries were observed",
        )
    }

    // ----- the gate -----------------------------------------------------------------------------

    /**
     * Two agents negotiate a handoff with no human in the middle: A hands work to B and is blocked
     * waiting on it; B acknowledges and answers; A's signal clears. Each "agent turn" below is that
     * agent's own harness acting on its delivered mail -- there is no relay step.
     */
    @Test
    fun two_agents_negotiate_a_handoff_unattended() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)

        val frontend = h.manager.startNew("frontend agent")
        val backend = h.manager.startNew("backend agent")

        // 1. Frontend hands the backend work to the backend agent and is blocked waiting on it.
        val handoff = (
            h.router.send(
                MailSendRequest(
                    from = frontend.id,
                    to = backend.id,
                    subject = "Handoff: token rotation",
                    body = "Please implement the token rotation endpoint and reply when done.",
                    checkpoint = Checkpoint(
                        state = "UI done; backend endpoint missing",
                        decisions = "rotate on refresh, no re-login",
                        nextAction = "implement POST /token/rotate",
                    ),
                    needsInput = true,
                ),
            )
            ) as MailResult.Sent

        // The handoff reached the backend console, carrying its checkpoint.
        assertTrue(
            runner.processes.any {
                it.input.contains("[agentHq mailbox]") &&
                    it.input.contains("token rotation") &&
                    it.input.contains("implement POST /token/rotate")
            },
            "the handoff and its checkpoint must reach the backend agent",
        )
        assertEquals(MessageState.DELIVERED, h.mailbox.message(handoff.message.id)!!.state)

        // 2. Backend's turn: read it, do the work, answer in the same thread.
        h.mailbox.markRead(handoff.message.id)
        val answer = (
            h.router.send(
                MailSendRequest(
                    from = backend.id,
                    to = frontend.id,
                    body = "Done: POST /token/rotate shipped.",
                    replyTo = handoff.message.id,
                    checkpoint = Checkpoint(state = "endpoint shipped", evidence = "commit abc123"),
                ),
            )
            ) as MailResult.Sent

        // The answer went to the frontend's console and closed the frontend's question.
        assertEquals(MessageState.DELIVERED, h.mailbox.message(answer.message.id)!!.state)
        assertEquals(MessageState.ANSWERED, h.mailbox.message(handoff.message.id)!!.state)
        assertTrue(h.router.waiting().isEmpty(), "the frontend is no longer blocked")
        assertTrue(
            runner.processes.any { it.input.contains("[agentHq mailbox]") && it.input.contains("Done: POST /token/rotate") },
            "the answer must reach the frontend agent",
        )

        // 3. Frontend reads the answer.
        val read = h.mailbox.markRead(answer.message.id)!!
        assertEquals(MessageState.READ, read.state)

        // The whole negotiation is one ordered thread, every message delivered, no human relay.
        val thread = h.mailbox.thread(Threads.pair(frontend.id, backend.id))
        assertEquals(2, thread.size)
        assertEquals(listOf(1L, 2L), thread.map { it.seq })
        assertTrue(thread.all { it.delivered }, "both directions were actually delivered")
        assertEquals(MessageState.ANSWERED, thread.first().state)
        assertEquals(MessageState.READ, thread.last().state)
    }

    // ----- MAIL-1: role-based recipient resolution ------------------------------------------------

    @Test
    fun send_to_a_role_delivers_to_the_one_agent_with_that_role_in_the_project() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        h.createAgentProfile("pm-1", "PM", "pm")
        h.createAgentProfile("dev-1", "Dev", "dev")
        val pmSession = h.manager.startNew("pm agent", agentId = "pm-1", role = "pm", projectId = "proj-a")
        val devSession = h.manager.startNew("dev agent", agentId = "dev-1", role = "dev", projectId = "proj-a")

        val sent = h.router.send(
            MailSendRequest(from = devSession.id, to = "pm", body = "need your input on scope"),
        ) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        assertTrue(
            runner.processes.any { it.input.contains("[agentHq mailbox]") && it.input.contains("need your input") },
            "the message must reach the pm agent's console",
        )
    }

    @Test
    fun send_to_a_role_when_only_another_project_has_it_is_refused() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        h.createAgentProfile("pm-b", "PM B", "pm")
        h.createAgentProfile("dev-a", "Dev A", "dev")
        h.manager.startNew("pm agent B", agentId = "pm-b", role = "pm", projectId = "proj-b")
        val devSession = h.manager.startNew("dev agent A", agentId = "dev-a", role = "dev", projectId = "proj-a")

        val sent = h.router.send(
            MailSendRequest(from = devSession.id, to = "pm", body = "need your input on scope"),
        ) as MailResult.Sent

        val failed = h.mailbox.message(sent.message.id)!!
        assertEquals(MessageState.UNDELIVERABLE, failed.state)
        assertTrue(
            failed.failureReason!!.contains("no pm agent in this project"),
            "expected 'no pm agent in this project' but got: ${failed.failureReason}",
        )
    }

    // ----- PATCH 0a197fe3: exactly-once delivery -------------------------------------------------

    /**
     * A plain agent-id send must deliver the prompt to the console exactly once. The pump path
     * resolves the agent id to a session and resumes it; if the re-entrancy guard does not cover
     * the agent-alias drain, the same message is delivered twice through the nested pump.
     */
    @Test
    fun plain_agent_id_delivery_is_exactly_once() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        h.createAgentProfile("architect-agent", "Architect Agent", "architect")
        h.manager.startNew("bootstrap", agentId = "architect-agent", role = "architect")

        val sent = h.router.send(
            MailSendRequest(from = "human", to = "architect-agent", body = "exactly once"),
        ) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        val deliveryCount = runner.processes.count {
            it.input.contains("[agentHq mailbox]") && it.input.contains("exactly once")
        }
        assertEquals(1, deliveryCount, "the prompt must reach the console exactly once, but it was delivered $deliveryCount times")
    }

    /**
     * A role-addressed send must also deliver exactly once. The message is keyed by the role name
     * ("pm"), not by the resolved session id or agent id, so the agent-alias drain in the nested
     * pump cannot re-find it.
     */
    @Test
    fun role_addressed_delivery_is_exactly_once() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        h.createAgentProfile("pm-1", "PM", "pm")
        h.createAgentProfile("dev-1", "Dev", "dev")
        val pmSession = h.manager.startNew("pm agent", agentId = "pm-1", role = "pm", projectId = "proj-a")
        val devSession = h.manager.startNew("dev agent", agentId = "dev-1", role = "dev", projectId = "proj-a")

        val sent = h.router.send(
            MailSendRequest(from = devSession.id, to = "pm", body = "exactly once role"),
        ) as MailResult.Sent

        assertEquals(MessageState.DELIVERED, h.mailbox.message(sent.message.id)!!.state)
        val deliveryCount = runner.processes.count {
            it.input.contains("[agentHq mailbox]") && it.input.contains("exactly once role")
        }
        assertEquals(1, deliveryCount, "role-addressed prompt must reach the console exactly once, but it was delivered $deliveryCount times")
    }

    @Test
    fun send_to_a_role_with_several_matches_lists_all_ids() {
        val runner = ScriptedRunner(autoExit = true)
        val h = harness(runner)
        h.createAgentProfile("agent-pm-1", "PM 1", "pm")
        h.createAgentProfile("agent-pm-2", "PM 2", "pm")
        h.createAgentProfile("dev-1", "Dev", "dev")
        h.manager.startNew("pm agent 1", agentId = "agent-pm-1", role = "pm", projectId = "proj-a")
        h.manager.startNew("pm agent 2", agentId = "agent-pm-2", role = "pm", projectId = "proj-a")
        val devSession = h.manager.startNew("dev agent", agentId = "dev-1", role = "dev", projectId = "proj-a")

        val sent = h.router.send(
            MailSendRequest(from = devSession.id, to = "pm", body = "which pm?"),
        ) as MailResult.Sent

        val failed = h.mailbox.message(sent.message.id)!!
        assertEquals(MessageState.UNDELIVERABLE, failed.state)
        assertTrue(
            failed.failureReason!!.contains("agent-pm-1"),
            "expected 'agent-pm-1' in error but got: ${failed.failureReason}",
        )
        assertTrue(
            failed.failureReason!!.contains("agent-pm-2"),
            "expected 'agent-pm-2' in error but got: ${failed.failureReason}",
        )
    }
}
