// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.prompts

import com.superiorsoftllc.infantry.ui.shell.gate
import com.superiorsoftllc.infantry.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.filterPrompts
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.PromptsContext
import kotlinx.coroutines.launch

const val PROMPTS_ADD_BUTTON_TAG = "prompts-add"
const val PROMPT_DIALOG_TAG = "prompt-dialog"
const val PROMPT_NAME_TAG = "prompt-name-input"
const val PROMPT_BODY_TAG = "prompt-body-input"
const val PROMPT_TAGS_TAG = "prompt-tags-input"
const val PROMPT_SAVE_TAG = "prompt-save"
const val PROMPT_CANCEL_TAG = "prompt-cancel"

/** FORM-MODAL-1: what the prompt modal holds. [editing] is the fixed name being edited (a rename would save a second prompt), or null for a new one. */
private data class PromptDraft(val editing: String? = null, val name: String = "", val body: String = "", val tags: String = "")

/**
 * P10-C: the Prompts tab (contract 1.44.0). A header row with an icon-only "Add prompt" button, the filter (by name or
 * tag), then the saved prompts with Insert (hands the body to [onInsert], the agent prompt box), Edit and Delete.
 * FORM-MODAL-1: create and edit happen in the shared [EditorModal], never in a form under the growing list: "+" opens
 * it empty, Edit (or a click on the card) opens it pre-filled with the name fixed, since `prompt_save` updates by name.
 * Save closes on Sent; a refusal stays in the modal. States: loading, empty, and a daemon refusal (`ctx.error`).
 */
@Composable
fun PromptsPane(link: LinkContext, ctx: PromptsContext, onInsert: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val live = link.live
    // POLICY-1: write controls are named actions gated by the daemon's policy (and any write policy); reads keep live.
    val canWrite = link.gate("prompts.save").enabled
    val canDelete = link.gate("prompts.delete", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE).enabled
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<PromptDraft?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(live) { if (live) ctx.load() }

    fun run(onSent: () -> Unit = {}, request: suspend () -> CommandResult) {
        localError = null
        scope.launch {
            when (val r = request()) {
                is CommandResult.Sent -> onSent()
                is CommandResult.Refused -> localError = r.reason
                is CommandResult.Queued -> Unit
            }
        }
    }

    fun openDraft(d: PromptDraft) {
        localError = null
        draft = d
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PROMPTS", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                IconAction(
                    AhqIcons.Add, "Add prompt", onClick = { openDraft(PromptDraft()) }, enabled = canWrite,
                    style = IconActionStyle.TEXT, modifier = Modifier.testTag(PROMPTS_ADD_BUTTON_TAG),
                )
            }
            OutlinedTextField(
                query, { query = it }, label = { Text("Filter by name or tag") }, singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag("prompts-search-input"),
            )
            // While the modal is open its refusals show inside it; otherwise (a delete refused) here.
            (if (draft == null) localError ?: ctx.error else null)?.let {
                Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag("prompts-error"))
            }

            val shown = filterPrompts(ctx.all, query)
            Column(Modifier.padding(top = 8.dp)) {
                when {
                    ctx.loading && ctx.all.isEmpty() ->
                        Text("Loading prompts...", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag("prompts-loading"))
                    ctx.all.isEmpty() ->
                        Text("No saved prompts yet.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag("prompts-empty"))
                    shown.isEmpty() ->
                        Text("No prompt matches “${query.trim()}”.", color = Palette.Muted, fontSize = 12.sp)
                }
                shown.forEach { p ->
                    val edit = { openDraft(PromptDraft(editing = p.name, name = p.name, body = p.body, tags = p.tags.joinToString(", "))) }
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel)
                            .clickable(enabled = canWrite, onClickLabel = "Edit prompt", role = Role.Button) { edit() }
                            .padding(10.dp),
                    ) {
                        Text(p.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(p.body, color = Palette.Muted, fontSize = 11.sp, maxLines = 3)
                        if (p.tags.isNotEmpty()) {
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                p.tags.forEach { t -> Chip(t, Palette.Info) }
                            }
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onInsert(p.body) }, modifier = Modifier.testTag("prompt-insert-${p.name}")) { Text("Insert", fontSize = 11.sp) }
                            OutlinedButton(onClick = edit, enabled = canWrite, modifier = Modifier.testTag("prompt-edit-${p.name}")) { Text("Edit", fontSize = 11.sp) }
                            OutlinedButton(
                                onClick = { run { ctx.delete(p.id) } }, enabled = canDelete,
                                modifier = Modifier.testTag("prompt-delete-${p.name}"),
                            ) { Text("Delete", fontSize = 11.sp, color = Palette.Danger) }
                        }
                    }
                }
            }
        }

        draft?.let { d ->
            EditorModal(
                title = if (d.editing == null) "Add prompt" else "Edit prompt",
                onSave = { run(onSent = { draft = null }) { ctx.save(d.name, d.body, d.tags) } },
                onCancel = { draft = null; localError = null },
                modifier = Modifier.testTag(PROMPT_DIALOG_TAG),
                saveEnabled = canWrite && d.name.isNotBlank() && d.body.isNotBlank(),
                error = localError ?: ctx.error,
                saveTag = PROMPT_SAVE_TAG, cancelTag = PROMPT_CANCEL_TAG,
            ) {
                OutlinedTextField(
                    d.name, { draft = d.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = canWrite && d.editing == null,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(PROMPT_NAME_TAG),
                )
                OutlinedTextField(
                    d.body, { draft = d.copy(body = it) }, label = { Text("Prompt body") }, minLines = 3, maxLines = 12, enabled = canWrite,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(PROMPT_BODY_TAG),
                )
                OutlinedTextField(
                    d.tags, { draft = d.copy(tags = it) }, label = { Text("Tags (comma separated)") }, singleLine = true, enabled = canWrite,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(PROMPT_TAGS_TAG),
                )
            }
        }
    }
}
