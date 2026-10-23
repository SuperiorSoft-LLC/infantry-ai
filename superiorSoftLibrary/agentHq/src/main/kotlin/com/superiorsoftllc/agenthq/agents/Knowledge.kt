package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.decisions.Decision
import com.superiorsoftllc.agenthq.issues.Issue
import com.superiorsoftllc.agenthq.memory.MemoryIndexEntry

/**
 * P10-K9 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md section 4.1): how much one project's knowledge base holds when a first turn
 * renders. Counts only: the section is a pointer, never note bodies or decision text (K-D2).
 */
data class KnowledgeCounts(
    val notes: Int,
    val activeDecisions: Int,
    val openIssues: Int,
    /**
     * BUG-G2e: the knowledge itself, as `agenthq knowledge` lists it. Present (the daemon always builds it), the first
     * turn CARRIES the knowledge; null (a counter that only counts), it falls back to pointing at `agenthq knowledge`.
     */
    val digest: KnowledgeDigest? = null,
) {
    val isEmpty: Boolean get() = notes == 0 && activeDecisions == 0 && openIssues == 0
}

/**
 * P10-K9: reads a project's [KnowledgeCounts] at render time, so a never-run session renders the counts of its first
 * DELIVERY, not of its spawn (K-D3 freshness). Null = no knowledge base to point at, so no section.
 */
fun interface KnowledgeCounter {
    fun counts(projectId: String): KnowledgeCounts?

    /** ROLE-NOTES-1: the (name, description) pairs of [role]'s notes in [projectId]; none unless the daemon wires them. */
    fun roleNotes(projectId: String, role: String): List<Pair<String, String>> = emptyList()

    companion object {
        /** No knowledge base wired: every first turn is exactly the pre-K9 text. */
        val NONE = KnowledgeCounter { null }
    }
}

/** P10-K9: the knowledge section for [projectId] (the session's own project), read now; null with no project. */
fun KnowledgeCounter.section(projectId: String?): String? {
    val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return counts(project)?.let { renderKnowledge(project, it) }
}

/**
 * ROLE-NOTES-1: the knowledge section plus, for a session with a role, its ROLE NOTES section and the one-line scope
 * how-to. Every first-turn path (ticket, spawn, delivered message, resume) renders through this, so a long-lived role
 * sees the notes its role carries. A session with no role gets exactly [section].
 */
fun KnowledgeCounter.section(projectId: String?, role: String?): String? {
    val base = section(projectId)
    val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return base
    val roleId = role?.trim()?.takeIf { it.isNotEmpty() } ?: return base
    val lines = roleNotes(project, roleId)
    val roleSection = buildString {
        if (lines.isEmpty()) {
            append("ROLE NOTES: none yet for the '$roleId' role. ")
        } else {
            append("ROLE NOTES (scope role: notes for the '$roleId' role, they persist with the role and only sessions of that role see them):")
            append(NL)
            append(lines.joinToString(NL) { "- ${it.first}: ${it.second}" })
            append(NL)
        }
        append("Use `agenthq memory index|show|search|save --scope role|session`; the default scope is the shared project notes. ")
        append("A role note added later is announced to you in a mail.")
    }
    return listOfNotNull(base, roleSection).joinToString(NL + NL)
}

private val NL = 10.toChar().toString()

/**
 * P10-K9: the `knowledge` section of a first turn (section 4.1). No project gives null, so a no-project first turn is
 * byte-identical to the pre-K9 text. An empty base gets the short form naming the write verbs; anything else the full
 * form with the live counts and the retrieval order. Pure: it names only verbs the daemon serves (K8).
 * BUG-G2e: with a [KnowledgeCounts.digest] the full form CARRIES the knowledge (capped, see [digestLines]) instead of
 * telling the session to run `agenthq knowledge` first; without one it keeps the pointer form.
 */
fun renderKnowledge(projectId: String?, counts: KnowledgeCounts): String? {
    val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (counts.isEmpty) {
        return "PROJECT KNOWLEDGE. Project $project has an empty knowledge base in the Infantry AI daemon. " +
            "Record what a later session must not re-derive: `agenthq decision add` for a choice, " +
            "`agenthq issue open|attempt|fix` for a bug, `agenthq memory save` for anything else."
    }
    if (counts.digest != null) return renderInjected(project, counts, counts.digest)
    return listOf(
        "PROJECT KNOWLEDGE (this applies to your whole session, including after a context compaction). " +
            "Project $project keeps a shared knowledge base in the Infantry AI daemon: ${counts.notes} notes, " +
            "${counts.activeDecisions} active decisions, ${counts.openIssues} open issues.",
        "Before you explore the code, and before you decide anything an earlier session may already have decided, " +
            "run `agenthq knowledge`: it lists the active decisions, the open issues and the note index. Then walk down: " +
            "`agenthq memory index --folder <folder>`, `agenthq memory search <term>`, `agenthq memory show <name>`, " +
            "`agenthq decision show D<n>`, and `agenthq issue precheck <path>` before you change a file. " +
            "An index line is a retrieval cue, not the content: open the note before you rely on it.",
        "Record through the same CLI, never in files: `agenthq decision add` (decisions are never edited; a new one " +
            "replaces an old one with `--supersedes D<n>`), `agenthq issue open|attempt|fix`, `agenthq memory save` " +
            "(`--append` adds a section without reading the note, `--edit --find \"<exact passage>\"` changes a passage " +
            "that occurs exactly once).",
        "If a command fails, the knowledge base is NOT empty: report the command and its answer, then go on.",
    ).joinToString("\n")
}

/**
 * BUG-G2e: the knowledge `agenthq knowledge` prints, as lines, so the daemon can hand it to a session's first turn
 * instead of asking the model to fetch it. Built by [KnowledgeLines] from the daemon's own stores, in-process.
 */
data class KnowledgeDigest(val decisions: List<String>, val issues: List<String>, val notes: List<String>)

/**
 * BUG-G2e: the ONE place that turns the knowledge stores' rows into the lines `agenthq knowledge` prints, shared by the
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

    fun issues(issues: List<Issue>): List<String> =
        issues.filter { it.status == Issue.STATUS_OPEN }.sortedBy { it.number }
            .map { "${it.ref} ${it.summary}" + if (it.location.isNotEmpty()) " (${it.location})" else "" }

    fun digest(notes: List<MemoryIndexEntry>, decisions: List<Decision>, issues: List<Issue>): KnowledgeDigest =
        KnowledgeDigest(decisions = decisions(decisions), issues = issues(issues), notes = notes(notes))
}

/**
 * BUG-G2e: the heading line that opens an injected digest. [com.superiorsoftllc.agenthq.session.RetrievalClassifier]
 * reads a first turn carrying it as one whose session already had the knowledge (verdict `injected`).
 */
const val KNOWLEDGE_DIGEST_HEADING = "KNOWLEDGE DIGEST (what `agenthq knowledge` prints, already loaded for you):"

/**
 * BUG-G2e caps on the injected digest, so a large base cannot swamp the first turn. Per section: at most
 * [DIGEST_DECISIONS_CAP] active decisions, [DIGEST_ISSUES_CAP] open issues and [DIGEST_NOTES_CAP] note-index lines; and
 * the whole digest body at most [DIGEST_CHARS_CAP] characters. Whatever a cap drops ends in one
 * `N more: run `agenthq knowledge`` line. Decisions come first, so the char cap drops note lines before decisions.
 */
const val DIGEST_DECISIONS_CAP = 30
const val DIGEST_ISSUES_CAP = 20
const val DIGEST_NOTES_CAP = 40
const val DIGEST_CHARS_CAP = 6000

private const val MORE_HINT = "run `agenthq knowledge`"

/**
 * BUG-G2e-1: read before the digest's first entry. A harness can add recalled memory of its own to the turn (QA-G2e: a
 * user-global opencode plugin prepended a `<memory>` block restating a SUPERSEDED decision), which Infantry AI can neither
 * see nor reorder, so the digest itself says it wins over that recall.
 */
internal const val DIGEST_AUTHORITY =
    "This digest is authoritative: where anything else in your context (a recalled memory, an earlier transcript) " +
        "contradicts an entry below, the entry below wins. A decision you remember that is not listed here may have been " +
        "superseded: check it with `agenthq decision show D<n>` before you rely on it."

/** The digest body lines, every cap applied. */
internal fun digestLines(digest: KnowledgeDigest): List<String> {
    val lines = mutableListOf<String>()
    fun section(heading: String, rows: List<String>, cap: Int) {
        lines += heading
        if (rows.isEmpty()) lines += "(none)"
        lines += rows.take(cap)
        if (rows.size > cap) lines += "${rows.size - cap} more: $MORE_HINT"
    }
    section("ACTIVE DECISIONS", digest.decisions, DIGEST_DECISIONS_CAP)
    section("OPEN ISSUES", digest.issues, DIGEST_ISSUES_CAP)
    section("NOTES (index)", digest.notes, DIGEST_NOTES_CAP)
    if (lines.sumOf { it.length + 1 } <= DIGEST_CHARS_CAP) return lines
    // Over the char cap: keep whole lines while they fit (leaving room for the closing line), count every dropped row.
    val closingRoom = 64
    val kept = mutableListOf<String>()
    var used = 0
    for (line in lines) {
        if (used + line.length + 1 > DIGEST_CHARS_CAP - closingRoom) break
        kept += line
        used += line.length + 1
    }
    val dropped = lines.drop(kept.size).filterNot { it.endsWith(" more: $MORE_HINT") || it == "(none)" || it.isHeading() }.size
    return kept + "$dropped more: $MORE_HINT"
}

private fun String.isHeading(): Boolean = this == "ACTIVE DECISIONS" || this == "OPEN ISSUES" || this == "NOTES (index)"

private fun renderInjected(project: String, counts: KnowledgeCounts, digest: KnowledgeDigest): String = listOf(
    "PROJECT KNOWLEDGE (this applies to your whole session, including after a context compaction). " +
        "Project $project keeps a shared knowledge base in the Infantry AI daemon: ${counts.notes} notes, " +
        "${counts.activeDecisions} active decisions, ${counts.openIssues} open issues. " +
        "Its digest is below: you do not need to fetch it before you start.",
    DIGEST_AUTHORITY,
    KNOWLEDGE_DIGEST_HEADING,
    digestLines(digest).joinToString("\n"),
    "Before you decide anything an entry above already decided, and before you rely on an entry, walk down: " +
        "`agenthq memory index --folder <folder>`, `agenthq memory search <term>`, `agenthq memory show <name>`, " +
        "`agenthq decision show D<n>`, and `agenthq issue precheck <path>` before you change a file. " +
        "An index line is a retrieval cue, not the content: open the note before you rely on it.",
    "Record through the same CLI, never in files: `agenthq decision add` (decisions are never edited; a new one " +
        "replaces an old one with `--supersedes D<n>`), `agenthq issue open|attempt|fix`, `agenthq memory save` " +
        "(`--append` adds a section without reading the note, `--edit --find \"<exact passage>\"` changes a passage " +
        "that occurs exactly once).",
    "If a command fails, the knowledge base is NOT empty: report the command and its answer, then go on.",
).joinToString("\n")
