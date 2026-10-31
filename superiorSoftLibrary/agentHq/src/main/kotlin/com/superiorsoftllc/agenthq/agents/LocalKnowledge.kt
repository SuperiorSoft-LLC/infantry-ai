package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.daemon.api.knowledge.Knowledge
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeDigestView
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeHit
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeRecords
import com.superiorsoftllc.agenthq.memory.MemoryIndexEntry
import com.superiorsoftllc.agenthq.memory.MemoryStore
import com.superiorsoftllc.agenthq.memory.SCOPE_ROLE

/**
 * DP-7b: core's [Knowledge] provider, over the memory store. DP-7c: the decisions and issues half comes from [records], the
 * [KnowledgeRecords] the Infantry_Notes plugin publishes; with none (plugin absent) the digest has no decisions and no issues.
 */
class LocalKnowledge(
    private val memory: MemoryStore,
    private val records: () -> KnowledgeRecords?,
) : Knowledge {
    override fun noteCount(project: String): Int = memory.index(project).size

    override fun digest(project: String, role: String?, budget: Int?): KnowledgeDigestView? {
        val notes = memory.index(project).map { MemoryIndexEntry(it.name, it.description, it.folder) }
        val active = records()?.activeDecisions(project).orEmpty()
        val open = records()?.openIssues(project).orEmpty()
        return KnowledgeDigestView(
            notes.size, active.size, open.size,
            KnowledgeLines.decisionTitleLines(active.map { it.ref to it.summary }),
            KnowledgeLines.issueLines(open.map { Triple(it.ref, it.summary, it.location) }),
            KnowledgeLines.notes(notes),
        )
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
