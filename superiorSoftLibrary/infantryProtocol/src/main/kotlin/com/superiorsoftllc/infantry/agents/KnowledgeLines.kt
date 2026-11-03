package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.decisions.Decision
import com.superiorsoftllc.infantry.issues.Issue
import com.superiorsoftllc.infantry.memory.MemoryIndexEntry

// DP-7f: moved out of core (infantry) into the protocol module so the Infantry_Notes daemon plugin can build the digest lines.
/**
 * BUG-G2e: the knowledge `infantry knowledge` prints, as lines, so the daemon can hand it to a session's first turn
 * instead of asking the model to fetch it. Built by [KnowledgeLines] from the daemon's own stores, in-process.
 */
data class KnowledgeDigest(val decisions: List<String>, val issues: List<String>, val notes: List<String>)

/**
 * BUG-G2e: the ONE place that turns the knowledge stores' rows into the lines `infantry knowledge` prints, shared by the
 * CLI and the first turn so both always show the same thing. Superseded decisions and fixed issues are filtered here.
 */
object KnowledgeLines {
    /** Above this many notes the note index folds to its top folders (with counts), then the root notes. */
    const val NOTES_FOLD = 60

    fun indexLine(entry: MemoryIndexEntry): String =
        (if (entry.folder.isEmpty()) entry.name else "${entry.folder}/${entry.name}") + ": " + entry.description

    fun notes(entries: List<MemoryIndexEntry>): List<String> {
        val sorted = entries.sortedWith(compareBy<MemoryIndexEntry>({ it.folder }, { it.name }))
        if (sorted.size <= NOTES_FOLD) return sorted.map(::indexLine)
        val (root, filed) = sorted.partition { it.folder.isEmpty() }
        return filed.groupBy { it.folder.substringBefore('/') }.toSortedMap().map { (top, inTop) -> "$top/ (${inTop.size} notes)" } +
            root.map(::indexLine)
    }

    fun decisions(decisions: List<Decision>): List<String> =
        decisions.filter { it.active }.sortedBy { it.number }.map { "${it.ref} ${it.summary}" }

    /** TOKEN-5: a decision as a title only (first sentence, at most [TITLE_CHARS]); the full text is `infantry decision show`. */
    const val TITLE_CHARS = 90

    fun decisionTitles(decisions: List<Decision>): List<String> =
        decisionTitleLines(decisions.filter { it.active }.sortedBy { it.number }.map { it.ref to it.summary })

    /** DP-7c: the open-issue lines over (ref, summary, location) triples already filtered and ordered. */
    fun issueLines(issues: List<Triple<String, String, String>>): List<String> =
        issues.map { (ref, summary, location) -> "$ref $summary" + if (location.isNotEmpty()) " ($location)" else "" }

    /** DP-7c: [decisionTitles] over (ref, summary) pairs already filtered and ordered (what the Knowledge records port returns). */
    fun decisionTitleLines(decisions: List<Pair<String, String>>): List<String> =
        decisions.map { (ref, summary) ->
            val one = summary.trim().lineSequence().first()
            val first = Regex("^.*?[.!?](?=\\s|\$)").find(one)?.value ?: one
            val title = if (first.length > TITLE_CHARS) first.take(TITLE_CHARS - 1).trimEnd() + "…" else first
            "$ref $title"
        }

    fun issues(issues: List<Issue>): List<String> =
        issues.filter { it.status == Issue.STATUS_OPEN }.sortedBy { it.number }
            .map { "${it.ref} ${it.summary}" + if (it.location.isNotEmpty()) " (${it.location})" else "" }

    fun digest(notes: List<MemoryIndexEntry>, decisions: List<Decision>, issues: List<Issue>): KnowledgeDigest =
        KnowledgeDigest(decisions = decisionTitles(decisions), issues = issues(issues), notes = notes(notes))
}
