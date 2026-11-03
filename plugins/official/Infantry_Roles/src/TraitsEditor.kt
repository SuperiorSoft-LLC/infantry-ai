// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles

import com.superiorsoftllc.infantry.ui.shell.gate
import com.superiorsoftllc.infantry.ui.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.TraitDraft
import com.superiorsoftllc.infantry.ui.model.bindTrait
import com.superiorsoftllc.infantry.ui.model.moveTrait
import com.superiorsoftllc.infantry.ui.model.unbindTrait
import com.superiorsoftllc.infantry.ui.shell.TraitsContext
import com.superiorsoftllc.infantry.ui.protocol.Role
import kotlinx.coroutines.launch

const val ROLE_TRAIT_TAG_PREFIX = "role-trait:"
const val ROLE_TRAIT_BIND_TAG_PREFIX = "role-trait-bind:"
const val TRAIT_EDITOR_SAVE_TAG = "trait-editor-save"

/**
 * TRAITS-T8: the role's ordered trait list (`role_traits_set`) and the trait library editor (`trait_save` /
 * `trait_delete`). Thin by design: every decision lives in TraitsModel.kt, every action goes through the shell's [TraitsContext] (S4c),
 * and what is shown changes only when the daemon's push arrives.
 */
@Composable
internal fun RoleTraitsEditor(role: Role, deps: RolesDeps) {
    val traits = deps.traits
    val scope = rememberCoroutineScope()
    val live = deps.link.live
    // POLICY-1: write controls are named actions gated by the daemon's policy (and any write policy); reads keep live.
    val canWrite = deps.link.gate("roles.save").enabled
    val bound = traits.idsFor(role.id)
    fun set(ids: List<String>) { scope.launch { traits.setForRole(role.id, ids) } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Traits of this role (${bound.size}/8), rendered in this order into every session", color = Palette.Muted, fontSize = 10.sp)
        if (bound.isEmpty()) Text("No trait bound.", color = Palette.Muted, fontSize = 11.sp)
        bound.forEachIndexed { i, id ->
            val t = traits.all.firstOrNull { it.id == id }
            Row(Modifier.fillMaxWidth().testTag(ROLE_TRAIT_TAG_PREFIX + id), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", color = Palette.Muted, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(t?.name ?: id, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                if (t?.check != null) { Chip("report", Palette.Info); Spacer(Modifier.width(4.dp)) }
                TextButton(onClick = { set(moveTrait(bound, id, -1)) }, enabled = canWrite && i > 0) { Text("Up", fontSize = 11.sp) }
                TextButton(onClick = { set(moveTrait(bound, id, +1)) }, enabled = canWrite && i < bound.lastIndex) { Text("Down", fontSize = 11.sp) }
                TextButton(onClick = { set(unbindTrait(bound, id)) }, enabled = canWrite) { Text("Unbind", fontSize = 11.sp) }
            }
        }
        val unbound = traits.all.filterNot { it.id in bound }
        if (unbound.isNotEmpty() && bound.size < 8) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bind:", color = Palette.Muted, fontSize = 11.sp)
                unbound.forEach { t ->
                    TextButton(onClick = { set(bindTrait(bound, t.id)) }, enabled = canWrite, modifier = Modifier.testTag(ROLE_TRAIT_BIND_TAG_PREFIX + t.id)) {
                        Text("+ ${t.name}", fontSize = 11.sp)
                    }
                }
            }
        }
        traits.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
        TraitLibrary(deps.link.live, traits)
    }
}

@Composable
private fun TraitLibrary(live: Boolean, traits: TraitsContext) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<TraitDraft?>(null) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide trait library" else "Trait library (${traits.all.size})", fontSize = 12.sp) }
    if (!open) return
    if (traits.loading) Text("Loading…", color = Palette.Muted, fontSize = 11.sp)
    if (traits.all.isEmpty() && !traits.loading) Text("No trait yet (or the daemon predates traits).", color = Palette.Muted, fontSize = 11.sp)
    traits.all.forEach { t ->
        val users = traits.rolesBinding(t.id)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${t.name}  v${t.version}", color = Palette.Text, fontSize = 12.sp, maxLines = 1)
                Text(t.id + if (users.isEmpty()) "" else "  ·  bound to ${users.joinToString()}", color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
            }
            TextButton(onClick = { draft = TraitDraft.of(t) }, enabled = live) { Text("Edit", fontSize = 11.sp) }
            // The daemon refuses a delete while a role binds the trait; say why instead of offering it.
            TextButton(onClick = { scope.launch { traits.delete(t.id) } }, enabled = live && users.isEmpty()) { Text("Delete", fontSize = 11.sp) }
        }
    }
    val d = draft
    if (d == null) {
        OutlinedButton(onClick = { draft = TraitDraft() }, enabled = live) { Text("+ New trait") }
    } else {
        TraitForm(d, onChange = { draft = it }, onCancel = { draft = null }, onSave = {
            scope.launch { traits.save(d); draft = null }
        })
    }
}

@Composable
private fun TraitForm(d: TraitDraft, onChange: (TraitDraft) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(d.id, { onChange(d.copy(id = it)) }, label = { Text("Id (slug)") }, enabled = d.isNew, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.name, { onChange(d.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.body, { onChange(d.copy(body = it)) }, label = { Text("Rule text (≤ 2 KB, rendered in full into every session)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(d.checkOn, { onChange(d.copy(checkOn = it)) })
            Text("Require a report (REPORT BACK check)", color = Palette.Text, fontSize = 12.sp)
        }
        if (d.checkOn) {
            OutlinedTextField(d.reportTo, { onChange(d.copy(reportTo = it)) }, label = { Text("Report to (role id)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(d.fieldsText, { onChange(d.copy(fieldsText = it)) }, label = { Text("Required fields, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(d.reprompt, { onChange(d.copy(reprompt = it)) })
                Text("Re-prompt once before raising attention", color = Palette.Text, fontSize = 12.sp)
            }
        }
        val problem = d.problem()
        problem?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onSave, enabled = problem == null, modifier = Modifier.testTag(TRAIT_EDITOR_SAVE_TAG)) { Text(if (d.isNew) "Create trait" else "Save trait") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
