// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.daemon.AgentFrames
import com.superiorsoftllc.infantry.daemon.FrameGate
import com.superiorsoftllc.infantry.plugin.api.ActionKind
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * POLICY-1: every frame the daemon dispatches is mapped to a named action (so a policy can turn it off by data), and every
 * write is mapped or owner-only. Reads the dispatch `when` of DaemonMain.kt, so a new frame added there without a catalog
 * entry fails here (under any non-production policy it would be refused as unmapped: default deny).
 */
class ActionCatalogCoverageTest {
    private val source = File("src/main/kotlin/com/superiorsoftllc/infantry/daemon/DaemonMain.kt").readText()
    private val constants: Map<String, String> = Envelope::class.java.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .associate { it.isAccessible = true; it.name to it.get(null) as String }

    /** The case labels of the socket loop's dispatch `when` (16-space indent, `Envelope.X[, Envelope.Y]` lines). */
    private fun dispatched(): Set<String> {
        val start = source.indexOf("val reply: Envelope? = when (envelope.type) {")
        val end = source.indexOf("unknown frame type", start)
        check(start > 0 && end > start) { "the dispatch when moved; update this test" }
        val label = Regex("""^ {16}(Envelope\.[A-Z0-9_]+(?:\s*,\s*Envelope\.[A-Z0-9_]+)*)\s*(?:,|->)""")
        return source.substring(start, end).lines().mapNotNull { label.find(it)?.groupValues?.get(1) }
            .flatMap { it.split(',') }.map { it.trim().removePrefix("Envelope.") }
            .map { constants[it] ?: error("Envelope.$it is not a String constant") }.toSet()
    }

    @Test fun `the parser sees the dispatch`() {
        val d = dispatched()
        assertTrue(d.size > 80, "only ${d.size} labels parsed")
        assertTrue(Envelope.TICKET_SAVE in d && Envelope.PROJECT_GIT_CHECKOUT in d && Envelope.PROJECT_CONFIG in d, d.toString())
    }

    @Test fun `every dispatched frame is mapped to an action`() {
        val missing = dispatched() - ActionCatalog.frames
        assertTrue(missing.isEmpty(), "frames dispatched by DaemonMain but in no ActionCatalog action: $missing")
    }

    @Test fun `the gates' own lists are mapped too`() {
        val missing = (AgentFrames.ALLOWED + FrameGate.OWNER_ONLY) - ActionCatalog.frames
        assertTrue(missing.isEmpty(), "frames on FrameGate/AgentFrames lists but in no ActionCatalog action: $missing")
    }

    @Test fun `every write frame is mapped or owner-only, and the switch is reachable`() {
        for (frame in dispatched()) {
            val action = ActionCatalog.forFrame(frame)
            assertTrue(action != null || frame in FrameGate.OWNER_ONLY, "write frame $frame is neither mapped nor owner-only")
            assertTrue(action == null || action.kind in ActionKind.ALL, "$frame has kind ${action?.kind}")
        }
        assertTrue(ActionCatalog.POLICY_SET in ActionCatalog.SWITCH_FRAMES && ActionCatalog.POLICY_GET in ActionCatalog.SWITCH_FRAMES)
    }

    @Test fun `read actions carry only reads`() {
        // A frame the demo lets through must be a read or one of the seat-scoped writes; a read id must not hide a write.
        val writes = setOf(Envelope.TICKET_SAVE, Envelope.SETTINGS_SET, Envelope.PROJECT_SAVE, Envelope.KILL, Envelope.NEW, Envelope.PROJECT_GIT_CHECKOUT)
        for (w in writes) assertTrue(ActionCatalog.forFrame(w)?.kind != ActionKind.READ, "$w is catalogued as a read")
    }
}
