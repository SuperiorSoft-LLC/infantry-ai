package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.daemon.api.knowledge.Knowledge
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeDigestView
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeHit
import com.superiorsoftllc.agenthq.decisions.DecisionStore
import com.superiorsoftllc.agenthq.issues.IssueStore
import com.superiorsoftllc.agenthq.memory.MemoryIndexEntry
import com.superiorsoftllc.agenthq.memory.MemoryStore
import com.superiorsoftllc.agenthq.memory.SCOPE_ROLE

/** DP-7b: core's [Knowledge] provider, over today's memory, decision and issue stores. */
class LocalKnowledge(
    private val memory: MemoryStore,
    private val decisions: DecisionStore,
    private val issues: IssueStore,
) : Knowledge {
    override fun noteCount(project: String): Int = memory.index(project).size

    override fun digest(project: String, role: String?, budget: Int?): KnowledgeDigestView? {
        val notes = memory.index(project).map { MemoryIndexEntry(it.name, it.description, it.folder) }
        val active = decisions.list(project)
        val open = issues.list(project)
        val d = KnowledgeLines.digest(notes, active, open)
        return KnowledgeDigestView(notes.size, active.size, open.size, d.decisions, d.issues, d.notes)
    }

    override fun section(project: String, name: String, scope: String, owner: String): String? =
        memory.get(project, name, scope, owner)?.body

    override fun search(project: String, term: String, scope: String, owner: String): List<KnowledgeHit> =
        memory.search(project, term, scope, owner).map { KnowledgeHit(it.name, it.description, it.snippet) }

    override fun roleNotes(project: String, role: String): List<Pair<String, String>> =
        memory.index(project, SCOPE_ROLE, role).map { it.name to it.description }
}

/**
 * DP-7b: the [KnowledgeCounter] every first-turn path reads, resolved through the registry on each call, so a provider
 * that is absent (or unloaded) gives no counts: the brief just omits the knowledge section, no error.
 */
class ServiceKnowledgeCounter(private val port: () -> Knowledge?) : KnowledgeCounter {

    override fun roleNotes(projectId: String, role: String): List<Pair<String, String>> =
        port()?.roleNotes(projectId, role).orEmpty()

    override fun counts(projectId: String): KnowledgeCounts? {
        val v = port()?.digest(projectId) ?: return null
        return KnowledgeCounts(v.notes, v.activeDecisions, v.openIssues, KnowledgeDigest(v.decisionLines, v.issueLines, v.noteLines))
    }
}
