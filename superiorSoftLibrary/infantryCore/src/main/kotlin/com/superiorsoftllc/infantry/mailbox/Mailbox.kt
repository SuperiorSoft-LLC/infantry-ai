package com.superiorsoftllc.infantry.mailbox

import java.util.UUID

/**
 * The mailbox: threads of messages between agents, with durable delivery and read receipts.
 *
 * It is deliberately transport-free and session-free. [Mailbox] owns *messages* -- their order in a
 * thread, their persistence, their delivery/read/answered state, and the boundaries around them --
 * and it hands delivery to a [MailDelivery] seam that the daemon implements on top of
 * [com.superiorsoftllc.infantry.session.SessionManager]. That split is what makes the whole thing
 * unit-testable with a fake delivery and keeps the "how does a session get a message" question out
 * of the mailbox itself.
 *
 * INVARIANT -- persist before deliver: [send] writes the message to the [MailStore] as
 * [MessageState.QUEUED] and only then can anyone try to deliver it. A daemon that dies between the
 * two loses no message; the next daemon loads it and delivers it when the recipient is available.
 *
 * THREADING: every mutation runs under one lock; nothing inside that lock calls out to a
 * [MailDelivery] or to [eventSink]. Delivery and event emission happen after the lock is released,
 * which is what keeps a delivery that re-enters the mailbox (a resume can emit a status event) from
 * deadlocking or recursing.
 *
 * @param limits the rate + ceiling boundaries. See [MailLimits].
 * @param clock wall clock in epoch millis; injectable for tests.
 * @param idFactory message id factory; injectable for deterministic tests.
 */
class Mailbox(
    private val store: MailStore,
    private val limits: MailLimits = MailLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Where mailbox events go. The daemon installs this to broadcast frames and to keep the *sender*
     * informed; a refusal or a permanent delivery failure is an event, never a silent drop.
     */
    @Volatile
    var eventSink: ((MailEvent) -> Unit)? = null

    private val lock = Any()
    private val messages = LinkedHashMap<String, Message>()
    private val seqByThread = HashMap<String, Long>()

    /**
     * Per-sender sliding windows of send timestamps, for the rate boundary.
     *
     * IN-MEMORY ONLY, AND AN OPEN PRODUCT QUESTION -- read this before trusting the boundary.
     *
     * Nothing here is persisted: these windows are not written to the [MailStore] and [load] does not
     * rebuild them, so a daemon restart hands every sender a full window again. A boundary enforced
     * here is therefore NOT a durable one -- "this sender is rate limited" is true only for as long as
     * the process enforcing it lives, and there is no record anywhere that it was ever reached.
     *
     * That may well be the right trade rather than an oversight: persisting the windows would put a
     * durable write on the path of every refused send, and would buy a rate limiter that outlives the
     * thing it is protecting. Nobody has decided it either way.
     *
     * So: DO NOT ASSUME the limit survives a restart, and do not add a test pinning either behaviour --
     * a test here would freeze one side of an undecided question. Decide the question first, then test
     * the answer. Until then the honest statement is "enforced, not durable".
     */
    private val sendHistory = HashMap<String, ArrayDeque<Long>>()

    /** Deep copy of a message list, sorted for stable output. Only called under [lock]. */
    private fun snapshot(list: Collection<Message>): List<Message> = list.toList()

    // --- startup --------------------------------------------------------------------------------

    /**
     * Load persisted messages (and rebuild the per-thread sequence counters) so a restart does not
     * forget a message that is still undelivered, or one that failed to deliver.
     */
    fun load() {
        synchronized(lock) {
            for (m in store.load()) {
                messages[m.id] = m
                val current = seqByThread[m.threadId] ?: 0L
                if (m.seq > current) seqByThread[m.threadId] = m.seq
            }
            persistLocked()
        }
    }

    // --- reads ----------------------------------------------------------------------------------

    fun message(id: String): Message? = synchronized(lock) { messages[id] }

    /** Every message in a thread, in thread order. */
    fun thread(threadId: String): List<Message> = synchronized(lock) {
        snapshot(messages.values.filter { it.threadId == threadId }.sortedBy { it.seq })
    }

    /** Messages addressed TO a session, oldest first, in every state (so a failed one is visible). */
    fun inbox(session: String): List<Message> = synchronized(lock) {
        snapshot(messages.values.filter { it.to == session }.sortedWith(compareBy({ it.sentAt }, { it.seq })))
    }

    /** Messages sent BY a session, oldest first. Where a sender sees its message go UNDELIVERABLE. */
    fun outbox(session: String): List<Message> = synchronized(lock) {
        snapshot(messages.values.filter { it.from == session }.sortedWith(compareBy({ it.sentAt }, { it.seq })))
    }

    /** All threads, most recently active first. */
    fun threads(): List<ThreadSummary> = synchronized(lock) { summariesLocked() }

    /**
     * Open needs-input signals: every message that flagged [Message.needsInput] and has no answer
     * yet. The caller (the router) narrows this to senders that are actually at rest -- an agent
     * still working is not "waiting".
     */
    fun waiting(): List<WaitingOn> = synchronized(lock) {
        messages.values
            .filter { it.needsInput && it.state != MessageState.ANSWERED && it.state != MessageState.UNDELIVERABLE }
            .sortedBy { it.sentAt }
            .map { WaitingOn(it.from, it.id, it.threadId, it.to, it.sentAt, it.subject) }
    }

    /** How many messages are queued for [recipient] but not yet delivered. */
    fun pendingCount(recipient: String): Int =
        synchronized(lock) { messages.values.count { it.to == recipient && it.state == MessageState.QUEUED } }

    // --- send -----------------------------------------------------------------------------------

    /**
     * Persist and record a message. Returns [MailResult.Sent] with the stored (QUEUED) message, or
     * [MailResult.Refused] with the reason -- a refused message is NOT stored, because storing it
     * would let it be delivered later and the boundary would have been cosmetic.
     *
     * When [MailSendRequest.replyTo] is set, the thread and the recipient are inherited from the
     * message being answered (a reply goes back to its sender, in the same thread, whatever the
     * caller passed) and that message becomes [MessageState.ANSWERED].
     */
    fun send(request: MailSendRequest): MailResult {
        val (result, events) = synchronized(lock) { sendLocked(request) }
        emit(events)
        return result
    }

    private fun sendLocked(request: MailSendRequest): Pair<MailResult, List<MailEvent>> {
        val from = request.from.trim()
        val to = request.to.trim()
        if (from.isEmpty() || to.isEmpty() || from == to) {
            return refuse(from, to, null, "sender and recipient must be two distinct, non-empty sessions")
        }
        if (request.body.isBlank() && request.subject.isBlank()) {
            return refuse(from, to, null, "a message needs a subject or a body")
        }

        val events = mutableListOf<MailEvent>()

        // A reply inherits its thread and its recipient from the message it answers.
        val original = request.replyTo?.let { messages[it] }
        if (request.replyTo != null && original == null) {
            return refuse(from, to, null, "unknown replyTo '${request.replyTo}'")
        }
        val replyTo = original?.id
        val recipient = original?.from ?: to
        val explicitThread = original?.threadId ?: Threads.normalize(request.threadId)
        // A NEW message on the implicit pair thread rolls over to pair:<a>|<b>:<n> once the current one is
        // full; a reply or an explicit thread id never does, so the loop guard below still holds.
        val threadId = explicitThread ?: openPairThread(Threads.pair(from, recipient))
        val subject = request.subject.ifBlank { original?.let { "Re: ${it.subject}" } ?: "" }

        // Boundary 1 -- the per-conversation ceiling. Refuse visibly and tell the humans.
        val inThread = messages.values.count { it.threadId == threadId }
        if (inThread >= limits.maxPerThread) {
            val reason = "conversation ceiling reached: thread '$threadId' has $inThread messages " +
                "(limit ${limits.maxPerThread}); stop and report to the human" +
                if (original != null) {
                    ". To continue, send a new message without replyTo (it starts a fresh thread)"
                } else {
                    ""
                }
            events += MailEvent.CeilingReached(threadId, limits.maxPerThread)
            events += MailEvent.Refused(from, recipient, reason)
            return MailResult.Refused(reason) to events
        }

        // Boundary 2 -- the sender rate limit, a sliding window.
        val now = clock()
        val window = sendHistory.getOrPut(from) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() > limits.windowMs) window.removeFirst()
        if (window.size >= limits.perSenderPerWindow) {
            val reason = "rate limit: '$from' already sent ${window.size} messages in the last " +
                "${limits.windowMs / 60_000} min (limit ${limits.perSenderPerWindow})"
            events += MailEvent.Refused(from, recipient, reason)
            return MailResult.Refused(reason) to events
        }

        val seq = (seqByThread[threadId] ?: 0L) + 1
        seqByThread[threadId] = seq
        val message = Message(
            id = idFactory(),
            threadId = threadId,
            seq = seq,
            from = from,
            to = recipient,
            subject = subject,
            body = request.body,
            sentAt = now,
            state = MessageState.QUEUED,
            replyTo = replyTo,
            checkpoint = request.checkpoint?.takeUnless { it.isEmpty },
            needsInput = request.needsInput,
        )
        messages[message.id] = message
        window.addLast(now)
        if (replyTo != null) {
            // Answering proves the original was delivered AND read, so record both receipts
            // honestly rather than asserting them earlier.
            val answered = original.copy(
                state = MessageState.ANSWERED,
                deliveredAt = original.deliveredAt ?: now,
                readAt = original.readAt ?: now,
                answeredAt = now,
            )
            messages[answered.id] = answered
            events += MailEvent.Changed(answered)
        }
        persistLocked()
        events += MailEvent.Sent(message)
        return MailResult.Sent(message) to events
    }

    /**
     * FAN-OUT: persist one message per recipient, all in a single collecting thread, atomically --
     * either every recipient's message is stored, or none are (a limit refusal never half-fires).
     *
     * Each fan-out message is an ordinary [Message], so the whole existing delivery/receipt/retry
     * machinery applies per recipient unchanged; only the *grouping* is new. Replies come back through
     * [replyTo] and land in the same collecting thread, which is how the sender gathers answers.
     *
     * A fan-out of N counts as N messages from the sender against both boundaries (rate limit and
     * per-thread ceiling), so a dispatch can never smuggle past a limit by batching.
     */
    fun fanOut(request: MailFanOutRequest): FanOutResult {
        val (result, events) = synchronized(lock) { fanOutLocked(request) }
        emit(events)
        return result
    }

    private fun fanOutLocked(request: MailFanOutRequest): Pair<FanOutResult, List<MailEvent>> {
        val from = request.from.trim()
        val recipients = request.recipients.map { it.trim() }.distinct().filter { it.isNotEmpty() }
        if (from.isEmpty() || recipients.isEmpty()) {
            return FanOutResult.Refused("fan-out needs a sender and at least one recipient") to emptyList()
        }
        if (recipients.any { it == from }) {
            return FanOutResult.Refused("a fan-out cannot address its own sender '$from'") to emptyList()
        }
        if (request.body.isBlank() && request.subject.isBlank()) {
            return FanOutResult.Refused("a message needs a subject or a body") to emptyList()
        }

        val events = mutableListOf<MailEvent>()
        val threadId = Threads.normalize(request.threadId) ?: Threads.fanOut(idFactory())

        // Boundary 1 -- the per-conversation ceiling, applied to the whole batch at once.
        val inThread = messages.values.count { it.threadId == threadId }
        if (inThread + recipients.size > limits.maxPerThread) {
            val reason = "conversation ceiling reached: thread '$threadId' would hold " +
                "${inThread + recipients.size} messages (limit ${limits.maxPerThread}); stop and report to the human"
            events += MailEvent.CeilingReached(threadId, limits.maxPerThread)
            return FanOutResult.Refused(reason) to events
        }

        // Boundary 2 -- the sender rate limit; a fan-out of N is N sends from the same sender.
        val now = clock()
        val window = sendHistory.getOrPut(from) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() > limits.windowMs) window.removeFirst()
        if (window.size + recipients.size > limits.perSenderPerWindow) {
            val reason = "rate limit: fan-out of ${recipients.size} from '$from' would exceed " +
                "${limits.perSenderPerWindow} in the last ${limits.windowMs / 60_000} min"
            events += MailEvent.Refused(from, recipients.joinToString(","), reason)
            return FanOutResult.Refused(reason) to events
        }

        val created = mutableListOf<Message>()
        for (recipient in recipients) {
            val seq = (seqByThread[threadId] ?: 0L) + 1
            seqByThread[threadId] = seq
            val message = Message(
                id = idFactory(),
                threadId = threadId,
                seq = seq,
                from = from,
                to = recipient,
                subject = request.subject,
                body = request.body,
                sentAt = now,
                state = MessageState.QUEUED,
                checkpoint = request.checkpoint?.takeUnless { it.isEmpty },
                needsInput = request.needsInput,
            )
            messages[message.id] = message
            created += message
        }
        repeat(recipients.size) { window.addLast(now) }
        persistLocked()
        created.forEach { events += MailEvent.Sent(it) }
        return FanOutResult.Sent(created) to events
    }

    private fun refuse(from: String, to: String, threadId: String?, reason: String): Pair<MailResult, List<MailEvent>> =
        MailResult.Refused(reason) to listOf(MailEvent.Refused(from, to, reason))

    // --- state transitions ----------------------------------------------------------------------

    /**
     * MAIL-HOOK-1: a pre-delivery hook handled a QUEUED message: it goes straight to READ (delivered and read at once), so no
     * wake or injection ever happens for it. Anything not QUEUED is left alone (null).
     */
    fun markHandled(messageId: String): Message? {
        val (events, result) = synchronized(lock) {
            val current = messages[messageId]
            if (current == null || current.state != MessageState.QUEUED) emptyList<MailEvent>() to null
            else {
                val now = clock()
                val next = current.copy(state = MessageState.READ, deliveredAt = now, readAt = now)
                messages[messageId] = next
                persistLocked()
                listOf<MailEvent>(MailEvent.Changed(next)) to next
            }
        }
        emit(events)
        return result
    }

    /**
     * The recipient acknowledged a message it had been given: DELIVERED -> READ. Reading requires
     * delivery, so a QUEUED message (never handed over) or an UNDELIVERABLE one returns null;
     * re-acknowledging a READ/ANSWERED message returns it unchanged. Idempotent.
     */
    fun markRead(messageId: String): Message? {
        val (events, result) = synchronized(lock) {
            val current = messages[messageId]
            when {
                current == null -> emptyList<MailEvent>() to null
                current.state == MessageState.READ || current.state == MessageState.ANSWERED ->
                    emptyList<MailEvent>() to current
                current.state == MessageState.DELIVERED -> {
                    val next = current.copy(state = MessageState.READ, readAt = clock())
                    messages[messageId] = next
                    persistLocked()
                    listOf<MailEvent>(MailEvent.Changed(next)) to next
                }
                else -> emptyList<MailEvent>() to null
            }
        }
        emit(events)
        return result
    }

    /**
     * Try to hand every QUEUED message for [recipient] to its console, oldest first, through
     * [delivery]. Stops at the first [DeliveryOutcome.Retry] so thread order is preserved, and
     * records a [DeliveryOutcome.Failed] permanently as [MessageState.UNDELIVERABLE].
     *
     * Order across threads is by send time; within a thread [Message.seq] already orders it.
     */
    /** MAIL-HANDLED-WAKE-1: ids a pre-delivery hook is still deciding; no pump may deliver or fold them meanwhile. */
    private val held = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun hold(messageId: String) { held.add(messageId) }
    fun release(messageId: String) { held.remove(messageId) }

    fun deliverQueued(recipient: String, delivery: MailDelivery) {
        while (true) {
            val queued = synchronized(lock) {
                messages.values
                    .filter { it.to == recipient && it.state == MessageState.QUEUED && it.id !in held }
                    .sortedWith(compareBy({ it.sentAt }, { it.seq }))
            }
            // USAGE-REGRESS-1: only a message that may wake the recipient starts a delivery; the others stay QUEUED
            // and ride in the next turn (folded into a waking delivery's [rest]).
            val next = queued.firstOrNull { delivery.wakes(it) } ?: return
            when (val outcome = delivery.deliver(next, queued.filter { it.id != next.id })) {
                is DeliveryOutcome.Delivered -> {
                    // USAGE-REGRESS-1: a delivery may fold later queued messages into the same wake turn.
                    val events = synchronized(lock) { (listOf(next.id) + outcome.alsoDelivered).flatMap { markDeliveredLocked(it) } }
                    emit(events)
                }
                is DeliveryOutcome.Retry -> return
                is DeliveryOutcome.Failed -> {
                    val events = synchronized(lock) { markUndeliverableLocked(next.id, outcome.reason) }
                    emit(events)
                }
            }
        }
    }

    /**
     * STALL-6: drops the still-QUEUED messages of [threadId] to [to] that [match]: they become [MessageState.UNDELIVERABLE]
     * with [reason] (visible, never silent) and are never delivered. Returns how many were dropped.
     */
    fun dropQueued(threadId: String, to: String, reason: String, match: (Message) -> Boolean = { true }): Int {
        val events = synchronized(lock) {
            messages.values.filter { it.threadId == threadId && it.to == to && it.state == MessageState.QUEUED && match(it) }
                .map { it.id }.flatMap { markUndeliverableLocked(it, reason) }
        }
        emit(events)
        return events.size
    }

    private fun markDeliveredLocked(messageId: String): List<MailEvent> {
        val current = messages[messageId] ?: return emptyList()
        if (current.state != MessageState.QUEUED) return emptyList()
        val next = current.copy(state = MessageState.DELIVERED, deliveredAt = clock())
        messages[messageId] = next
        persistLocked()
        return listOf(MailEvent.Changed(next))
    }

    private fun markUndeliverableLocked(messageId: String, reason: String): List<MailEvent> {
        val current = messages[messageId] ?: return emptyList()
        if (current.state != MessageState.QUEUED) return emptyList()
        val next = current.copy(state = MessageState.UNDELIVERABLE, failureReason = reason)
        messages[messageId] = next
        persistLocked()
        return listOf(MailEvent.Changed(next))
    }

    // --- internals ------------------------------------------------------------------------------

    /** The newest rollover of [base] that still has room (the base thread itself is roll 1). */
    private fun openPairThread(base: String): String {
        var n = 1
        while (true) {
            val id = Threads.rolled(base, n)
            if (messages.values.count { it.threadId == id } < limits.maxPerThread) return id
            n++
        }
    }

    private fun summariesLocked(): List<ThreadSummary> {
        val byThread = messages.values.groupBy { it.threadId }
        return byThread.map { (id, msgs) ->
            val ordered = msgs.sortedBy { it.seq }
            val participants = Threads.pairMembers(id).ifEmpty {
                ordered.flatMap { listOf(it.from, it.to) }.distinct().sorted()
            }
            ThreadSummary(
                id = id,
                kind = Threads.kindOf(id),
                participants = participants,
                group = Threads.groupOf(id),
                messageCount = msgs.size,
                lastAt = msgs.maxOf { it.sentAt },
                ceilingReached = msgs.size >= limits.maxPerThread,
                openNeedsInput = msgs.count {
                    it.needsInput && it.state != MessageState.ANSWERED && it.state != MessageState.UNDELIVERABLE
                },
            )
        }.sortedByDescending { it.lastAt }
    }

    private fun persistLocked() {
        runCatching { store.saveAll(messages.values) }
            .onFailure { System.err.println("[infantry] mail persist failed: ${it.message}") }
    }

    private fun emit(events: List<MailEvent>) {
        val sink = eventSink ?: return
        for (event in events) runCatching { sink(event) }
    }
}

/**
 * The boundaries around a conversation. Both are checked in [Mailbox.send] BEFORE anything is
 * stored, so a message that is refused is never delivered later: the boundary is real, not cosmetic.
 *
 * Defaults are the budget the owner already works with (`12` messages reaching `30` recipients
 * per 10 min, and a per-conversation message ceiling). A conversation that hits the
 * ceiling does not truncate silently -- the send is refused and a [MailEvent.CeilingReached] tells
 * the humans it is time to step in.
 */
data class MailLimits(
    val perSenderPerWindow: Int = 12,
    val windowMs: Long = 10 * 60_000L,
    val maxPerThread: Int = 60,
)

/** The outcome of [Mailbox.send]. */
sealed interface MailResult {
    data class Sent(val message: Message) : MailResult
    data class Refused(val reason: String) : MailResult
}

/** A mailbox event the daemon turns into a frame (or a log line). */
sealed interface MailEvent {
    /** A message was persisted (QUEUED). */
    data class Sent(val message: Message) : MailEvent

    /** A message changed state (DELIVERED / READ / ANSWERED / UNDELIVERABLE). */
    data class Changed(val message: Message) : MailEvent

    /** A send was refused (rate limit, ceiling, bad input) -- visible, never a silent drop. */
    data class Refused(val from: String, val to: String, val reason: String) : MailEvent

    /** A conversation hit its ceiling; the humans are told to step in. */
    data class CeilingReached(val threadId: String, val limit: Int) : MailEvent
}

/** What happened when the mailbox tried to hand one message to a recipient's console. */
sealed interface DeliveryOutcome {
    /** Handed over; the message is recorded DELIVERED. */
    data class Delivered(val note: String = "", val alsoDelivered: List<String> = emptyList()) : DeliveryOutcome

    /** The recipient cannot take it yet (busy) -- keep it QUEUED and try again later. */
    data class Retry(val reason: String) : DeliveryOutcome

    /** It can never be delivered (unknown recipient, not resumable, start refused) -- record it. */
    data class Failed(val reason: String) : DeliveryOutcome
}

/** The delivery seam [Mailbox] drives. The daemon implements it over `SessionManager`. */
fun interface MailDelivery {
    fun deliver(message: Message): DeliveryOutcome

    /**
     * USAGE-REGRESS-1: [rest] = the other QUEUED messages for the same recipient, oldest first. A delivery that folds
     * some into one wake turn reports their ids in [DeliveryOutcome.Delivered.alsoDelivered]; the default folds none.
     */
    fun deliver(message: Message, rest: List<Message>): DeliveryOutcome = deliver(message)

    /** USAGE-REGRESS-1: false = this message must not start a wake (it waits for the next turn). */
    fun wakes(message: Message): Boolean = true
}
