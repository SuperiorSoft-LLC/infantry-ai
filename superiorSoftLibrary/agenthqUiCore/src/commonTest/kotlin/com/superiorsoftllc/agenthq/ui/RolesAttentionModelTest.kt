package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.parseHexColor
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import com.superiorsoftllc.agenthq.ui.protocol.AttentionItem
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.Role
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Role picker / Role detail screens (contract 1.12.0 `roles`, plus the existing `agents` and
 * `providers`) and the attention panel (contract 1.9.0) are rendered from frames only, like everything else.
 */
class RolesAttentionModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private val backend = Role("backend", "Backend", "#3b82f6", "You build APIs.", "BUILT_IN")
    private val qa = Role("qa", "QA", "#22c55e", "", "BUILT_IN")

    private fun roles(vararg r: Role) = Envelope(Envelope.ROLES, json.encodeToString(ListSerializer(Role.serializer()), r.toList()))
    private fun role(r: Role) = Envelope(Envelope.ROLE, json.encodeToString(Role.serializer(), r))
    private fun agents(vararg p: AgentProfile) = Envelope(Envelope.AGENTS, json.encodeToString(ListSerializer(AgentProfile.serializer()), p.toList()))
    private fun agent(p: AgentProfile) = Envelope(Envelope.AGENT, json.encodeToString(AgentProfile.serializer(), p))
    private fun item(id: String, at: Long, acked: Long? = null) = AttentionItem(id, at, "stalled", sessionId = "s", outcome = "stalled", detail = "no output for 15m", ackedAt = acked)

    @Test fun `the role catalog comes from roles, and a pushed role updates it in place or joins the end`() {
        val s = feed(roles(backend, qa))
        assertEquals(listOf("backend", "qa"), s.roles.map { it.id })
        val custom = backend.copy(name = "Backend Ninja", color = "#0ea5e9", source = "CUSTOM")
        val updated = feed(role(custom), role(Role("data-wrangler", "Data Wrangler", "#111111")), from = s)
        assertEquals(listOf("backend", "qa", "data-wrangler"), updated.roles.map { it.id }, "order kept, a new id appended")
        assertEquals("Backend Ninja", updated.role("backend")!!.name)
        assertNull(updated.role("nope"))
    }

    @Test fun `profiles come from agents and a saved profile is upserted, so a role lists its own`() {
        val p1 = AgentProfile("a1", "API builder", "backend", provider = "stub")
        val p2 = AgentProfile("a2", "Tester", "qa")
        val s = feed(agents(p1, p2), agent(p1.copy(name = "API builder 2")), agent(AgentProfile("a3", "Second backend", "backend")))
        assertEquals(listOf("API builder 2", "Second backend"), s.profilesFor("backend").map { it.name })
        assertEquals(listOf("a2"), s.profilesFor("qa").map { it.id })
        val afterDelete = feed(agents(p2), from = s)
        assertEquals(emptyList(), afterDelete.profilesFor("backend"), "agents after a delete is the whole list")
    }

    @Test fun `providers come from the providers frame, the default first`() {
        val list = listOf(ProviderInfo("stub", default = true, defaultModel = null), ProviderInfo("claude", defaultModel = "sonnet"))
        val s = feed(Envelope(Envelope.PROVIDERS, json.encodeToString(ListSerializer(ProviderInfo.serializer()), list)))
        assertEquals(list, s.providers)
    }

    @Test fun `attention history plus pushes, newest first, with an open count that an ack lowers`() {
        val history = Envelope(Envelope.ATTENTION_ITEMS, json.encodeToString(ListSerializer(AttentionItem.serializer()), listOf(item("x", 1), item("y", 2))))
        val s = feed(history, Envelope(Envelope.ATTENTION, json.encodeToString(AttentionItem.serializer(), item("z", 3))))
        assertEquals(listOf("z", "y", "x"), s.attentionNewestFirst.map { it.id })
        assertEquals(3, s.attentionOpenCount)
        val acked = feed(Envelope(Envelope.ATTENTION, json.encodeToString(AttentionItem.serializer(), item("y", 2, acked = 50))), from = s)
        assertEquals(2, acked.attentionOpenCount)
        assertEquals(3, acked.attention.size, "an ack updates the item in place, it does not add one")
        assertEquals(50L, acked.attention.single { it.id == "y" }.ackedAt)
    }

    @Test fun `an unknown attention kind still renders, because the kind list may grow`() {
        val future = """[{"id":"f","at":1,"kind":"brand_new_kind","outcome":"o","detail":"d"}]"""
        assertEquals("brand_new_kind", feed(Envelope(Envelope.ATTENTION_ITEMS, future)).attention.single().kind)
    }

    @Test fun `a role colour is read from the catalog when the daemon has one`() {
        val s = feed(roles(backend))
        assertEquals("#3b82f6", s.role("backend")!!.color)
        assertEquals(0xFF3B82F6.toInt(), parseHexColor("#3b82f6"))
        assertNull(parseHexColor("blue"))
    }
}
