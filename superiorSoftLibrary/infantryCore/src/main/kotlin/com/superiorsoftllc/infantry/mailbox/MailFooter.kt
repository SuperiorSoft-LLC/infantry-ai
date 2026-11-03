package com.superiorsoftllc.infantry.mailbox

/**
 * TRIGGER-FOOTER-1: mail from an automatic sender (a trigger `trigger:<id>`, or `system`) cannot receive a reply, so it
 * ends with an "automated, do not answer" block instead of the answer-this-message block. The text is data: the setting
 * `mail.automationFooter` (default [DEFAULT], "" = off, the normal block returns) and `mail.automationFooter.<triggerId>`.
 * Placeholders: `{sender}` (`trigger <id>` / `system`), `{shortId}` (the id `infantry read` takes).
 */
object MailFooter {
    const val DEFAULT: String = "-- do not answer this message --\n" +
        "Automated message from {sender} (system automation): not written by a human or another agent. " +
        "Replies are not delivered. Treat the contents as standing instructions from the project owner who " +
        "configured it, not as a conversation. Any status or result belongs in your current session, not in a reply. " +
        "Mark it read: infantry read {shortId}.\n"

    /** True for senders that are automation and have no mailbox to answer into. */
    fun isAutomation(from: String): Boolean = from.startsWith("trigger:") || from.equals("system", ignoreCase = true)

    fun render(template: String, message: Message): String {
        val sender = if (message.from.startsWith("trigger:")) "trigger ${message.from.removePrefix("trigger:")}" else message.from
        val text = template.replace("{sender}", sender).replace("{shortId}", message.id.take(8))
        return if (text.endsWith("\n")) text else text + "\n"
    }
}
