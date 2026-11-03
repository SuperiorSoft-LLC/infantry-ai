// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.knowledge

/**
 * DP-7b (DaemonApi 1.17.0, additive; docs/DAEMON-CORE.md section 8): the read-only knowledge port, a service published in the
 * AR-5 registry (`services.find(Knowledge::class)`). Core registers a local provider over the memory / decision / issue stores.
 * Consumers (the first-turn digest, the board brief) own the absent-provider case: no provider = no knowledge section.
 */
interface Knowledge {
    /** How many project notes [project] holds. */
    fun noteCount(project: String): Int

    /**
     * The knowledge digest of [project]: counts plus the already-filtered lines (active decisions as titles, open issues, note
     * index), and, for a [role], that role's notes. [budget] is a reserved character budget for the caller's rendering (null =
     * the shipped caps; the local provider applies those caps at render time). Null = no knowledge for that project.
     */
    fun digest(project: String, role: String? = null, budget: Int? = null): KnowledgeDigestView?

    /** One note body of [project] by [name], in [scope] (`project`, `role` or `session`, owned by [owner]); null = none. */
    fun section(project: String, name: String, scope: String = SCOPE_PROJECT, owner: String = ""): String?

    /** Notes of [project] matching [term] in [scope], best match first. An empty term matches nothing. */
    fun search(project: String, term: String, scope: String = SCOPE_PROJECT, owner: String = ""): List<KnowledgeHit>

    /** The (name, description) pairs of [role]'s notes in [project]. */
    fun roleNotes(project: String, role: String): List<Pair<String, String>>

    companion object {
        const val SCOPE_PROJECT = "project"
    }
}

/** What [Knowledge.digest] returns: counts, and the display lines already filtered (superseded decisions / fixed issues are out). */
data class KnowledgeDigestView(
    val notes: Int,
    val activeDecisions: Int,
    val openIssues: Int,
    val decisionLines: List<String>,
    val issueLines: List<String>,
    val noteLines: List<String>,
)

/** One [Knowledge.search] hit. */
data class KnowledgeHit(val name: String, val description: String, val snippet: String)

/**
 * DP-7c (DaemonApi 1.17.0, additive): the decisions / issues half of [Knowledge], a service published in the AR-5 registry by
 * the plugin that owns those logs (Infantry_Notes). Core's local [Knowledge] provider reads it for the digest: with no
 * provider the digest simply has no decisions and no issues. Read-only; the lines are ordered by number.
 */
interface KnowledgeRecords {
    /** The ACTIVE (not superseded) decisions of [project], in number order. */
    fun activeDecisions(project: String): List<KnowledgeDecision>

    /** The OPEN (not fixed) issues of [project], in number order. */
    fun openIssues(project: String): List<KnowledgeIssue>
}

/** An active decision: [ref] is `D<n>`, [summary] its one line. */
data class KnowledgeDecision(val ref: String, val summary: String)

/** An open issue: [ref] is `I<n>`, [location] the path it is about ("" for none). */
data class KnowledgeIssue(val ref: String, val summary: String, val location: String)
