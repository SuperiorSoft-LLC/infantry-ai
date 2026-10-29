package com.superiorsoftllc.agenthq.context

/** One file of a related ticket: git's letter and the repo path. */
data class RelatedFile(val path: String, val status: String)

/** A related ticket as the prompt shows it. */
data class RelatedTicketView(
    val id: String,
    val title: String,
    val status: String,
    val branch: String?,
    val outcome: String,
    val files: List<RelatedFile>,
)

/** DAEMON-CONTEXT-1: the daemon-assembled related-work block of a ticket's first prompt. */
fun interface RelatedWorkSource {
    /** The block for [ticketId] (null = nothing to add). Never throws into a start. */
    fun block(ticketId: String, description: String): String?

    companion object {
        val NONE = RelatedWorkSource { _, _ -> null }
    }
}

/**
 * Renders the related block. The prompt is re-read every turn, so it is small: at most [maxTickets] tickets, [maxFiles]
 * files each, [maxLines] lines in all (about 300 to 500 tokens). A file is listed only when it overlaps the ticket's own
 * files or sits in the same module, and never when [isHub] says nearly every ticket touches it.
 */
object RelatedBlock {
    const val HEADER = "RELATED WORK (assembled by the daemon; start from these files, cite the ids):"
    const val NONE_LINE = "RELATED WORK: none found (no files on this branch yet, none named in the text). Look at the board: agenthq_board."
    const val FOOTER = "Full lists: agenthq ticket files <id> --related"

    fun render(
        ownPaths: Set<String>,
        tickets: List<RelatedTicketView>,
        isHub: (String) -> Boolean,
        maxTickets: Int = 3,
        maxFiles: Int = 5,
        maxLines: Int = 10,
    ): String {
        if (tickets.isEmpty()) return NONE_LINE
        val ownModules = ownPaths.map(::module).toSet()
        val lines = ArrayList<String>()
        lines += HEADER
        for (t in tickets.take(maxTickets)) {
            val eligible = t.files.filter { !isHub(it.path) && (it.path in ownPaths || module(it.path) in ownModules) }
                .sortedWith(compareByDescending<RelatedFile> { it.path in ownPaths }.thenBy { it.path })
            val shown = eligible.take(maxFiles)
            val more = t.files.size - shown.size
            val head = "- ${t.id} [${t.status}]${t.branch?.let { " $it" }.orEmpty()}: ${clip(t.title, 60)} - ${clip(t.outcome, 70)}"
            val fileLine = shown.joinToString(", ") { "${it.status} ${shortPath(it.path)}" } +
                (if (more > 0) (if (shown.isEmpty()) "+$more files" else " +$more more") else "")
            val need = if (fileLine.isBlank()) 1 else 2
            if (lines.size + need > maxLines - 1) break
            lines += head
            if (fileLine.isNotBlank()) lines += "    $fileLine"
        }
        lines += FOOTER
        return lines.joinToString("\n")
    }

    /** `superiorSoftLibrary/agentHq/src/main/kotlin/x/Foo.kt` -> `agentHq/Foo.kt`. */
    fun shortPath(path: String): String =
        module(path).let { m -> if (m.isEmpty()) path.substringAfterLast('/') else "$m/${path.substringAfterLast('/')}" }

    /** The module a path lives in: the first directory under `superiorSoftLibrary/`, else the first directory. */
    fun module(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        if (parts.size < 2) return ""
        return if (parts[0] == "superiorSoftLibrary" && parts.size > 2) parts[1] else parts[0]
    }

    private fun clip(text: String, max: Int): String {
        val one = text.replace('\r', ' ').replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }
}
