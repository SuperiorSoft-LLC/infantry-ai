package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeDecision
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeIssue
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeRecords

/**
 * DP-7c: stands in for the Infantry_Notes plugin's decisions/issues half in core tests (the real one lives in the
 * plugin and is tested there). [install] puts it where the daemon's Knowledge port reads it; [uninstall] removes it.
 */
class FakeKnowledgeRecords : KnowledgeRecords {
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

    fun install(): FakeKnowledgeRecords = also { testKnowledgeRecords = it }

    companion object {
        fun uninstall() {
            testKnowledgeRecords = null
        }
    }
}
