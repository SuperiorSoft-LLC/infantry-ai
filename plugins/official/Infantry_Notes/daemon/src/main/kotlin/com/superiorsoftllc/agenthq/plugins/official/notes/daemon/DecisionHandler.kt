package com.superiorsoftllc.agenthq.plugins.official.notes.daemon

import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.decisions.Decision
import com.superiorsoftllc.agenthq.decisions.DecisionAdd
import com.superiorsoftllc.agenthq.decisions.DecisionGet
import com.superiorsoftllc.agenthq.decisions.DecisionList
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * P10-K4 (contract 1.53.0): the `decision_*` wire surface over a [DecisionStore].
 *
 *  - `decision_add` ([DecisionAdd]) -> a `decision_item` PUSHED to every client through [push] (for the client that
 *    asked, the push IS the answer, so [answer] returns null);
 *  - `decision_list` ([DecisionList]) -> `decisions`, number order, active only unless `includeSuperseded`;
 *  - `decision_get` ([DecisionGet]) -> `decision_item`, or refused `decision_not_found`.
 *
 * [author] resolves a declared session id to its author fields, or null for a session the daemon does not know
 * (refused `session_not_found`). A refusal answers `<frame> refused: <why>`, a coded one ([DecisionRefusal]) code first.
 * Returns null for a frame that is not this handler's.
 */
class DecisionHandler(
    private val store: DecisionStore,
    private val author: (sessionId: String) -> DecisionAuthor?,
    private val push: (Frame) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    fun answer(frame: Frame): Frame? = when (frame.type) {
        Envelope.DECISION_ADD -> request(frame.type) {
            val req = json.decodeFromString(DecisionAdd.serializer(), frame.data)
            val by = req.sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                author(id) ?: throw DecisionRefusal(DecisionRefusal.SESSION_NOT_FOUND, "no session '$id' on this daemon")
            } ?: DecisionAuthor.NONE
            val added = store.add(req.projectId, req.summary, req.body, req.supersedes, by)
            push(item(added))
            null
        }

        Envelope.DECISION_LIST -> request(frame.type) {
            val req = json.decodeFromString(DecisionList.serializer(), frame.data)
            Frame(
                Envelope.DECISIONS,
                json.encodeToString(ListSerializer(Decision.serializer()), store.list(req.projectId, req.includeSuperseded)),
            )
        }

        Envelope.DECISION_GET -> request(frame.type) {
            val req = json.decodeFromString(DecisionGet.serializer(), frame.data)
            val found = store.get(req.projectId, req.ref) ?: throw DecisionRefusal(
                DecisionRefusal.DECISION_NOT_FOUND,
                "no decision ${req.ref.trim()} in project '${req.projectId.trim()}'",
            )
            item(found)
        }

        else -> null
    }

    private fun item(decision: Decision) = Frame(Envelope.DECISION_ITEM, json.encodeToString(Decision.serializer(), decision))

    private inline fun request(frame: String, block: () -> Frame?): Frame? = try {
        block()
    } catch (e: IllegalArgumentException) {
        Frame(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Frame(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
