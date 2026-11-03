package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.protocol.MessageOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TRIGGER-FOOTER-1: automation mail ends with a do-not-answer block; agent mail is unchanged. */
class MailFooterTest {
    private fun msg(from: String) = Message(id = "abcdef0123456789", threadId = "t1", seq = 1, from = from, to = "s-2", body = "body", sentAt = 0)

    private fun render(from: String, footer: String?) =
        MailSource(msg(from), MessageOrigin.External(from, "mail"), footer.takeIf { MailFooter.isAutomation(from) }).render()

    @Test
    fun triggerAndSystemMailGetTheDoNotAnswerBlock() {
        for (from in listOf("trigger:trg-1234", "system")) {
            val text = render(from, MailFooter.DEFAULT)
            assertTrue("-- do not answer this message --" in text, text)
            assertTrue("infantry read abcdef01" in text, text)
            assertFalse("infantry send" in text, text)
            assertFalse("--reply-to" in text, text)
            assertFalse("infantry_message" in text, text)
            assertFalse("-- answer this message --" in text, text)
        }
        assertTrue("Automated message from trigger trg-1234" in render("trigger:trg-1234", MailFooter.DEFAULT))
    }

    @Test
    fun switchedOffRestoresTheOldBlock() {
        val text = render("trigger:trg-1234", null)
        assertTrue("-- answer this message --" in text, text)
        assertTrue("infantry send trigger:trg-1234" in text, text)
    }

    @Test
    fun aCustomTemplateWins() {
        val text = render("trigger:trg-1234", "custom {sender} / {shortId}")
        assertTrue(text.endsWith("custom trigger trg-1234 / abcdef01\n"), text)
    }

    @Test
    fun agentMailIsUnchanged() {
        val text = render("s-9", MailFooter.DEFAULT)
        assertTrue("-- answer this message --" in text, text)
        assertTrue("infantry send s-9 \"<your answer, one line>\" --reply-to abcdef0123456789" in text, text)
        assertFalse("do not answer" in text, text)
    }

    @Test
    fun defaultWordingNamesNoRole() {
        for (role in listOf("pm", "architect", "backend", "frontend", "qa", "devops", "worker")) {
            assertFalse(Regex("\\b$role\\b", RegexOption.IGNORE_CASE).containsMatchIn(MailFooter.DEFAULT), role)
        }
        assertEquals(true, MailFooter.isAutomation("trigger:x"))
        assertEquals(false, MailFooter.isAutomation("pm"))
    }
}
