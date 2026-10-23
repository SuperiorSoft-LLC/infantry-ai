package com.superiorsoftllc.agenthq.pipeline

import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json

/**
 * PIPE-1: the `pipeline_*` wire surface over a [PipelineStore]. Every frame is answered by one
 * `pipeline_state`; a refusal answers `<frame> refused: <why>`. [author] resolves a declared session id to a
 * "who moved it" label (role and agent), or null for a session the daemon does not know.
 */
class PipelineHandler(
    private val store: PipelineStore,
    private val author: (sessionId: String) -> String?,
    /** PIPE-2 (contract 1.99.0): called with the new `pipeline_state` after every change, so the daemon can push it to every client. */
    private val push: (Envelope) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    fun answer(envelope: Envelope): Envelope? = answerFrame(envelope)?.also {
        if (it.type == Envelope.PIPELINE_STATE && envelope.type != Envelope.PIPELINE_SHOW) push(it)
    }

    private fun answerFrame(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.PIPELINE_CONFIG -> request(envelope.type) {
            val req = json.decodeFromString(PipelineConfigure.serializer(), envelope.data)
            store.configure(req.projectId, req.stages, req.exclusiveCheck, req.clear)
        }
        Envelope.PIPELINE_SHOW -> request(envelope.type) {
            store.state(json.decodeFromString(PipelineShow.serializer(), envelope.data).projectId)
        }
        Envelope.PIPELINE_RECORD -> request(envelope.type) {
            val req = json.decodeFromString(PipelineRecord.serializer(), envelope.data)
            store.record(req.projectId, req.commit, req.stage, req.evidence, who(req.sessionId))
        }
        Envelope.PIPELINE_CHECK -> request(envelope.type) {
            val req = json.decodeFromString(PipelineCheckRequest.serializer(), envelope.data)
            store.check(req.projectId, req.action, req.scope, req.commit, req.what, who(req.sessionId))
        }
        else -> null
    }

    private fun who(sessionId: String?): String? = sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
        author(id) ?: throw IllegalArgumentException("session_not_found: no session '$id' on this daemon")
    }

    private inline fun request(frame: String, block: () -> PipelineState): Envelope = try {
        Envelope(Envelope.PIPELINE_STATE, json.encodeToString(PipelineState.serializer(), block()))
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
