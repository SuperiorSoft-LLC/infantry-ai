// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.pipeline.PipelineCheckRequest
import com.superiorsoftllc.infantry.pipeline.PipelineConfigure
import com.superiorsoftllc.infantry.pipeline.PipelineRecord
import com.superiorsoftllc.infantry.pipeline.PipelineShow
import com.superiorsoftllc.infantry.pipeline.PipelineState
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json

/**
 * PIPE-1 / DP-6: the `pipeline_*` wire surface over a [PipelineStore]. Every frame is answered by one
 * `pipeline_state`; a refusal answers `<frame> refused: <why>`. [author] resolves a declared session id to a
 * "who moved it" label (role and agent), or null for a session the daemon does not know.
 */
class PipelineHandler(
    private val store: PipelineStore,
    private val author: (sessionId: String) -> String?,
    /** PIPE-2: called with the new `pipeline_state` after every change, so the daemon can push it to every client. */
    private val push: (Frame) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    fun answer(frame: Frame): Frame? = answerFrame(frame)?.also {
        if (it.type == Envelope.PIPELINE_STATE && frame.type != Envelope.PIPELINE_SHOW) push(it)
    }

    private fun answerFrame(frame: Frame): Frame? = when (frame.type) {
        Envelope.PIPELINE_CONFIG -> request(frame.type) {
            val req = json.decodeFromString(PipelineConfigure.serializer(), frame.data)
            store.configure(req.projectId, req.stages, req.exclusiveCheck, req.clear)
        }
        Envelope.PIPELINE_SHOW -> request(frame.type) {
            store.state(json.decodeFromString(PipelineShow.serializer(), frame.data).projectId)
        }
        Envelope.PIPELINE_RECORD -> request(frame.type) {
            val req = json.decodeFromString(PipelineRecord.serializer(), frame.data)
            store.record(req.projectId, req.commit, req.stage, req.evidence, who(req.sessionId))
        }
        Envelope.PIPELINE_CHECK -> request(frame.type) {
            val req = json.decodeFromString(PipelineCheckRequest.serializer(), frame.data)
            store.check(req.projectId, req.action, req.scope, req.commit, req.what, who(req.sessionId))
        }
        else -> null
    }

    private fun who(sessionId: String?): String? = sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
        author(id) ?: throw IllegalArgumentException("session_not_found: no session '$id' on this daemon")
    }

    private inline fun request(type: String, block: () -> PipelineState): Frame = try {
        Frame(Envelope.PIPELINE_STATE, json.encodeToString(PipelineState.serializer(), block()))
    } catch (e: IllegalArgumentException) {
        Frame(Envelope.ERROR, "$type refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Frame(Envelope.ERROR, "$type failed: ${e.message ?: e::class.simpleName}")
    }
}
