// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CONTRACT.md must document every `Envelope` frame, and the test must NAME the frame it finds missing.
 *
 * WHY THIS TEST EXISTS. `INTERRUPT` (`"interrupt"`) shipped in `Protocol.kt` with no entry in
 * CONTRACT.md. A naive `grep -i interrupt` in the contract returns hits, but every one is `INTERRUPTED`,
 * the pod state, not the frame. A zero or a hit from a naive grep is not evidence of documentation;
 * this test asks the word-bounded question instead, and asks it of every frame, not one we remembered.
 *
 * THE CHECK. A frame counts as documented when its wire value appears as a backticked token in the
 * FIRST cell of a row of the contract's frame tables, e.g. "| `interrupt` | ... | client → daemon |".
 * Backticks make it word-bounded: `` `interrupt` `` does not match `INTERRUPTED`, `interrupted`, or prose.
 *
 * KNOWN DEBT, STATED PLAINLY. [KNOWN_UNTABLED] lists frames that already shipped without a table row
 * before this test existed. They are documented in prose (changelog sections), which this test does not
 * police. The allowlist is frozen: a NEW frame absent from the table fails here, and an allowlisted frame
 * that gains a row fails the staleness check until it is removed from the list. So the debt can only shrink.
 */
class ContractFrameDocTest {

    private val contract: String = findContract()

    private fun findContract(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "CONTRACT.md")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("CONTRACT.md not found above ${File("").absolutePath}")
    }

    /** Every `Envelope` frame constant: wire value keyed by constant name. */
    private fun envelopeFrames(): Map<String, String> =
        Envelope::class.java.fields
            .filter { it.type == String::class.java && Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.get(null) as String }

    /** True when a frame-table row's first cell names [wireValue] as a backticked token. */
    private fun hasTableRow(wireValue: String): Boolean =
        Regex("(?m)^\\|[^|\\n]*`${Regex.escape(wireValue)}`[^|\\n]*\\|").containsMatchIn(contract)

    @Test
    fun everyEnvelopeFrameHasATableRowOrIsKnownUndocumented() {
        val values = envelopeFrames().values
        val undocumented = values.filter { it !in KNOWN_UNTABLED && !hasTableRow(it) }.sorted()
        assertTrue(
            undocumented.isEmpty(),
            "frames with no backticked row in CONTRACT.md's frame table (add a row, or justify in the " +
                "changelog and the allowlist): $undocumented",
        )
    }

    @Test
    fun knownUndocumentedAllowlistHoldsOnlyRealUndocumentedFrames() {
        val values = envelopeFrames().values.toSet()
        val stale = KNOWN_UNTABLED.filter { it !in values || hasTableRow(it) }.sorted()
        assertTrue(
            stale.isEmpty(),
            "KNOWN_UNTABLED names frames that are no longer frames or that now have a table row; remove them: $stale",
        )
    }

    private companion object {
        /** Frames shipped before this test, documented in prose only. Frozen: shrink it, never grow it. */
        val KNOWN_UNTABLED = setOf(
            "attention", "attention_ack", "attention_items", "attention_list",
            "epic", "epic_list", "epic_save", "epics",
            "error", "finished", "memory_hits",
            "memory_search", "project", "project_get", "project_list", "project_save",
            "projects", "reasoning", "stories", "story", "story_get", "story_list", "story_move",
            "story_save", "text_delta", "turn", "workspace", "workspace_list",
            "workspace_save", "workspaces",
        )
    }
}
