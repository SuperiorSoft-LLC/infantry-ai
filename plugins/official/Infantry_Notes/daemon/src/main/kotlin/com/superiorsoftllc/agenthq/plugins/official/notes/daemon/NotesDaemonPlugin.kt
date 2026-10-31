package com.superiorsoftllc.agenthq.plugins.official.notes.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeDecision
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeIssue
import com.superiorsoftllc.agenthq.daemon.api.knowledge.KnowledgeRecords
import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.agenthq.protocol.Envelope

/**
 * DP-7c: Infantry_Notes' daemon half. The project decisions log (P10-K4) and issues log (P10-K6) over the adopted `decisions`,
 * `issues` and `issue_events` tables (module.json `daemon.storage.adopt`), their six-plus-three wire frames (declared in
 * `daemon.frames`, same types, actions and kinds as before), and [KnowledgeRecords], the decisions / issues half of the
 * Knowledge port that core's digest reads. The author of an entry is the declared session; an add is pushed to every client
 * through the board runtime's push, like the pipeline's. Memory notes stay in core until DP-7d.
 */
class NotesDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val decisions = DecisionStore(api.storage)
        val issues = IssueStore(api.storage)
        val push = { frame: Frame -> api.boardRuntime.push(listOf(frame)) }
        // The handlers are cheap and hold no state beyond the stores; one per call keeps the caller's session lookup scoped to it.
        fun session(id: String, caller: com.superiorsoftllc.agenthq.daemon.api.Caller) =
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
        api.services.provide(KnowledgeRecords::class, object : KnowledgeRecords {
            override fun activeDecisions(project: String) =
                decisions.list(project).filter { it.active }.sortedBy { it.number }.map { KnowledgeDecision(it.ref, it.summary) }

            override fun openIssues(project: String) =
                issues.list(project).filter { it.status == com.superiorsoftllc.agenthq.issues.Issue.STATUS_OPEN }.sortedBy { it.number }
                    .map { KnowledgeIssue(it.ref, it.summary, it.location) }
        })
    }
}
