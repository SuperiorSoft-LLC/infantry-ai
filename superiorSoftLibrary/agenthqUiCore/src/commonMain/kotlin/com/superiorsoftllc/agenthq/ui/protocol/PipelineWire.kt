package com.superiorsoftllc.agenthq.ui.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.99.0 (PIPE-2): a project's OPTIONAL verification pipeline, as the daemon's `pipeline_state` frame carries it.
 * Stage names are data the project chose; the UI draws them and knows no policy. A project with no pipeline is
 * [PipelineStateView.configured] false and nothing else (the default).
 */
@Serializable
data class PipelineConfigView(val stages: List<String> = emptyList(), val exclusiveCheck: Boolean = false)

/** One commit's stage entry; [evidence] is free text the reporter chose (suite, counts, a log path or link). */
@Serializable
data class PipelineEntryView(
    val commit: String,
    val stage: String,
    val movedBy: String? = null,
    val movedAt: Long,
    val evidence: String? = null,
)

/** A running check: [lastOutputAt] is its last sign of life. */
@Serializable
data class PipelineCheckView(
    val scope: String = "",
    val commit: String,
    val what: String,
    val startedBy: String? = null,
    val startedAt: Long,
    val lastOutputAt: Long,
)

@Serializable
data class PipelineStateView(
    val projectId: String,
    val configured: Boolean = false,
    val config: PipelineConfigView? = null,
    val commits: List<PipelineEntryView> = emptyList(),
    val history: List<PipelineEntryView> = emptyList(),
    val checks: List<PipelineCheckView> = emptyList(),
)

@Serializable
internal data class PipelineShowRequest(val projectId: String)

@Serializable
internal data class PipelineConfigureRequest(
    val projectId: String,
    val stages: List<String>? = null,
    val exclusiveCheck: Boolean = false,
    val clear: Boolean = false,
)

/** Stage names from a comma/newline separated box: trimmed, blanks and repeats dropped, order kept. */
fun parseStageNames(text: String): List<String> =
    text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
