// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.daemon.DemoPresetFixture
import com.superiorsoftllc.infantry.plugin.api.ActionKind
import com.superiorsoftllc.infantry.plugin.api.ActionPolicyMatcher
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** POLICY-1: the ONE switch: selection order, pinning, fail-closed, extends, the matcher, the owner-only flip. */
class PolicyRuntimeTest {
    private val readOnly = PolicyPreset("read-only", label = "Read only", disable = listOf("*"), allow = listOf("kind:read"), reason = "RO")
    private val guest = PolicyPreset("guest", extends = listOf("read-only"), allow = listOf("chat.*"), reason = "Guest")
    private val provider = PolicyProvider { listOf(readOnly, guest, DemoPresetFixture.preset) }
    private val state: File = Files.createTempDirectory("policy-test").toFile().apply { deleteOnExit() }
    private fun runtime(env: Map<String, String> = emptyMap()) =
        PolicyRuntime(listOf(provider), { env[it] }, File(state, PolicyRuntime.SELECTION_FILE), state)

    @Test fun `the matcher`() {
        assertTrue(ActionPolicyMatcher.matches("*", "a.b", ActionKind.READ))
        assertTrue(ActionPolicyMatcher.matches("kind:write", "", ActionKind.WRITE))
        assertTrue(ActionPolicyMatcher.matches("board.*", "board.ticket.move", ActionKind.WRITE))
        assertFalse(ActionPolicyMatcher.matches("board.*", "boards.x", ActionKind.WRITE))
        assertTrue(ActionPolicyMatcher.matches("board.ticket.move", "board.ticket.move", ActionKind.WRITE))
        assertFalse(ActionPolicyMatcher.allows(listOf("*"), listOf("kind:read"), "", ActionKind.WRITE), "an undeclared write follows its kind")
        assertTrue(ActionPolicyMatcher.allows(listOf("*"), listOf("kind:read"), "board.read", ActionKind.READ))
        assertTrue(ActionPolicyMatcher.allows(emptyList(), emptyList(), "", ActionKind.DESTRUCTIVE))
    }

    @Test fun `production by default refuses nothing and adds nothing`() {
        val r = runtime()
        assertEquals(PolicyPreset.PRODUCTION, r.current.id)
        assertNull(r.pinnedBy)
        assertEquals("", r.readyFields())
        for (t in listOf(Envelope.TICKET_SAVE, Envelope.KILL, "anything_new")) assertNull(r.frameRefusal(Envelope(t, "{}"), null, owner = false), t)
    }

    @Test fun `the owner flips by the one setting, it persists, and back`() {
        val r = runtime()
        var told = 0
        r.onChange { told++ }
        assertNull(r.select("read-only"))
        assertEquals("read-only", r.current.id)
        assertEquals("read-only", File(state, PolicyRuntime.SELECTION_FILE).readText().trim())
        assertNotNull(r.frameRefusal(Envelope(Envelope.TICKET_SAVE, "{}"), null, owner = true))
        assertNull(r.frameRefusal(Envelope(Envelope.TICKET_LIST, ""), null, owner = false))
        assertNotNull(r.frameRefusal(Envelope("anything_new", ""), null, owner = true), "unmapped = refused")
        assertEquals("read-only", runtime().current.id, "a restart keeps the pick")
        assertNull(r.select(PolicyPreset.PRODUCTION))
        assertFalse(File(state, PolicyRuntime.SELECTION_FILE).exists())
        assertNull(r.frameRefusal(Envelope(Envelope.TICKET_SAVE, "{}"), null, owner = true))
        assertEquals(2, told)
        assertNotNull(r.select("nope"))
    }

    @Test fun `only the owner sends policy_set, anybody reads policy_get`() {
        val r = runtime()
        assertNotNull(r.frameRefusal(Envelope(ActionCatalog.POLICY_SET, "read-only"), null, owner = false))
        assertNull(r.frameRefusal(Envelope(ActionCatalog.POLICY_SET, "read-only"), null, owner = true))
        assertNull(r.frameRefusal(Envelope(ActionCatalog.POLICY_GET, ""), null, owner = false))
        r.select("read-only")
        assertNull(r.frameRefusal(Envelope(ActionCatalog.POLICY_SET, PolicyPreset.PRODUCTION), null, owner = true), "the switch is never policy-gated")
        assertTrue(r.wire(owner = true).canSwitch)
        assertFalse(r.wire(owner = false).canSwitch)
        r.select(PolicyPreset.PRODUCTION)
    }

    @Test fun `INFANTRY_POLICY and selectWhenEnv pin the switch`() {
        val pinned = runtime(mapOf(PolicyRuntime.POLICY_ENV to "guest"))
        assertEquals("guest", pinned.current.id)
        assertEquals(PolicyRuntime.POLICY_ENV, pinned.pinnedBy)
        assertNotNull(pinned.select(PolicyPreset.PRODUCTION))
        assertFalse(pinned.wire(owner = true).canSwitch)
        val demo = runtime(mapOf(DemoPresetFixture.preset.selectWhenEnv!! to "1"))
        assertEquals(DemoPresetFixture.preset.id, demo.current.id)
        assertTrue(demo.current.seats != null, "the demo preset has seats (a daemon plugin implements them)")
        assertTrue(demo.readyFields().startsWith(" demo=lock access=restricted notices=demo_limit"), demo.readyFields())
        assertEquals(PolicyPreset.PRODUCTION, runtime(mapOf(DemoPresetFixture.preset.selectWhenEnv!! to "0")).current.id)
    }

    @Test fun `a pinned id nobody provides denies everything`() {
        val r = runtime(mapOf(PolicyRuntime.POLICY_ENV to "missing"))
        assertNotNull(r.frameRefusal(Envelope(Envelope.SESSIONS, ""), null, owner = true))
        assertFalse(r.current.isEmpty)
    }

    @Test fun `extends unions parent-first, the child's scalars win`() {
        val g = runtime(mapOf(PolicyRuntime.POLICY_ENV to "guest")).current
        assertEquals(listOf("*"), g.disable)
        assertEquals(listOf("kind:read", "chat.*"), g.allow)
        assertEquals("Guest", g.reason)
    }
}
