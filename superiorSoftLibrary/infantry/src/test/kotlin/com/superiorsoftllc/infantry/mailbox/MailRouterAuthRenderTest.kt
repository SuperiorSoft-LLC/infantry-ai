// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A delivered prompt must answer the message WITHOUT ever containing the token. MAIL-FOOTER-1: the footer
 * prints `infantry` CLI commands (which read `INFANTRY_TOKEN` from the environment the daemon gave the agent)
 * instead of curl, so there is no credential in the prompt at all, on a token-gated daemon or a loopback one.
 */
class MailRouterAuthRenderTest {

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

    /** Deliver one message to a fresh idle agent and return the prompt its console actually received. */
    private fun deliveredPrompt(agentsSendToken: Boolean, mailBaseUrl: String): String {
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val agentProfiles = AgentProfiles(FakeAgentRepository(), manager)
        val router = MailRouter(manager, mailbox, agentProfiles, mailBaseUrl, agentsSendToken)
        manager.eventSink = { event -> router.onSessionEvent(event) }
        val agent = manager.startNew("bootstrap")
        router.send(MailSendRequest(from = "human", to = agent.id, body = "wake up"))
        return runner.processes.last { it.input.contains("[Infantry AI COMMS]") }.input.toString()
    }

    @Test
    fun aTokenGatedDaemonPutsNoCredentialAndNoCurlInThePrompt() {
        val prompt = deliveredPrompt(agentsSendToken = true, mailBaseUrl = "http://10.0.0.5:8787")

        assertFalse(prompt.contains("Authorization"), prompt)
        assertFalse(prompt.contains("Bearer"), prompt)
        assertFalse(prompt.contains("curl"), prompt)
        assertFalse(prompt.contains("/mail/"), "no HTTP recipe survives: $prompt")
        assertTrue(prompt.contains("infantry send human"), prompt)
    }

    @Test
    fun aLoopbackDaemonRendersTheSameCliFooter() {
        val gated = deliveredPrompt(agentsSendToken = true, mailBaseUrl = "http://10.0.0.5:8787")
        val loopback = deliveredPrompt(agentsSendToken = false, mailBaseUrl = "http://127.0.0.1:8787")

        val footer = { p: String -> p.substringAfter("-- answer this message --").replace(Regex("[0-9a-f-]{36}|[0-9a-f]{8}"), "<id>") }
        assertEquals(footer(gated), footer(loopback))
        assertFalse(loopback.contains("Authorization"), loopback)
    }
}
