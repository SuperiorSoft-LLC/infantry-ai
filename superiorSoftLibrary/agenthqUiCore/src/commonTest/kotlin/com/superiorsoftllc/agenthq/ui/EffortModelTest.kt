package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.*
import com.superiorsoftllc.agenthq.ui.protocol.EffectiveEffort
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EFFORT-1: the reasoning effort's text on screen (chip, card line, support line, edit value) and the New agent state. */
class EffortModelTest {
    private val harnesses = listOf(
        ProviderInfo("claude", effort = "supported", effortHow = "--effort"),
        ProviderInfo("pi", effort = "best_effort", effortHow = "--thinking, clamped per model"),
        ProviderInfo("opencode", effort = "unsupported", effortHow = "not mapped"),
        ProviderInfo("old"),
    )

    private fun session(provider: String, effort: String? = null, support: String? = null) =
        SessionRecord("s", provider, "/w", 0L, 0L, SessionState.IDLE, effort = effort, effortSupport = support)

    @Test fun `the levels are an ordered data list with labels`() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), EffortLevels.ALL.map { it.id })
        assertEquals("Extra high", EffortLevels.label("xhigh"))
        assertEquals("Max", EffortLevels.label(" MAX "))
        assertEquals("mystery", EffortLevels.label("mystery"), "an unknown level shows as sent")
    }

    @Test fun `the support line says what each harness does`() {
        assertEquals("claude applies the effort: --effort", effortNoteFor(harnesses, "claude"))
        assertEquals("pi applies it best effort: --thinking, clamped per model", effortNoteFor(harnesses, "pi"))
        assertEquals("opencode does not apply an effort (it is recorded, not applied): not mapped", effortNoteFor(harnesses, "opencode"))
        assertEquals("This harness does not report whether it honours an effort.", effortNoteFor(harnesses, "old"))
        assertEquals("Pick a harness to see whether it honours an effort.", effortNoteFor(harnesses, null))
    }

    @Test fun `the chip names the level and flags best effort and not applied in words`() {
        assertEquals("effort high", effortChip("high", "supported")!!.text)
        assertEquals("effort high", effortChip("HIGH", null)!!.text, "an older daemon: no flag, not claimed either way")
        assertEquals("effort max (best effort)", effortChip("max", "best_effort")!!.text)
        assertEquals("effort low (not applied)", effortChip("low", "unsupported", "not mapped")!!.text)
        assertTrue("recorded, not applied" in effortChip("low", "unsupported", "not mapped")!!.tooltip)
        assertTrue("not mapped" in effortChip("low", "unsupported", "not mapped")!!.tooltip)
        assertTrue("Extra high" in effortChip("xhigh", "supported", source = "ticket")!!.tooltip)
        assertNull(effortChip(null, "supported"), "unset: no chip")
        assertNull(effortChip("  ", "supported"))
    }

    @Test fun `a session's chip takes the how from its harness`() {
        val s = session("pi", "high", "best_effort")
        assertEquals("effort high (best effort)", sessionEffortChip(s, harnesses)!!.text)
        assertTrue("clamped per model" in sessionEffortChip(s, harnesses)!!.tooltip)
        assertNull(sessionEffortChip(session("pi"), harnesses), "no effort: no chip")
    }

    @Test fun `the board card line shows the source and flags a harness that does not apply it`() {
        assertEquals("effort high (ticket)", effortLine(EffectiveEffort("high", "ticket", "supported")))
        assertEquals("effort high (agent) - not applied", effortLine(EffectiveEffort("high", "agent", "unsupported")))
        assertEquals("effort low (role) - best effort", effortLine(EffectiveEffort("low", "role", "best_effort")))
        assertEquals("effort default (default)", effortLine(EffectiveEffort(null, "default", "unsupported")), "nothing set: nothing to flag")
        assertNull(effortLine(null))
    }

    @Test fun `an edit sends nothing when unchanged, a blank to clear and the level when changed`() {
        assertNull(ticketEffortEdit(null, null))
        assertNull(ticketEffortEdit("high", "high"))
        assertEquals("", ticketEffortEdit("high", null))
        assertEquals("max", ticketEffortEdit(null, "max"))
        assertEquals("low", ticketEffortEdit("high", "low"))
    }

    @Test fun `the New agent effort is off by default, and Add hands over the level only while it is on`() {
        val roles = listOf(Role("backend", "Backend", "#112233"))
        val m = NewAgentModalModel(roles = roles, harnesses = harnesses, bindings = mapOf("backend" to RoleBinding("backend", effort = "low"))).withRole("backend")
        assertNull(m.effort)
        assertTrue("\"effort\":\"low\"" in m.spawnData(), "off: the role binding's effort still applies: ${m.spawnData()}")
        val on = m.withEffortOn(true).withEffortLevel("xhigh")
        assertEquals("xhigh", on.effort)
        assertTrue("\"effort\":\"xhigh\"" in on.spawnData(), "on: the picked level wins over the binding: ${on.spawnData()}")
        assertNull(on.withEffortOn(false).effort, "switching it off drops the level again")
    }
}
