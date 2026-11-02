package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** AR-7b: the generic session-row badge registrar; no plugin is named in core. */
class SessionBadgeRegistrarTest {
    private fun agent(ticket: String?) = AgentView(
        SessionRecord(id = "s1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p", ticketId = ticket),
        role = "r",
    )

    private fun module(name: String, vararg providers: SessionBadgeProvider) =
        registerModule(ModuleManifest(name, "1.0.0", author = "t", license = "NOASSERTION"), Tier.CORE, NoPluginServices,
            object : UiPlugin { override fun register(registrar: UiRegistrar) { providers.forEach(registrar::sessionBadge) } })

    @Test fun `badges from several modules come back in order and null means none`() {
        val a = module("A_One", SessionBadgeProvider("a.late", 5) { _, _ -> SessionBadge("late", BadgeTone.WARN, "tip") })
        val b = module("B_Two",
            SessionBadgeProvider("b.early", 1) { _, ag -> ag.session.ticketId?.let { SessionBadge(it) } },
            SessionBadgeProvider("b.none", 2) { _, _ -> null })
        val f = TabFactory(listOf(a, b))
        assertEquals(listOf("t1", "late"), f.sessionBadgesFor(AppState(), agent("t1")).map { it.label })
        assertEquals(listOf("late"), f.sessionBadgesFor(AppState(), agent(null)).map { it.label })
        assertEquals(BadgeTone.WARN, f.sessionBadgesFor(AppState(), agent(null)).single().tone)
    }
}
