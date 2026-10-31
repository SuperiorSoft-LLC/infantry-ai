package com.superiorsoftllc.agenthq.ticketfiles

/** One file of a related ticket: git's letter, the repo path and its +/- line count (0 = unknown or binary). */
data class RelatedFile(val path: String, val status: String, val churn: Long = 0)

/** A related ticket as the prompt shows it. */
data class RelatedTicketView(
    val id: String,
    val title: String,
    val status: String,
    val branch: String?,
    val outcome: String,
    val files: List<RelatedFile>,
    /** KNOWLEDGE-PTR-1: why it is related (`linked`, `named`, `series`, `same files`); null = not said. */
    val reason: String? = null,
    /** KNOWLEDGE-PTR-1: the commit to `git show` for its diff (merge, else recorded tip); null = none recorded. */
    val sha: String? = null,
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
 * files each, [maxChars] characters in all (KNOWLEDGE-PTR-1: the `limits.ticketFiles.brief*` settings). A file is listed
 * when it overlaps the ticket's own files or sits in the same module, else (nothing overlaps: a todo ticket, a series
 * member) the related ticket's biggest changes; never when [isHub] says nearly every ticket touches it. No related
 * ticket = no block (null).
 */
object RelatedBlock {
    const val HEADER = "RELATED WORK (assembled by the daemon; start from these files, cite the ids):"
    const val FOOTER = "Full lists: agenthq ticket files <id> --related"

    fun render(
        ownPaths: Set<String>,
        tickets: List<RelatedTicketView>,
        isHub: (String) -> Boolean,
        maxTickets: Int = 5,
        maxFiles: Int = 5,
        maxChars: Int = 1500,
        instruction: String? = null,
    ): String? {
        if (tickets.isEmpty()) return null
        val ownModules = ownPaths.map(::module).toSet()
        val closing = listOf(FOOTER) + listOfNotNull(instruction?.trim()?.takeIf { it.isNotEmpty() })
        val lines = ArrayList<String>()
        lines += HEADER
        var used = HEADER.length + closing.sumOf { it.length + 1 }
        for (t in tickets.take(maxTickets)) {
            val candidates = t.files.filter { !isHub(it.path) }
            val near = candidates.filter { it.path in ownPaths || module(it.path) in ownModules }
                .sortedWith(compareByDescending<RelatedFile> { it.path in ownPaths }.thenBy { it.path })
            val pool = near.ifEmpty { candidates.sortedWith(compareByDescending<RelatedFile> { it.churn }.thenBy { it.path }) }
            val shown = pool.take(maxFiles.coerceAtLeast(0))
            val more = t.files.size - shown.size
            val why = t.reason?.let { "$it: " }.orEmpty()
            val head = "- $why${t.id} [${t.status}]${t.branch?.let { " $it" }.orEmpty()}: ${clip(t.title, 60)} - ${clip(t.outcome, 70)}"
            val fileLine = shown.joinToString(", ") { "${it.status} ${shortPath(it.path)}" } +
                (if (more > 0) (if (shown.isEmpty()) "+$more files" else " +$more more") else "") +
                (t.sha?.let { " (git show ${it.take(8)} for the diff)" }.orEmpty())
            val add = head.length + 1 + (if (fileLine.isBlank()) 0 else fileLine.trim().length + 5)
            if (used + add > maxChars) break
            lines += head
            if (fileLine.isNotBlank()) lines += "    ${fileLine.trim()}"
            used += add
        }
        if (lines.size == 1) return null
        lines += closing
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
