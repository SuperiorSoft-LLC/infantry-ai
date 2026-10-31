package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.protocol.MessageOrigin
import com.superiorsoftllc.agenthq.session.SessionManager

/**
 * Contract 1.125.0: anything that puts a turn into an agent's transcript. A source states WHO it is as typed data
 * ([origin]) and renders the text the harness's CLI reads ([render]); the router hands the two to the session manager,
 * so the transcript turn carries the origin on the wire and the model reads the text. Nothing downstream parses the
 * text back into an origin, and a client never sees anything but [origin].
 *
 * To add a message source (a webhook, an email bridge, a scheduler): implement this interface and pass it to
 * [MailRouter] / the session manager's `resume(sessionId, source.render(), source.origin)`. See `docs/ARCHITECTURE.md`,
 * "How to add a new message source".
 */
interface MessageSource {
    /** Who the turn is from. Set here, where the turn is created. */
    val origin: MessageOrigin

    /** The harness-facing text for this turn: the only place a header like `[Infantry AI COMMS] ...` is produced. */
    fun render(): String
}

/**
 * The mail source: one [Message] from the mailbox. [origin] is derived from the sender handle once, here;
 * [render] is the one place the `[Infantry AI COMMS]` header and the answer-this-message footer are written.
 */
class MailSource(
    private val message: Message,
    override val origin: MessageOrigin,
    /** TRIGGER-FOOTER-1: the footer template for automation mail (see [MailFooter]); null = the normal answer block. */
    private val automationFooter: String? = null,
) : MessageSource {

    override fun render(): String = buildString {
        append("[Infantry AI COMMS] message ").append(message.id)
        // The reply address, not the label: `agenthq send <from>` must reach the sender, so the header and footer keep
        // the session id (or handle) the mailbox stored. [origin] is what clients render.
        append(" from ").append(message.from)
        append(" · thread ").append(message.threadId)
        if (message.subject.isNotBlank()) append(" · ").append(message.subject)
        append('\n')
        append('\n')
        append(message.body)
        message.checkpoint?.takeUnless { it.isEmpty }?.let { cp ->
            append("\n\n-- checkpoint --\n")
            cp.state?.takeIf { it.isNotBlank() }?.let { append("state: ").append(it).append('\n') }
            cp.decisions?.takeIf { it.isNotBlank() }?.let { append("decisions: ").append(it).append('\n') }
            cp.evidence?.takeIf { it.isNotBlank() }?.let { append("evidence: ").append(it).append('\n') }
            cp.blockers?.takeIf { it.isNotBlank() }?.let { append("blockers: ").append(it).append('\n') }
            cp.nextAction?.takeIf { it.isNotBlank() }?.let { append("next: ").append(it).append('\n') }
        }
        automationFooter?.let { append('\n').append(MailFooter.render(it, message)); return@buildString }
        append("\n-- answer this message --\n")
        append("mark it read:\n")
        append("  agenthq read ").append(message.id.take(8)).append('\n')
        append("reply (goes back to ").append(message.from).append(" in this same thread):\n")
        append("  agenthq send ").append(message.from).append(" \"<your answer, one line>\" --reply-to ")
        append(message.id).append('\n')
        append("check the reply landed:\n")
        append("  agenthq threads\n")
        append("or with the MCP tools: agenthq_read {\"messageId\":\"").append(message.id).append("\"}, then ")
        append("agenthq_message {\"to\":\"").append(message.from).append("\",\"replyTo\":\"").append(message.id)
        append("\",\"body\":\"<your answer>\"}\n")
        append("all mail commands: agenthq mail\n")
    }

    companion object {
        /** The sender handles that mean the human, as `from` carries them when mail is sent outside any session. */
        private val OWNER_HANDLES = setOf("human", "owner", "user")

        /** Mail from [message]'s sender: an agent session, the owner, AgentHQ itself, or an outside handle. */
        fun of(
            message: Message,
            manager: SessionManager,
            footerFor: (String) -> String? = { MailFooter.DEFAULT },
        ): MailSource {
            val from = message.from
            val sender = manager.get(from)
            val origin = when {
                sender != null -> MessageOrigin.Agent(sender.id, sender.agentId, sender.role)
                from.lowercase() in OWNER_HANDLES -> MessageOrigin.Owner
                from.equals("system", ignoreCase = true) -> MessageOrigin.System("mail")
                else -> MessageOrigin.External(from, "mail")
            }
            val footer = if (sender == null && MailFooter.isAutomation(from)) footerFor(from) else null
            return MailSource(message, origin, footer)
        }
    }
}
