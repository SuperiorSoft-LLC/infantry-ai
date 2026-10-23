package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.PipelineCheckView
import com.superiorsoftllc.agenthq.ui.protocol.PipelineEntryView
import com.superiorsoftllc.agenthq.ui.protocol.PipelineStateView

/** What the Pipeline section shows for a project (PIPE-2). Pure so it is tested without a screen. */
sealed interface PipelineSectionView {
    /** The daemon has not answered yet. */
    data object Loading : PipelineSectionView

    /** The default: no pipeline is configured for the project. */
    data object NotConfigured : PipelineSectionView

    data class Configured(
        val stages: List<String>,
        val commits: List<PipelineEntryView>,
        val checks: List<PipelineCheckView>,
    ) : PipelineSectionView
}

fun pipelineSectionView(state: PipelineStateView?): PipelineSectionView = when {
    state == null -> PipelineSectionView.Loading
    !state.configured -> PipelineSectionView.NotConfigured
    else -> PipelineSectionView.Configured(state.config?.stages.orEmpty(), state.commits, state.checks)
}

/** The 8-character form of a commit id; shorter ids are kept whole. */
fun shortCommit(commit: String): String = commit.take(8)

/** How long a check has been silent: the sign of life is [PipelineCheckView.lastOutputAt], not its start. */
fun PipelineCheckView.silentMs(now: Long): Long = (now - lastOutputAt).coerceAtLeast(0)
