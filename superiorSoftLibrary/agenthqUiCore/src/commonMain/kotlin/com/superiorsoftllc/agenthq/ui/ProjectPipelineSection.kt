package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.PipelineSectionView
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.model.pipelineSectionView
import com.superiorsoftllc.agenthq.ui.model.shortCommit
import com.superiorsoftllc.agenthq.ui.model.silentMs
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.PIPELINE_UNSUPPORTED
import com.superiorsoftllc.agenthq.ui.protocol.PipelineCheckView
import com.superiorsoftllc.agenthq.ui.protocol.PipelineEntryView
import com.superiorsoftllc.agenthq.ui.protocol.parseStageNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

const val PIPELINE_CONFIGURE_TAG = "pipeline-configure"
const val PIPELINE_EMPTY_TAG = "pipeline-empty"
const val PIPELINE_COMMIT_TAG = "pipeline-commit-"
const val PIPELINE_CHIP_TAG = "pipeline-chip-"
const val PIPELINE_CHECK_TAG = "pipeline-check-"
const val PIPELINE_STAGES_FIELD_TAG = "pipeline-stages-field"
const val PIPELINE_SAVE_TAG = "pipeline-save"
const val PIPELINE_REMOVE_TAG = "pipeline-remove"

/**
 * PIPE-2 (contract 1.99.0): the project's optional verification pipeline. Per commit: a chip for its current stage,
 * the evidence the reporter gave, who moved it and when; above them, each running check with the age of its last output.
 * No pipeline is the default and is stated as such, with the way to configure one. The daemon pushes `pipeline_state`
 * on every change, so nothing here polls; only the ages tick.
 */
@Composable
fun ProjectPipelineSection(state: AppState, controller: Controller, projectId: String, live: Boolean) {
    val scope = rememberCoroutineScope()
    val supported = contractAtLeast(state.connection.contract, 1, 99)
    var editing by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = Clock.System.now().toEpochMilliseconds() } }
    LaunchedEffect(projectId, live, supported) { if (live && supported) controller.loadPipeline(projectId) }

    val view = pipelineSectionView(state.pipelines[projectId])
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("PIPELINE", color = Palette.Muted, fontSize = 10.sp)
        if (supported) OutlinedButton(
            onClick = { error = null; editing = true }, enabled = live, modifier = Modifier.testTag(PIPELINE_CONFIGURE_TAG),
        ) { Text(if (view is PipelineSectionView.Configured) "Stages…" else "Configure…", fontSize = 11.sp) }
    }
    when {
        !supported -> Text(PIPELINE_UNSUPPORTED, color = Palette.Muted, fontSize = 11.sp)
        view is PipelineSectionView.Loading -> Text("Loading pipeline…", color = Palette.Muted, fontSize = 11.sp)
        view is PipelineSectionView.NotConfigured -> Text(
            "No pipeline configured. Configure stages (for example merged, testing, passed, failed, promoted) to track each commit.",
            color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(PIPELINE_EMPTY_TAG),
        )
        view is PipelineSectionView.Configured -> {
            Text(view.stages.joinToString(" -> "), color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
            view.checks.forEach { CheckRow(it, now) }
            if (view.commits.isEmpty()) Text("No commit has been recorded yet.", color = Palette.Muted, fontSize = 11.sp)
            view.commits.forEach { CommitRow(it, view.stages, now) }
        }
    }
    error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }

    if (editing) {
        val current = (view as? PipelineSectionView.Configured)?.stages.orEmpty()
        StagesDialog(
            initial = current.joinToString(", "), configured = view is PipelineSectionView.Configured, live = live,
            onDismiss = { editing = false },
            onSave = { stages ->
                scope.launch {
                    val r = controller.configurePipeline(projectId, stages)
                    if (r is CommandResult.Refused) error = r.reason else editing = false
                }
            },
            onRemove = {
                scope.launch {
                    val r = controller.configurePipeline(projectId, null)
                    if (r is CommandResult.Refused) error = r.reason else editing = false
                }
            },
        )
    }
}

@Composable
private fun CheckRow(c: PipelineCheckView, now: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag(PIPELINE_CHECK_TAG + c.commit)) {
        Text("Running: ${c.what} on ${shortCommit(c.commit)}", color = Palette.Info, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val who = c.startedBy?.let { " · $it" }.orEmpty()
        Text(
            "started ${ago(c.startedAt, now)} ago$who · last output ${ago(now - c.silentMs(now), now)} ago",
            color = Palette.Muted, fontSize = 10.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommitRow(e: PipelineEntryView, stages: List<String>, now: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag(PIPELINE_COMMIT_TAG + e.commit)) {
        // FlowRow so the hash, chip and age wrap on a narrow (mobile) width instead of clipping.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shortCommit(e.commit), color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp)
            Text(
                e.stage, color = stageColor(e.stage), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(PIPELINE_CHIP_TAG + e.commit),
            )
            if (e.stage !in stages) Text("(not a configured stage)", color = Palette.Muted, fontSize = 10.sp)
            Text(listOfNotNull(e.movedBy, "${ago(e.movedAt, now)} ago").joinToString(" · "), color = Palette.Muted, fontSize = 10.sp)
        }
        e.evidence?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Text, fontFamily = Mono, fontSize = 10.sp) }
    }
}

/** Failed reads red, passed/promoted green, testing as in-flight; any other stage name is plain. */
private fun stageColor(stage: String): Color = when (stage.lowercase()) {
    "failed", "fail", "red" -> Palette.Danger
    "passed", "pass", "promoted", "green", "done" -> Palette.Ok
    "testing", "running", "checking" -> Palette.Info
    else -> Palette.Accent
}

@Composable
private fun StagesDialog(
    initial: String, configured: Boolean, live: Boolean,
    onDismiss: () -> Unit, onSave: (List<String>) -> Unit, onRemove: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val stages = parseStageNames(text)
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pipeline stages", fontSize = 15.sp) },
        text = {
            Column {
                Text("Stage names in order, separated by commas.", color = Palette.Muted, fontSize = 11.sp)
                OutlinedTextField(
                    text, { text = it }, label = { Text("merged, testing, passed, failed, promoted") }, enabled = live,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(PIPELINE_STAGES_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(stages) }, enabled = live && stages.isNotEmpty(), modifier = Modifier.testTag(PIPELINE_SAVE_TAG)) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (configured) TextButton(onClick = onRemove, enabled = live, modifier = Modifier.testTag(PIPELINE_REMOVE_TAG)) { Text("Remove pipeline", color = Palette.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
