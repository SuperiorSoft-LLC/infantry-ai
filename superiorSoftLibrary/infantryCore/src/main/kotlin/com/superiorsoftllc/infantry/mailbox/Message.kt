// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import kotlinx.serialization.Serializable

/**
 * Phase 6: the mailbox -- agent-to-agent messaging with threads.
 *
 * This is what separates Infantry from a parallel runner: two agents negotiate a handoff by talking
 * to each other, instead of a human relaying every step. Everything in the mailbox is designed
 * against the two delivery failures this project actually hit:
 *
 *  1. an agent neared its step limit and went **silent** -- nobody waiting on it had a signal;
 *  2. a QA run lost its whole report when it reported **once at the end** and a harness ceiling cut
 *     it off -- per-check delivery would have survived.
 *
 * Both are delivery problems, so the mailbox makes delivery a first-class, persistent, *visible*
 * thing: a message is written to disk before anyone tries to hand it over (so a message to an
 * offline agent is never lost), and a delivery that can never happen becomes an explicit
 * [MessageState.UNDELIVERABLE] row rather than silence.
 */

/**
 * Where one message is in its life. The ordered remainder is QUEUED -> DELIVERED -> READ ->
 * ANSWERED; [UNDELIVERABLE] is the terminal failure branch.
 *
 * The three you must be able to tell apart are **delivered**, **read** and **answered**: silence has
 * two opposite causes -- "not delivered yet" and "delivered, read, and deliberately not answered" --
 * and from the sender's side they look identical. Guessing wrong is expensive, so each one is its
 * own state rather than a boolean. See [Message.delivered], [Message.read], [Message.answered].
 */
@Serializable
enum class MessageState {
    /** Persisted and waiting for the recipient's console to become available. Never lost. */
    QUEUED,

    /** Handed to the recipient's console (its run was started carrying this message). */
    DELIVERED,

    /** The recipient acknowledged it, without (yet) answering. */
    READ,

    /** A reply exists in this thread. Clears a [Message.needsInput] signal. */
    ANSWERED,

    /**
     * Delivery failed permanently and the failure is RECORDED, not swallowed: an unknown recipient,
     * a recipient that cannot be resumed, or a run that refused to start. Terminal.
     */
    UNDELIVERABLE,
}

/** What a thread is keyed by. Threads read in order, whatever their kind. */
@Serializable
enum class ThreadKind {
    /** Two agents (a deterministic, order-independent pair of session ids). */
    AGENT_PAIR,

    /** One orchestration run (Phase 5 and later). */
    RUN,

    /** A pod -- a group of runs that hand off among themselves. */
    POD,

    /** Any other explicitly named thread. */
    CUSTOM,
}

/**
 * Durable state a handoff can carry, so it survives a context reset.
 *
 * This is the field that makes "the next agent starts from where the last one stopped" true even
 * when the receiving console has no memory of the sender's conversation. Every part is optional;
 * an empty checkpoint is the same as no checkpoint.
 */
@Serializable
data class Checkpoint(
    /** Where the work stands right now. */
    val state: String? = null,
    /** Decisions already taken, so nobody reopens them. */
    val decisions: String? = null,
    /** Proof: commits, files, test results. */
    val evidence: String? = null,
    /** What is in the way, and who can lift it. */
    val blockers: String? = null,
    /** The single next step, and who owns it. */
    val nextAction: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(state, decisions, evidence, blockers, nextAction).all { it.isNullOrBlank() }

    companion object {
        val NONE = Checkpoint()
    }
}

/**
 * One mailbox message. Both the internal model and the wire model (the same one-model rule
 * [com.superiorsoftllc.infantry.session.SessionRecord] follows).
 *
 * [seq] is monotonic WITHIN a thread, which is what makes a thread read in order even when two
 * messages share a millisecond. [threadId] groups messages; [replyTo] links an answer to the
 * message it answers (and always lives in the same thread).
 */
@Serializable
data class Message(
    val id: String,
    val threadId: String,
    /** Position in the thread, 1-based, strictly increasing within [threadId]. */
    val seq: Long,
    /** Sender session id, or a non-session handle such as `human` / `system`. */
    val from: String,
    /** Recipient session id. */
    val to: String,
    val subject: String = "",
    val body: String,
    /** Epoch millis the message was persisted (before any delivery attempt). */
    val sentAt: Long,
    val state: MessageState = MessageState.QUEUED,
    /** The message this one answers, when it is a reply. */
    val replyTo: String? = null,
    val checkpoint: Checkpoint? = null,
    /**
     * The sender declares it is BLOCKED awaiting an answer. Cleared when this message is ANSWERED.
     * Deliberately explicit: Infantry never infers "waiting" from a quiet agent, because a false
     * "waiting on you" erodes trust in the signal faster than no signal at all.
     */
    val needsInput: Boolean = false,
    val deliveredAt: Long? = null,
    val readAt: Long? = null,
    val answeredAt: Long? = null,
    /** Why delivery failed, when [state] is [MessageState.UNDELIVERABLE]. */
    val failureReason: String? = null,
) {
    /** Handed to the recipient's console (whether or not it was read). */
    val delivered: Boolean
        get() = deliveredAt != null || state == MessageState.DELIVERED ||
            state == MessageState.READ || state == MessageState.ANSWERED

    /** The recipient acknowledged it. Answering proves reading, so ANSWERED implies read. */
    val read: Boolean
        get() = readAt != null || state == MessageState.READ || state == MessageState.ANSWERED

    /** A reply exists in the thread. */
    val answered: Boolean get() = state == MessageState.ANSWERED

    /** Delivery failed permanently; the reason is in [failureReason]. */
    val failed: Boolean get() = state == MessageState.UNDELIVERABLE
}

/**
 * A thread as a client sees it: who is in it, how big it is, whether it hit its ceiling, and how
 * many questions are still open in it.
 */
@Serializable
data class ThreadSummary(
    val id: String,
    val kind: ThreadKind,
    /** Known participants: the pair's session ids for an agent-pair thread, else the senders/recipients. */
    val participants: List<String>,
    /** The conversation this thread belongs to: a rolled-over pair thread shares its group (`pair:<a>|<b>`) with its predecessors. */
    val group: String = id,
    val messageCount: Int,
    val lastAt: Long,
    /** True once the conversation ceiling was hit; further sends are refused visibly. */
    val ceilingReached: Boolean,
    /** Messages in this thread that flagged [Message.needsInput] and have no answer yet. */
    val openNeedsInput: Int,
)

/**
 * One open needs-input signal, derived from a message that flagged [Message.needsInput] and has not
 * been answered. A waiting agent is one that asked a question and has nothing else in flight.
 */
@Serializable
data class WaitingOn(
    /** The session that is blocked. */
    val session: String,
    /** The message carrying the question. */
    val messageId: String,
    val threadId: String,
    /** The session it is waiting on. */
    val waitingOn: String,
    val since: Long,
    val subject: String,
)

/**
 * The payload of a `mail_send` frame. `from` is normally the connection's active session, but it is
 * explicit so a coordinator or a test can send on a session's behalf.
 */
@Serializable
data class MailSendRequest(
    val from: String,
    val to: String,
    val subject: String = "",
    val body: String,
    /** Optional explicit thread (`run:<id>` / `pod:<id>` / any name). Defaults to the agent pair. */
    val threadId: String? = null,
    /** Set to answer a specific message; the thread and recipient are inherited from it. */
    val replyTo: String? = null,
    val checkpoint: Checkpoint? = null,
    val needsInput: Boolean = false,
    /**
     * Contract 1.100.0: explicit override of the cross-project guard. Without it a session sender mailing a session or
     * agent of ANOTHER project is refused at send time; with it the message goes through. Role names are unaffected:
     * they only ever resolve inside the sender's own project.
     */
    val allowCrossProject: Boolean = false,
)

/** A refused send, echoed to the sender so a limit is never a silent drop. */
@Serializable
data class MailRefused(
    val from: String,
    val to: String,
    val threadId: String? = null,
    val reason: String,
)

/**
 * The payload of a `mail_read` call (the WS frame carries the bare id; the HTTP and agent-facing
 * surfaces carry this so the acknowledging session is named for the audit trail).
 */
@Serializable
data class MailReadRequest(
    val messageId: String,
    /** The session acknowledging; optional. */
    val session: String? = null,
)

/**
 * The payload of a `mail_fanout` call: one sender dispatching the same task to N recipients, whose
 * replies all collect in a single thread. This is the "FAN-OUT" slice of agent-to-agent
 * communication (one -> many, gather answers), built on the same [Message] rows and delivery policy
 * as a one-to-one [MailSendRequest] -- no new store, no new delivery seam.
 *
 * The mechanism, not the policy: how the sender decides the fan-out is complete (all answered? a
 * quorum? first answer?) is deliberately left out -- that is an orchestration design question.
 */
@Serializable
data class MailFanOutRequest(
    val from: String,
    /** The N recipients, each a session id or an agent profile id. Deduplicated by the mailbox. */
    val recipients: List<String>,
    val subject: String = "",
    val body: String,
    /** Optional explicit thread; defaults to a fresh `fanout:<id>` collecting thread. */
    val threadId: String? = null,
    val checkpoint: Checkpoint? = null,
    val needsInput: Boolean = false,
)

/** The outcome of [Mailbox.fanOut]. */
sealed interface FanOutResult {
    /** One [Message] per recipient, all sharing one collecting thread, persisted as QUEUED. */
    data class Sent(val messages: List<Message>) : FanOutResult

    /** Refused before anything was stored: bad input, or a limit hit. */
    data class Refused(val reason: String) : FanOutResult
}

/** The names of the threads this system builds. */
object Threads {
    /**
     * The deterministic agent-pair thread for two sessions: sorted so `A->B` and `B->A` land in the
     * same conversation, which is what makes a negotiation readable end to end.
     */
    fun pair(a: String, b: String): String {
        val (x, y) = if (a <= b) a to b else b to a
        return "pair:$x|$y"
    }

    private val rollSuffix = Regex(""":\d+$""")

    /** Roll [n] of a pair thread: roll 1 is [base] itself, later rolls append `:<n>`. */
    fun rolled(base: String, n: Int): String = if (n <= 1) base else "$base:$n"

    /** The pair thread a rolled-over id belongs to; any other id is its own group. */
    fun groupOf(threadId: String): String =
        if (threadId.startsWith("pair:")) threadId.replace(rollSuffix, "") else threadId

    fun run(id: String): String = "run:$id"

    fun pod(id: String): String = "pod:$id"

    /** A fan-out collecting thread: one sender, N recipients, their replies gathered here. */
    fun fanOut(id: String): String = "fanout:$id"

    fun kindOf(threadId: String): ThreadKind = when {
        threadId.startsWith("pair:") -> ThreadKind.AGENT_PAIR
        threadId.startsWith("run:") -> ThreadKind.RUN
        threadId.startsWith("pod:") -> ThreadKind.POD
        else -> ThreadKind.CUSTOM
    }

    /** The two session ids of an agent-pair thread, or empty for any other kind. */
    fun pairMembers(threadId: String): List<String> =
        if (threadId.startsWith("pair:")) groupOf(threadId).removePrefix("pair:").split('|') else emptyList()

    /**
     * Normalise a caller-supplied thread id: a bare name becomes a `t:` (custom) thread, a qualified
     * `run:` / `pod:` / `pair:` id is kept as-is.
     */
    fun normalize(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.contains(':')) t else "t:$t"
    }
}
