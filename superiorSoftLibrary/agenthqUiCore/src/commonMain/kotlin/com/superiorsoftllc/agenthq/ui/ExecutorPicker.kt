package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.ExecutorPickerModel
import com.superiorsoftllc.agenthq.ui.model.ProviderOption

const val EXECUTOR_LOADING_TAG = "executor-loading"
const val EXECUTOR_MODEL_LABEL_TAG = "executor-model-label"
const val EXECUTOR_NOTE_TAG = "executor-note"
const val EXECUTOR_PROVIDER_LABEL_TAG = "executor-provider-label"
const val EXECUTOR_MODEL_MISSING_TAG = "executor-model-missing"
/** DEMO-RESTRICT: the reason line of an unavailable harness, `executor-unavailable-<harness>`. */
const val EXECUTOR_UNAVAILABLE_TAG_PREFIX = "executor-unavailable-"

/**
 * The test tags of one [ExecutorPicker]. The defaults are the doc's `executor-*` names (section 7, rule 5); a host
 * that already published its own tags (the New agent modal, the role binding editor) passes them so its tests and
 * any automation keep working unchanged.
 */
data class ExecutorPickerTags(
    val harness: (String) -> String = { "executor-harness-$it" },
    val providerSearch: String = "executor-provider-search",
    val providerAll: String = "executor-provider-all",
    val provider: (String) -> String = { "executor-provider-$it" },
    val modelSearch: String = "executor-model-search",
    val model: (harness: String, id: String) -> String = { _, id -> "executor-model-$id" },
    val freeText: String = "executor-model-freetext",
    val daemonDefault: String = "executor-harness-default",
)

/**
 * ROLE-FIRST S7: Harness -> Provider -> Model (docs/AGENTHQ-ROLE-FIRST.md section 7). Every rule lives in
 * [ExecutorPickerModel]; this only renders it and hands each choice back through [onChange]:
 *  - Harness: a chip per `providers` row (a short list, so chips, not a search). [onDaemonDefault], when given,
 *    adds a "daemon default" chip that un-pins the harness (the role binding editor needs it; the modal does not).
 *  - Provider: a [SearchableSelect] with "All (n)" first; just a label when the harness has one vendor.
 *  - Model: a [SearchableSelect] that is always searchable; picking one on All sets the provider (reverse arrow).
 *    Backspace on its empty search box goes back to All.
 *  - A harness that lists no models keeps a free-text field with its default, plus the daemon's note saying why.
 *  - No harnesses yet: one loading line, never an empty control.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExecutorPicker(
    model: ExecutorPickerModel,
    onChange: (ExecutorPickerModel) -> Unit,
    modifier: Modifier = Modifier,
    tags: ExecutorPickerTags = ExecutorPickerTags(),
    onDaemonDefault: (() -> Unit)? = null,
    /** Text color of unselected rows and labels; defaults to the dark panels' [Palette.Text]. */
    textColor: Color = Palette.Text,
    /** TICKET-EXEC-UI: the [onDaemonDefault] chip's text ("inherit" on a ticket, whose harness inherits a profile's). */
    daemonDefaultLabel: String = "daemon default",
    /** TICKET-EXEC-UI: the line shown while no harness is chosen. */
    noHarnessText: String = "No harness chosen: the daemon's default harness runs this agent.",
) {
    val ex = model
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ex.harnesses.isEmpty()) {
            Text(
                "Loading harnesses... (the daemon's defaults apply if none load)", color = Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.testTag(EXECUTOR_LOADING_TAG),
            )
            return@Column
        }
        SectionLabel("Harness", textColor)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            onDaemonDefault?.let { clear ->
                Chip(
                    daemonDefaultLabel, if (ex.harness == null) Palette.Accent else Palette.Muted,
                    Modifier.testTag(tags.daemonDefault).clickable { clear() },
                )
            }
            ex.harnesses.forEach { h ->
                val why = ex.unavailableReason(h.id)
                if (why == null) {
                    HarnessChip(
                        h.id, if (ex.harness == h.id) Palette.Accent else Palette.Muted,
                        Modifier.testTag(tags.harness(h.id)).clickable { onChange(ex.withHarness(h.id)) },
                    )
                } else {
                    // DEMO-RESTRICT: dimmed, not clickable, the reason on hover and below the chips.
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("${h.id} unavailable: $why", fontSize = 11.sp) } },
                        state = rememberTooltipState(isPersistent = true),
                    ) {
                        HarnessChip(
                            h.id, (if (ex.harness == h.id) Palette.Accent else Palette.Muted).copy(alpha = 0.35f),
                            Modifier.testTag(tags.harness(h.id)).semantics {
                                disabled()
                                contentDescription = "${h.id} unavailable: $why"
                            },
                        )
                    }
                }
            }
        }
        ex.harnesses.mapNotNull { h -> ex.unavailableReason(h.id)?.let { h.id to it } }.forEach { (id, why) ->
            // One line each (a CLI-not-found reason lists every path tried; the tooltip keeps the whole text).
            Text(
                "$id unavailable: ${why.lineSequence().first().trim()}", color = Palette.Muted.copy(alpha = 0.7f), fontSize = 10.sp,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.testTag(EXECUTOR_UNAVAILABLE_TAG_PREFIX + id),
            )
        }
        val harness = ex.harness
        if (harness == null) {
            Text(noHarnessText, color = Palette.Muted, fontSize = 11.sp)
            return@Column
        }
        ex.notes.forEach { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(EXECUTOR_NOTE_TAG)) }
        if (ex.freeText) {
            OutlinedTextField(
                ex.model.orEmpty(), { onChange(ex.withModel(it)) }, singleLine = true,
                placeholder = { Text(ex.freeTextDefault?.let { "Model (default: $it)" } ?: "Model (harness default)") },
                modifier = Modifier.fillMaxWidth().testTag(tags.freeText), colors = visibleBorderFieldColors(),
            )
            return@Column
        }

        SectionLabel("Provider", textColor)
        if (ex.providerCollapsed) {
            Text(
                ex.providerOptions.firstOrNull()?.label.orEmpty(), fontSize = 12.sp, color = textColor,
                modifier = Modifier.testTag(EXECUTOR_PROVIDER_LABEL_TAG),
            )
        } else {
            val all = ex.withProviderQuery("").providerOptions
            // null is the "All" row; it only shows while nothing is typed, so a search never offers it as a hit.
            val rows: List<ProviderOption?> = (if (ex.providerQuery.isBlank()) listOf(null) else emptyList<ProviderOption?>()) + ex.providerOptions
            SearchableSelect(
                items = rows, query = ex.providerQuery, onQueryChange = { onChange(ex.withProviderQuery(it)) },
                onPick = { onChange(ex.withGroup(it?.group)) },
                key = { it?.group ?: "\u0000all" },
                label = { it?.let { p -> "${p.label} (${p.modelCount})" } ?: "All (${all.sumOf { p -> p.modelCount }})" },
                searchTag = tags.providerSearch,
                itemTag = { it?.let { p -> tags.provider(p.group) } ?: tags.providerAll },
                isSelected = { (it?.group) == ex.selectedGroup },
                placeholder = "Search providers...", totalCount = all.size + 1, maxListHeight = 110.dp, textColor = textColor,
            )
        }

        SearchableSelect(
            items = ex.modelOptions, query = ex.modelQuery, onQueryChange = { onChange(ex.withModelQuery(it)) },
            onPick = { onChange(ex.withModel(it.id, it.group)) },
            key = { it.group + "|" + it.id }, label = { it.id },
            searchTag = tags.modelSearch, itemTag = { tags.model(harness, it.id) },
            isSelected = { it.id == ex.model },
            badge = { o -> o.group.takeIf { it.isNotEmpty() && ex.selectedGroup == null } },
            placeholder = "Search models...", emptyText = "This provider lists no models.",
            totalCount = ex.withModelQuery("").modelOptions.size,
            onBackOut = if (!ex.providerCollapsed && ex.selectedGroup != null) ({ onChange(ex.withGroup(null)) }) else null,
            textColor = textColor,
            aboveList = {
                // PICKER-UX: a lone model is named as the only one on offer, so nobody hunts for another choice.
                val sole = ex.soleModel?.id
                SectionLabel(
                    when {
                        sole != null && ex.model == sole -> "Model: $sole (only one allowed in this deployment)"
                        ex.model != null -> "Model: ${ex.model}"
                        else -> "Model (none picked: harness default)"
                    },
                    textColor, Modifier.testTag(EXECUTOR_MODEL_LABEL_TAG),
                )
                if (ex.modelMissing) {
                    Text(
                        "\"${ex.model}\" is no longer in this harness's catalog; it is kept as stored until you pick another.",
                        color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(EXECUTOR_MODEL_MISSING_TAG),
                    )
                }
            },
        )
    }
}
