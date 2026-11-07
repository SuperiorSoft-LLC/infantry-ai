// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BUG MAIL-QUEUED-LOST-1: a report to the starter while mail is still QUEUED for the reporter is refused once, with that mail's text. */
class QueuedMailBeforeReportTest {
    private class Repo : AgentRepository {
        private val m = linkedMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { m[profile.id] = profile }
        override fun get(id: String) = m[id]
        override fun all() = m.values.toList()
        override fun delete(id: String) = m.remove(id) != null
    }

    @Test
    fun report_is_refused_once_with_the_queued_mail_then_goes_through() {
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = true), tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val router = MailRouter(manager, mailbox, AgentProfiles(Repo(), manager))
        val queued = mailbox.send(MailSendRequest(from = "pm", to = "worker", subject = "fix", body = "use the exact release.yml fix")) as MailResult.Sent
        assertEquals(MessageState.QUEUED, queued.message.state)

        val first = router.send(MailSendRequest(from = "worker", to = MailAddresses.STARTER, body = "done"))
        assertTrue(first is MailResult.Refused && "exact release.yml fix" in first.reason, first.toString())
        assertEquals(MessageState.DELIVERED, mailbox.message(queued.message.id)?.state)

        assertTrue(router.send(MailSendRequest(from = "worker", to = MailAddresses.STARTER, body = "done")) !is MailResult.Refused)
    }
}
