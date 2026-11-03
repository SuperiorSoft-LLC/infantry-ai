package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.ExecutorPickerModel
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.model.TicketPinEdit
import com.superiorsoftllc.infantry.ui.model.TicketPins
import com.superiorsoftllc.infantry.ui.model.executorLine
import com.superiorsoftllc.infantry.ui.model.modelForProvider
import com.superiorsoftllc.infantry.ui.model.ticketPinEdit
import com.superiorsoftllc.infantry.ui.protocol.EffectiveExecutor
import com.superiorsoftllc.infantry.ui.protocol.ExecutorField
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TICKET-EXEC-UI: the ticket forms' assignee / provider / model pins, pure (no Compose). */
class TicketExecutorModelTest {
    private val harnesses = listOf(ProviderInfo("opencode", default = true), ProviderInfo("claude"), ProviderInfo("pi"))
    private val catalog = listOf(
        ProviderModels("opencode", "deepseek", "opencode / deepseek", listOf("deepseek/deepseek-chat")),
        ProviderModels("claude", "anthropic", "claude / anthropic", listOf("opus", "sonnet")),
    )

    @Test
    fun `an edit sends only the pins that changed`() {
        val before = TicketPins("role:backend", "opencode", "deepseek/deepseek-chat")
        assertTrue(ticketPinEdit(before, before).isEmpty, "nothing changed: nothing to send")
        assertEquals(
            TicketPinEdit(agentId = null, provider = "claude", model = "opus"),
            ticketPinEdit(before, TicketPins("role:backend", "claude", "opus")),
        )
        assertEquals(TicketPinEdit(agentId = "p-1", provider = null, model = null), ticketPinEdit(before, before.copy(agentId = "p-1")))
    }

    @Test
    fun `clearing a pin sends an empty string, the contract's clear`() {
        val before = TicketPins("role:backend", "opencode", "deepseek/deepseek-chat")
        assertEquals(TicketPinEdit(agentId = "", provider = "", model = ""), ticketPinEdit(before, TicketPins(null, null, null)))
        assertEquals(TicketPinEdit(null, null, ""), ticketPinEdit(before, before.copy(model = null)), "model back to inherit, provider kept")
    }

    @Test
    fun `blank and null pins are the same no-pin`() {
        assertTrue(ticketPinEdit(TicketPins(null, null, null), TicketPins("", " ", "")).isEmpty)
        val t = Ticket(id = "t", title = "x", status = "todo", agentId = "", provider = null, model = " ")
        assertEquals(TicketPins(null, null, null), TicketPins.of(t))
    }

    @Test
    fun `a ticket frame carrying provider and model decodes its pins`() {
        val json = """{"id":"t1","title":"x","status":"todo","agentId":"role:qa","provider":"claude","model":"opus",""" +
            """"effectiveExecutor":{"agentId":null,"role":"qa","provider":{"value":"claude","source":"ticket"},"model":{"value":"opus","source":"ticket"}}}"""
        val t = Json { ignoreUnknownKeys = true }.decodeFromString(Ticket.serializer(), json)
        assertEquals(TicketPins("role:qa", "claude", "opus"), TicketPins.of(t))
    }

    @Test
    fun `changing the provider keeps a model only when the new provider lists it`() {
        assertEquals("opus", modelForProvider(harnesses, catalog, "claude", "opus"))
        assertNull(modelForProvider(harnesses, catalog, "opencode", "opus"), "opus is claude's: back to inherit on opencode")
        assertNull(modelForProvider(harnesses, catalog, "pi", "opus"), "a harness that lists nothing keeps no foreign model")
        assertNull(modelForProvider(harnesses, catalog, null, "opus"), "provider inherit takes the model pin with it")
        assertNull(modelForProvider(harnesses, catalog, "claude", null))
    }

    @Test
    fun `the shared picker resets an incompatible model on a harness change`() {
        val m = ExecutorPickerModel.seeded(harnesses, catalog, PickedModel("opencode", "deepseek/deepseek-chat"))
        assertEquals(PickedModel("opencode", "deepseek/deepseek-chat"), m.picked)
        assertEquals(PickedModel("claude", null), m.withHarness("claude").picked)
    }

    @Test
    fun `the card line names each value with its source`() {
        val e = EffectiveExecutor(provider = ExecutorField("claude", "ticket"), model = ExecutorField("opus", "profile"))
        assertEquals("claude (ticket) / opus (profile)", executorLine(e))
        assertEquals(
            "opencode (role) / default (role)",
            executorLine(EffectiveExecutor(provider = ExecutorField("opencode", "role"), model = ExecutorField(null, "role"))),
        )
        assertEquals("default (default) / opus (ticket)", executorLine(EffectiveExecutor(model = ExecutorField("opus", "ticket"))))
        assertNull(executorLine(null))
        assertNull(executorLine(EffectiveExecutor(role = "qa")))
    }
}
