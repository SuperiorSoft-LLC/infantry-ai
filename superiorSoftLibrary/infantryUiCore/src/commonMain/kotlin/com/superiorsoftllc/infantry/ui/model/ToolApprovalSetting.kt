// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.ToolApprovalInfo

/**
 * BUG-PERM-1 (contract 1.76.0): Settings > "Tool approval". No harness can relay an approval prompt to this UI yet, so a
 * tool the mode does not allow is denied (the chat says so). The value is daemon state, the same for every harness: a
 * change is a `tool_approval_set` and applies to every session's next run.
 */
object ToolApprovalSetting {
    /** The first contract whose daemon serves `tool_approval` / `tool_approval_set`. */
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 76

    const val TITLE = "Tool approval"
    const val ASK_LABEL = "Ask me each time"
    const val APPLIES_TO = "Which tools agents may run without asking you, for every harness. Applies to each session's next run"

    /** The choices when the daemon did not list its own. */
    val DEFAULT_CHOICES = listOf("deny", "allowEdits", "allowAll")

    /** The modes the dropdown offers: the daemon's list, else [DEFAULT_CHOICES]; nothing before the daemon answers. */
    fun options(value: ToolApprovalInfo?): List<String> =
        if (value == null) emptyList() else value.choices.ifEmpty { DEFAULT_CHOICES }

    /** The value the field shows: the stored one, else what runs get now (env or the deny default). */
    fun shown(value: ToolApprovalInfo): String = value.mode ?: value.effective

    /** A mode in words. */
    fun label(mode: String): String = when (mode) {
        "deny" -> "deny (only what each CLI already allows)"
        "allowEdits" -> "allowEdits (file edits run without asking)"
        "allowAll" -> "allowAll (every tool runs without asking)"
        // PROMPT-2 (1.77.0): the daemon lists `ask`; each tool outside what the CLI allows shows the prompt card.
        "ask" -> ASK_LABEL
        else -> mode
    }

    /** What a run gets now, and where it came from. */
    fun status(value: ToolApprovalInfo): String {
        val from = when (value.source) {
            "setting" -> "set here"
            "env" -> "INFANTRY_TOOL_APPROVAL"
            "default" -> "default"
            else -> value.source
        }
        return "Runs now get: ${value.effective} ($from)"
    }
}
