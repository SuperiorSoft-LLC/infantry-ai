package com.superiorsoftllc.infantry.ui.model

/** Per-device: where the agent's reasoning text is shown. Display only; the text is the harness's own output. */
enum class ReasoningPlacement(val label: String, val effect: String) {
    INLINE("Inline in the chat", "The default. Reasoning shows as a collapsed Thinking block in the chat, and also in the reasoning panel."),
    PANEL_ONLY("Only in the panel", "The chat stays clean. All reasoning text is in the reasoning panel, opened from the button at the top right of the chat."),
    ;

    companion object {
        /** Unknown or unset ids fall back to [INLINE] (today's behaviour). */
        fun fromId(id: String?): ReasoningPlacement = entries.firstOrNull { it.name == id } ?: INLINE
    }
}

const val REASONING_PLACEMENT_PREF_KEY = "reasoningPlacement"
const val REASONING_TITLE = "Agent reasoning"
const val REASONING_APPLIES_TO = "Applies to: where an agent's reasoning text is shown in every chat on this device. Nothing is hidden: it is always in the reasoning panel. Remembered across launches."

/** The chat rows to draw: [PANEL_ONLY] leaves the reasoning blocks out of the chat (they stay in the panel). */
fun ReasoningPlacement.chatRows(items: List<ChatItem>): List<ChatItem> =
    if (this == ReasoningPlacement.PANEL_ONLY) items.filter { it !is ChatItem.Reasoning } else items

/** Every reasoning line of a transcript, oldest first: what the panel lists (replay included, since it reads the same output). */
fun reasoningLines(output: List<OutputLine>): List<OutputLine> = output.filter { it.kind == LineKind.REASONING }

/** The text the panel's Copy puts on the clipboard. */
fun reasoningCopyText(lines: List<OutputLine>): String = lines.joinToString("\n") { it.text }
