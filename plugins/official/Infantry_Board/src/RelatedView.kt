package com.superiorsoftllc.agenthq.plugins.official.board

import com.superiorsoftllc.agenthq.ui.protocol.Ticket

/** KNOWLEDGE-PTR-2: one line of the stored RELATED (auto) block: why it is related, the ticket, its status and files. */
data class RelatedItem(val reason: String, val ticketId: String, val title: String, val status: String, val files: String?)

/** KNOWLEDGE-PTR-2: reads the stored RELATED (auto) block atop a description; the UI hides its marker lines. */
object RelatedView {
    private const val BEGIN = "<!-- RELATED (auto) -->"
    private const val END = "<!-- /RELATED -->"
    private val LINE = Regex("""^- ([^:]+): (\S+) (.*) \[([^\]]+)]( -> (.*?))?( \(git show \w+\))?$""")

    /** The description without its block: what a person wrote. */
    fun human(description: String): String {
        if (!description.startsWith(BEGIN)) return description
        val end = description.indexOf(END)
        if (end < 0) return description
        return description.substring(end + END.length).removePrefix("\n").removePrefix("\n")
    }

    /** The block's ticket lines (the Series and Top files lines are not tickets). */
    fun items(description: String): List<RelatedItem> {
        if (!description.startsWith(BEGIN)) return emptyList()
        val end = description.indexOf(END).takeIf { it >= 0 } ?: return emptyList()
        return description.substring(BEGIN.length, end).lineSequence().mapNotNull { l ->
            LINE.matchEntire(l.trim())?.let { RelatedItem(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4], it.groupValues[6].ifBlank { null }) }
        }.toList()
    }

    /** Related tickets of a card: its relates_to links plus the block's series members, each ticket once. */
    fun count(t: Ticket): Int =
        (t.links.filter { it.type == "relates_to" }.map { it.ticketId } + items(t.description).filter { it.reason == "series" }.map { it.ticketId }).toSet().size
}
