package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.issues.Issue
import com.superiorsoftllc.infantry.issues.IssueAttempt
import com.superiorsoftllc.infantry.issues.IssueFix
import com.superiorsoftllc.infantry.issues.IssueGet
import com.superiorsoftllc.infantry.issues.IssueList
import com.superiorsoftllc.infantry.issues.IssueOpen
import com.superiorsoftllc.infantry.issues.IssuePrecheck
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * P10-K6 (contract 1.54.0): the `issue_*` wire surface over an [IssueStore].
 *
 *  - `issue_open` ([IssueOpen]), `issue_attempt` ([IssueAttempt]), `issue_fix` ([IssueFix]) -> the whole issue as an
 *    `issue_item` PUSHED to every client through [push] (for the client that asked, the push IS the answer, so
 *    [answer] returns null);
 *  - `issue_list` ([IssueList]) -> `issues`, number order, open only unless `includeFixed`;
 *  - `issue_get` ([IssueGet]) -> `issue_item`, or refused `issue_not_found`;
 *  - `issue_precheck` ([IssuePrecheck]) -> `issues`, open first.
 *
 * [actor] resolves a declared session id to its author fields, or null for a session the daemon does not know (refused
 * `session_not_found`). The declared session is also what the ACTIVE issue is resolved against. A refusal answers
 * `<frame> refused: <why>`, a coded one ([IssueRefusal]) code first. Returns null for a frame that is not this handler's.
 */
class IssueHandler(
    private val store: IssueStore,
    private val actor: (sessionId: String) -> IssueActor?,
    private val push: (Frame) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    fun answer(frame: Frame): Frame? = when (frame.type) {
        Envelope.ISSUE_OPEN -> request(frame.type) {
            val req = json.decodeFromString(IssueOpen.serializer(), frame.data)
            pushed(store.open(req.projectId, req.summary, req.location, req.ticketId, resolve(req.sessionId)))
        }

        Envelope.ISSUE_ATTEMPT -> request(frame.type) {
            val req = json.decodeFromString(IssueAttempt.serializer(), frame.data)
            pushed(store.attempt(req.projectId, req.summary, req.outcome, req.location, req.issue, resolve(req.sessionId)))
        }

        Envelope.ISSUE_FIX -> request(frame.type) {
            val req = json.decodeFromString(IssueFix.serializer(), frame.data)
            pushed(store.fix(req.projectId, req.summary, req.issue, resolve(req.sessionId)))
        }

        Envelope.ISSUE_LIST -> request(frame.type) {
            val req = json.decodeFromString(IssueList.serializer(), frame.data)
            issues(store.list(req.projectId, req.includeFixed, req.location))
        }

        Envelope.ISSUE_GET -> request(frame.type) {
            val req = json.decodeFromString(IssueGet.serializer(), frame.data)
            item(
                store.get(req.projectId, req.ref) ?: throw IssueRefusal(
                    IssueRefusal.ISSUE_NOT_FOUND,
                    "no issue ${req.ref.trim()} in project '${req.projectId.trim()}'",
                ),
            )
        }

        Envelope.ISSUE_PRECHECK -> request(frame.type) {
            val req = json.decodeFromString(IssuePrecheck.serializer(), frame.data)
            issues(store.precheck(req.projectId, req.path))
        }

        else -> null
    }

    private fun resolve(sessionId: String?): IssueActor =
        sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            actor(id) ?: throw IssueRefusal(IssueRefusal.SESSION_NOT_FOUND, "no session '$id' on this daemon")
        } ?: IssueActor.NONE

    private fun pushed(issue: Issue): Frame? {
        push(item(issue))
        return null
    }

    private fun item(issue: Issue) = Frame(Envelope.ISSUE_ITEM, json.encodeToString(Issue.serializer(), issue))

    private fun issues(list: List<Issue>) = Frame(Envelope.ISSUES, json.encodeToString(ListSerializer(Issue.serializer()), list))

    private inline fun request(frame: String, block: () -> Frame?): Frame? = try {
        block()
    } catch (e: IllegalArgumentException) {
        Frame(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Frame(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
