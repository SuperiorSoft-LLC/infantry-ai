// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.agents.KnowledgeLines
import com.superiorsoftllc.infantry.daemon.api.knowledge.Knowledge
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeDecision
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeDigestView
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeHit
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeIssue
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeRecords
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.memory.MemoryIndexEntry
import com.superiorsoftllc.infantry.memory.SCOPE_ROLE
import com.superiorsoftllc.infantry.protocol.Envelope

/**
 * DP-7c: Infantry_Notes' daemon half. The project decisions log (P10-K4) and issues log (P10-K6) over the adopted `decisions`,
 * `issues` and `issue_events` tables (module.json `daemon.storage.adopt`), their six-plus-three wire frames (declared in
 * `daemon.frames`, same types, actions and kinds as before), and [KnowledgeRecords], the decisions / issues half of the
 * Knowledge port that core's digest reads. The author of an entry is the declared session; an add is pushed to every client
 * through the board runtime's push, like the pipeline's. DP-7f: the memory notes too (MemoryStore over the adopted `memory_notes` table, the six `memory_*` frames, a session's notes dropped
 * on `events.onSessionRemoved`, a role-note change mailed through `sessionNotifier`), and the whole [Knowledge] port
 * (digest, section, search, roleNotes) that core's first turn and board brief read through the service registry.
 */
class NotesDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val decisions = DecisionStore(api.storage)
        val issues = IssueStore(api.storage)
        val memory = MemoryStore(StorageMemoryPersistence(api.storage))
        api.events.onSessionRemoved { id -> memory.purgeSession(id) }
        api.tools.register(MemoryTool { id -> api.sessions.get(id)?.projectId })
        val push = { frame: Frame -> api.boardRuntime.push(listOf(frame)) }
        // The handlers are cheap and hold no state beyond the stores; one per call keeps the caller's session lookup scoped to it.
        fun session(id: String, caller: com.superiorsoftllc.infantry.daemon.api.Caller) =
            (api.sessionReads.find(id, caller) as? SessionsResult.Ok)?.value?.takeIf { it.id == id }
        for (type in listOf(Envelope.DECISION_ADD, Envelope.DECISION_LIST, Envelope.DECISION_GET)) {
            api.frames.handle(type) { frame, caller ->
                DecisionHandler(decisions, { id -> session(id, caller)?.let { DecisionAuthor(it.id, it.agentId, it.role) } }, push).answer(frame)
            }
        }
        for (type in listOf(Envelope.ISSUE_OPEN, Envelope.ISSUE_ATTEMPT, Envelope.ISSUE_FIX, Envelope.ISSUE_LIST, Envelope.ISSUE_GET, Envelope.ISSUE_PRECHECK)) {
            api.frames.handle(type) { frame, caller ->
                IssueHandler(issues, { id -> session(id, caller)?.let { IssueActor(it.id, it.agentId) } }, push).answer(frame)
            }
        }
        for (type in listOf(Envelope.MEMORY_SEARCH, Envelope.MEMORY_SAVE, Envelope.MEMORY_LIST, Envelope.MEMORY_GET, Envelope.MEMORY_DELETE, Envelope.MEMORY_PURGE_ROLE)) {
            api.frames.handle(type) { frame, caller ->
                MemoryHandler(
                    memory,
                    mayWriteGlobal = { role ->
                        val roles = (api.settings.stored("knowledge.globalWriteRoles") ?: MemoryHandler.PM_ROLE).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        role in roles
                    },
                    audit = { api.log(it) },
                    caller = { id -> session(id, caller)?.let { MemoryCaller(it.id, it.role.orEmpty()) } },
                    // ROLE-NOTES-1: a role note changed while sessions of that role run is announced to each by mail.
                    onRoleNoteChanged = { project, role, name, deleted ->
                        // ROLE-NOTE-READ-1: a short note travels in the notice; the exact tool call that reads any note is named.
                        val body = if (deleted) null else memory.get(project, name, SCOPE_ROLE, role)?.body?.takeIf { it.length <= ROLE_NOTE_INLINE_CHARS }
                        api.sessionNotifier.notifyRole(
                            project, role, "Role note ${if (deleted) "deleted" else "changed"}: $name",
                            "The role note '$name' of your role '$role' was ${if (deleted) "deleted" else "added or changed"}. " +
                                "Re-read it: MCP tool infantry_memory {\"action\":\"show\",\"name\":\"$name\",\"scope\":\"role\"} " +
                                "(CLI: `infantry memory show $name --scope role`; index: infantry_memory {\"action\":\"index\",\"scope\":\"role\"})." +
                                (body?.let { "\n\n--- $name ---\n$it" } ?: ""),
                        )
                    },
                ).answer(frame)
            }
        }
        val activeDecisions = { project: String -> decisions.list(project).filter { it.active }.sortedBy { it.number } }
        val openIssues = { project: String -> issues.list(project).filter { it.status == com.superiorsoftllc.infantry.issues.Issue.STATUS_OPEN }.sortedBy { it.number } }
        api.services.provide(Knowledge::class, object : Knowledge {
            override fun noteCount(project: String): Int = memory.index(project).size

            override fun digest(project: String, role: String?, budget: Int?): KnowledgeDigestView? {
                val notes = memory.index(project).map { MemoryIndexEntry(it.name, it.description, it.folder) }
                val active = activeDecisions(project)
                val open = openIssues(project)
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
        })
        api.services.provide(com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeWriter::class, object : com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeWriter {
            override fun save(project: String, name: String, description: String, body: String, folder: String?) {
                memory.save(project, name, description, body, folder)
            }

            override fun delete(project: String, name: String): Boolean = memory.delete(project, name)

            override fun names(project: String, prefix: String): List<String> = memory.index(project).map { it.name }.filter { it.startsWith(prefix) }
        })
        api.services.provide(KnowledgeRecords::class, object : KnowledgeRecords {
            override fun activeDecisions(project: String) =
                decisions.list(project).filter { it.active }.sortedBy { it.number }.map { KnowledgeDecision(it.ref, it.summary) }

            override fun openIssues(project: String) =
                issues.list(project).filter { it.status == com.superiorsoftllc.infantry.issues.Issue.STATUS_OPEN }.sortedBy { it.number }
                    .map { KnowledgeIssue(it.ref, it.summary, it.location) }
        })
    }

    private companion object {
        /** ROLE-NOTE-READ-1: a role note up to this long is carried in its "changed" notice; longer ones are read with infantry_memory. */
        const val ROLE_NOTE_INLINE_CHARS = 1500
    }
}
