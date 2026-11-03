// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.skills

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
import com.superiorsoftllc.infantry.ui.model.filterSkills
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.SkillsContext
import kotlinx.coroutines.launch

const val SKILLS_ADD_BUTTON_TAG = "skills-add"
const val SKILL_DIALOG_TAG = "skill-dialog"
const val SKILL_NAME_TAG = "skill-name-input"
const val SKILL_DESCRIPTION_TAG = "skill-description-input"
const val SKILL_BODY_TAG = "skill-body-input"
const val SKILL_TAGS_TAG = "skill-tags-input"
const val SKILL_SAVE_TAG = "skill-save"
const val SKILL_CANCEL_TAG = "skill-cancel"

/** FORM-MODAL-1: what the skill modal holds. [editing] is the fixed name being edited (a rename would save a second skill), or null for a new one. */
private data class SkillDraft(
    val editing: String? = null,
    val name: String = "",
    val description: String = "",
    val body: String = "",
    val tags: String = "",
)

/**
 * P10-D: the Skills tab (contract 1.45.0). A header row with an icon-only "Add skill" button, the filter (by name,
 * description or tag), then the saved skills with Insert (hands the body to [onInsert], the agent prompt box), Edit
 * and Delete. FORM-MODAL-1: create and edit happen in the shared [EditorModal], never in a form under the growing
 * list: "+" opens it empty, Edit (or a click on the card) opens it pre-filled with the name fixed, since `skill_save`
 * updates by name. Save closes on Sent; a refusal stays in the modal. States: loading, empty, and a daemon refusal
 * (`ctx.error`).
 */
@Composable
fun SkillsPane(link: LinkContext, ctx: SkillsContext, onInsert: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val live = link.live
    // POLICY-1: write controls are named actions gated by the daemon's policy (and any write policy); reads keep live.
    val canWrite = link.gate("skills.save").enabled
    val canDelete = link.gate("skills.delete", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE).enabled
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<SkillDraft?>(null) }
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

    fun openDraft(d: SkillDraft) {
        localError = null
        draft = d
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SKILLS", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                IconAction(
                    AhqIcons.Add, "Add skill", onClick = { openDraft(SkillDraft()) }, enabled = canWrite,
                    style = IconActionStyle.TEXT, modifier = Modifier.testTag(SKILLS_ADD_BUTTON_TAG),
                )
            }
            OutlinedTextField(
                query, { query = it }, label = { Text("Filter by name, description or tag") }, singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag("skills-search-input"),
            )
            // While the modal is open its refusals show inside it; otherwise (a delete refused) here.
            (if (draft == null) localError ?: ctx.error else null)?.let {
                Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag("skills-error"))
            }

            // SKILLS-GLOBAL-1b: the global layer and its inline-length setting (project and role layers: Project Settings).
            val appState = ctx.appState
            val appController = ctx.appController
            if (appState != null && appController != null) {
                Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                    LayerSkillsPanel(appState, appController, "global", canWrite = canWrite, showCutoff = true)
                }
            }
            val shown = filterSkills(ctx.all, query)
            Column(Modifier.padding(top = 8.dp)) {
                when {
                    ctx.loading && ctx.all.isEmpty() ->
                        Text("Loading skills...", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag("skills-loading"))
                    ctx.all.isEmpty() ->
                        Text("No saved skills yet.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag("skills-empty"))
                    shown.isEmpty() ->
                        Text("No skill matches “${query.trim()}”.", color = Palette.Muted, fontSize = 12.sp)
                }
                shown.forEach { p ->
                    val edit = {
                        openDraft(SkillDraft(editing = p.name, name = p.name, description = p.description, body = p.body, tags = p.tags.joinToString(", ")))
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel)
                            .clickable(enabled = canWrite, onClickLabel = "Edit skill", role = Role.Button) { edit() }
                            .padding(10.dp),
                    ) {
                        Text(p.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (p.description.isNotBlank()) Text(p.description, color = Palette.Text, fontSize = 11.sp, maxLines = 2)
                        Text(p.body, color = Palette.Muted, fontSize = 11.sp, maxLines = 3)
                        if (p.tags.isNotEmpty()) {
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                p.tags.forEach { t -> Chip(t, Palette.Info) }
                            }
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onInsert(p.body) }, modifier = Modifier.testTag("skill-insert-${p.name}")) { Text("Insert", fontSize = 11.sp) }
                            OutlinedButton(onClick = edit, enabled = canWrite, modifier = Modifier.testTag("skill-edit-${p.name}")) { Text("Edit", fontSize = 11.sp) }
                            OutlinedButton(
                                onClick = { run { ctx.delete(p.id) } }, enabled = canDelete,
                                modifier = Modifier.testTag("skill-delete-${p.name}"),
                            ) { Text("Delete", fontSize = 11.sp, color = Palette.Danger) }
                        }
                    }
                }
            }
        }

        draft?.let { d ->
            EditorModal(
                title = if (d.editing == null) "Add skill" else "Edit skill",
                onSave = { run(onSent = { draft = null }) { ctx.save(d.name, d.description, d.body, d.tags) } },
                onCancel = { draft = null; localError = null },
                modifier = Modifier.testTag(SKILL_DIALOG_TAG),
                saveEnabled = canWrite && d.name.isNotBlank() && d.body.isNotBlank(),
                error = localError ?: ctx.error,
                saveTag = SKILL_SAVE_TAG, cancelTag = SKILL_CANCEL_TAG,
            ) {
                OutlinedTextField(
                    d.name, { draft = d.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = canWrite && d.editing == null,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(SKILL_NAME_TAG),
                )
                OutlinedTextField(
                    d.description, { draft = d.copy(description = it) }, label = { Text("Description (optional, max 500)") }, singleLine = true,
                    enabled = canWrite, textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(SKILL_DESCRIPTION_TAG),
                )
                OutlinedTextField(
                    d.body, { draft = d.copy(body = it) }, label = { Text("Skill body") }, minLines = 3, maxLines = 12, enabled = canWrite,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(SKILL_BODY_TAG),
                )
                OutlinedTextField(
                    d.tags, { draft = d.copy(tags = it) }, label = { Text("Tags (comma separated)") }, singleLine = true, enabled = canWrite,
                    textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().testTag(SKILL_TAGS_TAG),
                )
            }
        }
    }
}
