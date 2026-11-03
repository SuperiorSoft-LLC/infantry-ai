// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.agents.KnowledgeLines
import com.superiorsoftllc.infantry.daemon.api.knowledge.Knowledge
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeDecision
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeDigestView
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeHit
import com.superiorsoftllc.infantry.memory.MemoryIndexEntry
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeIssue
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeRecords

/**
 * DP-7c: stands in for the Infantry_Notes plugin's decisions/issues half in core tests (the real one lives in the
 * plugin and is tested there). [install] puts it where the daemon's Knowledge port reads it; [uninstall] removes it.
 */
class FakeKnowledgeRecords : KnowledgeRecords, Knowledge {
    private data class N(val project: String, val name: String, val description: String, val body: String, val folder: String)
    private val notes = mutableListOf<N>()

    /** DP-7f: a project note, as the plugin's memory store would hold it. */
    fun saveNote(project: String, name: String, description: String, body: String, folder: String = "") {
        notes.removeAll { it.project == project && it.name == name }
        notes += N(project, name, description, body, folder)
    }

    private fun index(project: String) = notes.filter { it.project == project }.map { MemoryIndexEntry(it.name, it.description, it.folder) }

    override fun noteCount(project: String) = notes.count { it.project == project }

    override fun digest(project: String, role: String?, budget: Int?): KnowledgeDigestView {
        val d = activeDecisions(project)
        val i = openIssues(project)
        return KnowledgeDigestView(
            noteCount(project), d.size, i.size,
            KnowledgeLines.decisionTitleLines(d.map { it.ref to it.summary }),
            KnowledgeLines.issueLines(i.map { Triple(it.ref, it.summary, it.location) }),
            KnowledgeLines.notes(index(project)),
        )
    }

    override fun section(project: String, name: String, scope: String, owner: String) =
        notes.firstOrNull { it.project == project && it.name == name }?.body

    override fun search(project: String, term: String, scope: String, owner: String): List<KnowledgeHit> =
        if (term.isBlank()) emptyList()
        else notes.filter { it.project == project && (term in it.name || term in it.description || term in it.body) }
            .map { KnowledgeHit(it.name, it.description, it.body.take(80)) }

    override fun roleNotes(project: String, role: String): List<Pair<String, String>> = emptyList()

    private data class D(val project: String, val number: Int, val summary: String, var active: Boolean = true)
    private data class I(val project: String, val number: Int, val summary: String, val location: String, var open: Boolean = true)

    private val decisions = mutableListOf<D>()
    private val issues = mutableListOf<I>()

    fun addDecision(project: String, summary: String, supersedes: String? = null) {
        if (supersedes != null) decisions.first { it.project == project && "D${it.number}" == supersedes }.active = false
        decisions += D(project, decisions.count { it.project == project } + 1, summary)
    }

    fun openIssue(project: String, summary: String, location: String) {
        issues += I(project, issues.count { it.project == project } + 1, summary, location)
    }

    fun fixIssue(project: String, ref: String) {
        issues.first { it.project == project && "I${it.number}" == ref }.open = false
    }

    override fun activeDecisions(project: String) =
        decisions.filter { it.project == project && it.active }.sortedBy { it.number }.map { KnowledgeDecision("D${it.number}", it.summary) }

    override fun openIssues(project: String) =
        issues.filter { it.project == project && it.open }.sortedBy { it.number }.map { KnowledgeIssue("I${it.number}", it.summary, it.location) }

    fun install(): FakeKnowledgeRecords = also { testKnowledge = it }

    companion object {
        fun uninstall() {
            testKnowledge = null
        }
    }
}
