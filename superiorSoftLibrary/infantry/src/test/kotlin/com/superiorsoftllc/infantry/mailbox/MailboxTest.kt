package com.superiorsoftllc.infantry.mailbox

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 gate, at the mailbox level: threads read in order, delivery is durable and explicit,
 * read/answered receipts are distinguishable, a checkpoint survives a restart, a needs-input signal
 * is real (and clears on answer), and the boundaries refuse visibly instead of truncating silently.
 */
class MailboxTest {

    private class MemStore : MailStore {
        var rows: List<Message> = emptyList()
        override fun load(): List<Message> = rows
        override fun saveAll(messages: Collection<Message>) {
            rows = messages.toList()
        }
    }

    private fun tempFile(): File = File.createTempFile("infantry-mail", ".json").apply { delete() }

    private fun mailbox(
        store: MailStore = MemStore(),
        limits: MailLimits = MailLimits(),
        events: MutableList<MailEvent> = mutableListOf(),
    ): Pair<Mailbox, MutableList<MailEvent>> {
        val mb = Mailbox(store, limits)
        mb.eventSink = { events += it }
        return mb to events
    }

    // ----- threads ------------------------------------------------------------------------------

    @Test
    fun messages_read_in_thread_order_and_are_persisted_queued() {
        val store = MemStore()
        val (mb, _) = mailbox(store)
        mb.send(MailSendRequest(from = "A", to = "B", subject = "one", body = "first"))
        mb.send(MailSendRequest(from = "A", to = "B", subject = "two", body = "second"))

        val thread = mb.thread(Threads.pair("A", "B"))
        assertEquals(listOf(1L, 2L), thread.map { it.seq })
        assertEquals(listOf("first", "second"), thread.map { it.body })
        assertTrue(thread.all { it.state == MessageState.QUEUED })
        assertEquals(2, store.rows.size, "every message must be persisted before delivery")
    }

    @Test
    fun pair_thread_is_order_independent() {
        val (mb, _) = mailbox()
        mb.send(MailSendRequest(from = "A", to = "B", body = "hi"))
        mb.send(MailSendRequest(from = "B", to = "A", body = "hello"))
        assertEquals(Threads.pair("A", "B"), Threads.pair("B", "A"))
        assertEquals(2, mb.thread(Threads.pair("A", "B")).size)
        assertEquals(2L, mb.thread(Threads.pair("A", "B")).last().seq)
    }

    @Test
    fun explicit_run_thread_is_honoured() {
        val (mb, _) = mailbox()
        val sent = mb.send(MailSendRequest(from = "A", to = "B", body = "step 1", threadId = "run:42"))
        val message = (sent as MailResult.Sent).message
        assertEquals("run:42", message.threadId)
        assertEquals(ThreadKind.RUN, Threads.kindOf(message.threadId))
        assertEquals("run:42", mb.threads().single().id)
    }

    // ----- delivery -----------------------------------------------------------------------------

    @Test
    fun deliverQueued_records_each_outcome() {
        val (mb, events) = mailbox()
        val delivered = (mb.send(MailSendRequest(from = "A", to = "B", body = "d")) as MailResult.Sent).message
        val failed = (mb.send(MailSendRequest(from = "A", to = "B", body = "f")) as MailResult.Sent).message
        // The retry is sent last so it is the one that stops the pass, exercising both paths.
        val retried = (mb.send(MailSendRequest(from = "A", to = "B", body = "r")) as MailResult.Sent).message

        var calls = 0
        val delivery = MailDelivery { message ->
            calls++
            when (message.id) {
                delivered.id -> DeliveryOutcome.Delivered()
                retried.id -> DeliveryOutcome.Retry("busy")
                else -> DeliveryOutcome.Failed("recipient is not resumable")
            }
        }
        mb.deliverQueued("B", delivery)

        assertEquals(MessageState.DELIVERED, mb.message(delivered.id)!!.state)
        assertNotNull(mb.message(delivered.id)!!.deliveredAt)
        assertEquals(MessageState.UNDELIVERABLE, mb.message(failed.id)!!.state)
        assertEquals("recipient is not resumable", mb.message(failed.id)!!.failureReason)
        // A retry keeps the message queued AND stops the pass, preserving order.
        assertEquals(MessageState.QUEUED, mb.message(retried.id)!!.state)
        assertEquals(3, calls)
        assertTrue(events.any { it is MailEvent.Changed && it.message.id == failed.id && it.message.failed })
    }

    @Test
    fun usage_regress_1_a_delivery_may_fold_the_rest_of_the_queue_into_one_wake() {
        val (mb, _) = mailbox()
        val ids = (1..3).map { (mb.send(MailSendRequest(from = "A", to = "B", body = "m$it")) as MailResult.Sent).message.id }
        var wakes = 0
        val delivery = object : MailDelivery {
            override fun deliver(message: Message): DeliveryOutcome = DeliveryOutcome.Delivered()
            override fun deliver(message: Message, rest: List<Message>): DeliveryOutcome {
                wakes++
                return DeliveryOutcome.Delivered("", rest.map { it.id })
            }
        }
        mb.deliverQueued("B", delivery)
        assertEquals(1, wakes)
        ids.forEach { assertEquals(MessageState.DELIVERED, mb.message(it)!!.state) }
    }

    @Test
    fun usage_regress_1_a_non_waking_message_waits_and_rides_with_the_next_waking_one() {
        val (mb, _) = mailbox()
        val note = (mb.send(MailSendRequest(from = "system", to = "B", body = "bg job finished")) as MailResult.Sent).message
        val delivery = object : MailDelivery {
            var rests = emptyList<String>()
            override fun deliver(message: Message): DeliveryOutcome = DeliveryOutcome.Delivered()
            override fun wakes(message: Message) = message.from != "system"
            override fun deliver(message: Message, rest: List<Message>): DeliveryOutcome {
                rests = rest.map { it.id }
                return DeliveryOutcome.Delivered("", rests)
            }
        }
        mb.deliverQueued("B", delivery)
        assertEquals(MessageState.QUEUED, mb.message(note.id)!!.state)
        val real = (mb.send(MailSendRequest(from = "A", to = "B", body = "report")) as MailResult.Sent).message
        mb.deliverQueued("B", delivery)
        assertEquals(listOf(note.id), delivery.rests)
        assertEquals(MessageState.DELIVERED, mb.message(note.id)!!.state)
        assertEquals(MessageState.DELIVERED, mb.message(real.id)!!.state)
    }

    @Test
    fun delivered_then_retry_pass_resumes_from_the_front() {
        val (mb, _) = mailbox()
        val first = (mb.send(MailSendRequest(from = "A", to = "B", body = "1")) as MailResult.Sent).message
        val second = (mb.send(MailSendRequest(from = "A", to = "B", body = "2")) as MailResult.Sent).message

        // First pass: recipient busy, nothing delivered.
        mb.deliverQueued("B") { DeliveryOutcome.Retry("busy") }
        assertEquals(MessageState.QUEUED, mb.message(first.id)!!.state)

        // Second pass: recipient free; both delivered in order.
        val order = mutableListOf<String>()
        mb.deliverQueued("B") { m -> order += m.id; DeliveryOutcome.Delivered() }
        assertEquals(listOf(first.id, second.id), order)
        assertEquals(MessageState.DELIVERED, mb.message(second.id)!!.state)
    }

    // ----- receipts -----------------------------------------------------------------------------

    @Test
    fun read_is_distinct_from_delivered_and_from_answered() {
        val (mb, _) = mailbox()
        val sent = (mb.send(MailSendRequest(from = "A", to = "B", body = "please review")) as MailResult.Sent).message

        assertFalse(sent.delivered)
        assertFalse(sent.read)
        assertFalse(sent.answered)

        assertNull(mb.markRead(sent.id), "an undelivered message cannot be read")

        mb.deliverQueued("B") { DeliveryOutcome.Delivered() }
        val delivered = mb.message(sent.id)!!
        assertTrue(delivered.delivered)
        assertFalse(delivered.read, "delivered is not read")

        val read = mb.markRead(sent.id)!!
        assertEquals(MessageState.READ, read.state)
        assertTrue(read.read)
        assertFalse(read.answered, "read is not answered")
    }

    @Test
    fun reply_answers_the_original_and_forces_recipient_and_thread() {
        val (mb, _) = mailbox()
        val original = (
            mb.send(MailSendRequest(from = "A", to = "B", subject = "question", body = "please review"))
            as MailResult.Sent
            ).message

        // B replies; even though the caller passes a wrong `to` and a wrong thread, a reply goes back
        // to the original sender, in the original thread.
        val reply = mb.send(
            MailSendRequest(from = "B", to = "SOMEONE_ELSE", threadId = "run:other", body = "answer", replyTo = original.id),
        ) as MailResult.Sent

        assertEquals("A", reply.message.to)
        assertEquals(original.threadId, reply.message.threadId)
        assertEquals("Re: question", reply.message.subject)
        assertEquals(original.id, reply.message.replyTo)

        val answered = mb.message(original.id)!!
        assertEquals(MessageState.ANSWERED, answered.state)
        assertTrue(answered.answered && answered.read && answered.delivered)
        assertNotNull(answered.answeredAt)
    }

    // ----- durable state / checkpoint -----------------------------------------------------------

    @Test
    fun checkpoint_is_attached_and_survives_a_restart() {
        val file = tempFile()
        val boxA = Mailbox(JsonMailStore(file))
        val sent = boxA.send(
            MailSendRequest(
                from = "A",
                to = "B",
                body = "handoff",
                checkpoint = Checkpoint(state = "half done", decisions = "use X", nextAction = "do Y"),
            ),
        ) as MailResult.Sent

        // A brand-new mailbox (a restarted daemon) reads it back, checkpoint intact.
        val boxB = Mailbox(JsonMailStore(file))
        boxB.load()
        val restored = boxB.message(sent.message.id)
        assertNotNull(restored)
        assertEquals("half done", restored.checkpoint?.state)
        assertEquals("do Y", restored.checkpoint?.nextAction)
    }

    @Test
    fun restart_continues_thread_sequence_without_collision() {
        val file = tempFile()
        val boxA = Mailbox(JsonMailStore(file))
        boxA.send(MailSendRequest(from = "A", to = "B", body = "1"))
        boxA.send(MailSendRequest(from = "A", to = "B", body = "2"))

        val boxB = Mailbox(JsonMailStore(file))
        boxB.load()
        val third = (boxB.send(MailSendRequest(from = "A", to = "B", body = "3")) as MailResult.Sent).message
        assertEquals(3L, third.seq)
    }

    // ----- needs-input --------------------------------------------------------------------------

    @Test
    fun needsInput_is_signalled_and_cleared_by_the_answer() {
        val (mb, _) = mailbox()
        val question = mb.send(
            MailSendRequest(from = "A", to = "B", body = "blocked on you", needsInput = true),
        ) as MailResult.Sent

        assertEquals(listOf("A"), mb.waiting().map { it.session })
        assertEquals(question.message.id, mb.waiting().single().messageId)

        mb.send(MailSendRequest(from = "B", to = "A", body = "unblocked", replyTo = question.message.id))
        assertTrue(mb.waiting().isEmpty(), "answering the question clears the signal")
    }

    // ----- boundaries ---------------------------------------------------------------------------

    @Test
    fun rate_limit_refuses_and_records_nothing() {
        val (mb, events) = mailbox(limits = MailLimits(perSenderPerWindow = 2, windowMs = 600_000))
        assertTrue(mb.send(MailSendRequest(from = "A", to = "B", body = "1")) is MailResult.Sent)
        assertTrue(mb.send(MailSendRequest(from = "A", to = "B", body = "2")) is MailResult.Sent)
        val third = mb.send(MailSendRequest(from = "A", to = "B", body = "3"))

        assertTrue(third is MailResult.Refused)
        assertTrue((third as MailResult.Refused).reason.contains("rate limit"))
        assertEquals(2, mb.outbox("A").size, "a refused message must not be stored (it could be delivered later)")
        assertTrue(events.any { it is MailEvent.Refused })
        // Another sender is unaffected by A's budget.
        assertTrue(mb.send(MailSendRequest(from = "C", to = "B", body = "hi")) is MailResult.Sent)
    }

    @Test
    fun new_message_on_a_full_pair_rolls_over_and_system_notices_never_hit_the_ceiling() {
        val (mb, _) = mailbox(limits = MailLimits(maxPerThread = 3, perSenderPerWindow = 1000))
        repeat(7) { assertTrue(mb.send(MailSendRequest(from = "system", to = "PM", body = "n$it")) is MailResult.Sent) }
        val ids = mb.threads().map { it.id }.toSet()
        assertEquals(setOf("pair:PM|system", "pair:PM|system:2", "pair:PM|system:3"), ids)
        assertEquals(setOf("pair:PM|system"), mb.threads().map { it.group }.toSet())
        assertEquals(listOf("PM", "system"), mb.threads().first().participants)
    }

    @Test
    fun thread_ceiling_refuses_and_tells_the_human_to_step_in() {
        val (mb, events) = mailbox(limits = MailLimits(maxPerThread = 2))
        assertTrue(mb.send(MailSendRequest(from = "A", to = "B", body = "1")) is MailResult.Sent)
        assertTrue(mb.send(MailSendRequest(from = "B", to = "A", body = "2")) is MailResult.Sent)

        val last = mb.thread(Threads.pair("A", "B")).last()
        val third = mb.send(MailSendRequest(from = "A", to = "B", body = "3", replyTo = last.id))
        assertTrue(third is MailResult.Refused)
        assertTrue((third as MailResult.Refused).reason.contains("without replyTo"))
        assertTrue(third.reason.contains("conversation ceiling"))
        assertTrue(events.any { it is MailEvent.CeilingReached })
        assertTrue(mb.threads().single().ceilingReached)
        // Still only two messages: the ceiling is real, not a silent truncation of the third.
        assertEquals(2, mb.thread(Threads.pair("A", "B")).size)
    }

    @Test
    fun replyTo_accepts_full_id_and_unique_prefix_but_refuses_ambiguous_and_unknown() {
        val ids = ArrayDeque(listOf("df422723-aaaa", "df422999-bbbb", "ab123456-cccc", "r1", "r2", "r3", "r4"))
        val mb = Mailbox(MemStore(), MailLimits(), idFactory = { ids.removeFirst() })
        // a cross-project sender/recipient pair: the lookup is by message id, never by project
        val m1 = (mb.send(MailSendRequest(from = "p1:pm", to = "p2:pm", body = "ping")) as MailResult.Sent).message
        mb.send(MailSendRequest(from = "p1:pm", to = "p2:pm", body = "ping2"))
        val m3 = (mb.send(MailSendRequest(from = "p1:pm", to = "p2:pm", body = "ping3")) as MailResult.Sent).message

        val ambiguous = mb.send(MailSendRequest(from = "p2:pm", to = "x", body = "r", replyTo = "df422"))
        assertTrue(ambiguous is MailResult.Refused && ambiguous.reason.contains("ambiguous"))
        val unknown = mb.send(MailSendRequest(from = "p2:pm", to = "x", body = "r", replyTo = "zz"))
        assertTrue(unknown is MailResult.Refused && unknown.reason.contains("unknown replyTo"))
        val byPrefix = mb.send(MailSendRequest(from = "p2:pm", to = "x", body = "r", replyTo = "ab123456")) as MailResult.Sent
        assertEquals(m3.id, byPrefix.message.replyTo)
        assertEquals("p1:pm", byPrefix.message.to)
        val byFull = mb.send(MailSendRequest(from = "p2:pm", to = "x", body = "r", replyTo = m1.id)) as MailResult.Sent
        assertEquals(m1.id, byFull.message.replyTo)
        val byUniquePrefix = mb.send(MailSendRequest(from = "p2:pm", to = "x", body = "r", replyTo = "df422723")) as MailResult.Sent
        assertEquals(m1.id, byUniquePrefix.message.replyTo)
    }

    @Test
    fun invalid_send_is_refused() {
        val (mb, _) = mailbox()
        assertTrue(mb.send(MailSendRequest(from = "A", to = "A", body = "self")) is MailResult.Refused)
        assertTrue(mb.send(MailSendRequest(from = "A", to = "B", body = "  ")) is MailResult.Refused)
        assertTrue(mb.send(MailSendRequest(from = "A", to = "B", body = "x", replyTo = "nope")) is MailResult.Refused)
    }
}
