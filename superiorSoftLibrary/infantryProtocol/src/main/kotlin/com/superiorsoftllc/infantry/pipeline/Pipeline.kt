// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.pipeline

import kotlinx.serialization.Serializable

/**
 * PIPE-1 (additive; contract version not bumped): a project's OPTIONAL verification pipeline. Stage names are data the project chooses
 * (for example merged, testing, passed, failed, promoted); nothing here knows any branch, gate or batching policy.
 * A project with no pipeline configured has [PipelineState.configured] false and nothing else.
 */
@Serializable
data class PipelineConfig(
    val stages: List<String>,
    /** When true, one running check per [PipelineCheck.scope] (worktree / build dir): a second start is refused. */
    val exclusiveCheck: Boolean = false,
)

/** One stage change of a commit. [evidence] is free text the reporter chose (suite, counts, log path). */
@Serializable
data class PipelineEntry(
    val commit: String,
    val stage: String,
    val movedBy: String? = null,
    val movedAt: Long,
    val evidence: String? = null,
)

/** A running check: [commit] is being verified by [what] in [scope]; [lastOutputAt] is the last sign of life. */
@Serializable
data class PipelineCheck(
    val scope: String,
    val commit: String,
    val what: String,
    val startedBy: String? = null,
    val startedAt: Long,
    val lastOutputAt: Long,
)

/** The whole answer: [commits] holds each commit's CURRENT entry, newest move first; [history] every entry, newest first. */
@Serializable
data class PipelineState(
    val projectId: String,
    val configured: Boolean,
    val config: PipelineConfig? = null,
    val commits: List<PipelineEntry> = emptyList(),
    val history: List<PipelineEntry> = emptyList(),
    val checks: List<PipelineCheck> = emptyList(),
)

/** `pipeline_config`: set the stages ([stages] non-empty), or remove the pipeline with [clear]. */
@Serializable
data class PipelineConfigure(
    val projectId: String,
    val stages: List<String>? = null,
    val exclusiveCheck: Boolean = false,
    val clear: Boolean = false,
)

/** `pipeline_show`. */
@Serializable
data class PipelineShow(val projectId: String)

/** `pipeline_record`: move [commit] to [stage]; [sessionId] declares who. */
@Serializable
data class PipelineRecord(
    val projectId: String,
    val commit: String,
    val stage: String,
    val evidence: String? = null,
    val sessionId: String? = null,
)

/** `pipeline_check`: [action] is `start` (needs [commit] and [what]), `ping` (output seen) or `finish`. */
@Serializable
data class PipelineCheckRequest(
    val projectId: String,
    val action: String,
    val scope: String = "",
    val commit: String? = null,
    val what: String? = null,
    val sessionId: String? = null,
)

/** A refusal; the wire text is `<frame> refused: <code>: <detail>`. */
class PipelineRefusal(val code: String, detail: String) : IllegalArgumentException("$code: $detail") {
    companion object {
        const val NO_PIPELINE = "no_pipeline"
        const val UNKNOWN_STAGE = "unknown_stage"
        const val CHECK_RUNNING = "check_running"
        const val NO_CHECK = "no_check"
    }
}
