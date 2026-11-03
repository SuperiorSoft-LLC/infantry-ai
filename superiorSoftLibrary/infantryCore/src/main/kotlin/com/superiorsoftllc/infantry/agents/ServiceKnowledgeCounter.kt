// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.daemon.api.knowledge.Knowledge

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
