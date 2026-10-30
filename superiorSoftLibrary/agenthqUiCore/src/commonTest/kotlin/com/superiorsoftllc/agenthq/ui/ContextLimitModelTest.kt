package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.*
import com.superiorsoftllc.agenthq.ui.protocol.EffectiveContextLimit
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONTEXT-LIMIT-2: the context limit's text on screen (chip, card line, support line, edit value). */
class ContextLimitModelTest {
    private val harnesses = listOf(
        ProviderInfo("claude", contextLimit = "supported", contextLimitHow = "auto-compacts"),
        ProviderInfo("pi", contextLimit = "best_effort", contextLimitHow = "compacts between turns"),
        ProviderInfo("opencode", contextLimit = "unsupported", contextLimitHow = "not mapped"),
        ProviderInfo("old"),
    )

    @Test fun `sizes read the way people type them`() {
        assertEquals("200k", formatContextLimit(200_000))
        assertEquals("1M", formatContextLimit(1_000_000))
        assertEquals("1.5M", formatContextLimit(1_500_000))
        assertEquals("12345", formatContextLimit(12_345))
        assertEquals(listOf("200k", "500k", "1M"), ContextLimitPresets.ALL.map(::formatContextLimit))
        ContextLimitPresets.ALL.forEach { assertEquals(it, ContextLimitInput.parse(formatContextLimit(it)).value) }
    }

    @Test fun `the support line says what each harness does`() {
        assertEquals("claude applies the limit: auto-compacts", contextLimitNoteFor(harnesses, "claude"))
        assertEquals("pi applies it best effort: compacts between turns", contextLimitNoteFor(harnesses, "pi"))
        assertEquals("opencode does not apply a limit (it is recorded, not applied): not mapped", contextLimitNoteFor(harnesses, "opencode"))
        assertEquals("This harness does not report whether it honours a limit.", contextLimitNoteFor(harnesses, "old"))
        assertEquals("Pick a harness to see whether it honours a limit.", contextLimitNoteFor(harnesses, null))
    }

    @Test fun `the chip in each state`() {
        assertEquals("limit 1M", contextLimitChip(1_000_000, "supported")!!.text)
        assertEquals("limit 200k (not applied)", contextLimitChip(200_000, "unsupported", "not mapped")!!.text)
        assertEquals("limit 500k (best effort)", contextLimitChip(500_000, "best_effort")!!.text)
        assertNull(contextLimitChip(null, "supported"))
        assertNull(contextLimitChip(0, "supported"))
        assertTrue("not mapped" in contextLimitChip(200_000, "unsupported", "not mapped", "ticket")!!.tooltip)
        assertTrue("ticket" in contextLimitChip(200_000, "unsupported", "not mapped", "ticket")!!.tooltip)
    }

    @Test fun `the card line names the value and its source`() {
        assertEquals("context limit 1M (ticket)", contextLimitLine(EffectiveContextLimit(1_000_000, "ticket", "supported")))
        assertEquals("context limit 200k (ticket) - not applied", contextLimitLine(EffectiveContextLimit(200_000, "ticket", "unsupported")))
        assertEquals("context limit default (default)", contextLimitLine(EffectiveContextLimit(null, "default")))
        assertNull(contextLimitLine(null))
    }

    @Test fun `an edit sends nothing when unchanged, zero to clear, else the size`() {
        assertNull(ticketContextLimitEdit(null, null))
        assertNull(ticketContextLimitEdit(200_000, 200_000))
        assertEquals(0, ticketContextLimitEdit(200_000, null))
        assertEquals(1_000_000, ticketContextLimitEdit(null, 1_000_000))
    }
}
