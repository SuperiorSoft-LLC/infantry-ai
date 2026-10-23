package com.superiorsoftllc.agenthq.issues

import com.superiorsoftllc.agenthq.protocol.Envelope
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
    private val push: (Envelope) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    fun answer(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.ISSUE_OPEN -> request(envelope.type) {
            val req = json.decodeFromString(IssueOpen.serializer(), envelope.data)
            pushed(store.open(req.projectId, req.summary, req.location, req.ticketId, resolve(req.sessionId)))
        }

        Envelope.ISSUE_ATTEMPT -> request(envelope.type) {
            val req = json.decodeFromString(IssueAttempt.serializer(), envelope.data)
            pushed(store.attempt(req.projectId, req.summary, req.outcome, req.location, req.issue, resolve(req.sessionId)))
        }

        Envelope.ISSUE_FIX -> request(envelope.type) {
            val req = json.decodeFromString(IssueFix.serializer(), envelope.data)
            pushed(store.fix(req.projectId, req.summary, req.issue, resolve(req.sessionId)))
        }

        Envelope.ISSUE_LIST -> request(envelope.type) {
            val req = json.decodeFromString(IssueList.serializer(), envelope.data)
            issues(store.list(req.projectId, req.includeFixed, req.location))
        }

        Envelope.ISSUE_GET -> request(envelope.type) {
            val req = json.decodeFromString(IssueGet.serializer(), envelope.data)
            item(
                store.get(req.projectId, req.ref) ?: throw IssueRefusal(
                    IssueRefusal.ISSUE_NOT_FOUND,
                    "no issue ${req.ref.trim()} in project '${req.projectId.trim()}'",
                ),
            )
        }

        Envelope.ISSUE_PRECHECK -> request(envelope.type) {
            val req = json.decodeFromString(IssuePrecheck.serializer(), envelope.data)
            issues(store.precheck(req.projectId, req.path))
        }

        else -> null
    }

    private fun resolve(sessionId: String?): IssueActor =
        sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            actor(id) ?: throw IssueRefusal(IssueRefusal.SESSION_NOT_FOUND, "no session '$id' on this daemon")
        } ?: IssueActor.NONE

    private fun pushed(issue: Issue): Envelope? {
        push(item(issue))
        return null
    }

    private fun item(issue: Issue) = Envelope(Envelope.ISSUE_ITEM, json.encodeToString(Issue.serializer(), issue))

    private fun issues(list: List<Issue>) = Envelope(Envelope.ISSUES, json.encodeToString(ListSerializer(Issue.serializer()), list))

    private inline fun request(frame: String, block: () -> Envelope?): Envelope? = try {
        block()
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
