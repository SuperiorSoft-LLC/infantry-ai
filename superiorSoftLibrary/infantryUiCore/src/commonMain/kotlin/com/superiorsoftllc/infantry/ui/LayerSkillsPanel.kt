package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.layerSkillKey
import kotlinx.coroutines.launch

const val LAYER_SKILLS_TAG = "layer-skills"

/**
 * SKILLS-GLOBAL-1b: the skills of ONE layer. [scope] is `global`, `project` (needs [projectId]) or `role` (needs both and
 * [role]). Lists the layer's skills with Edit / Delete and an inline form to add one (saving an existing name replaces it).
 * [roleNotes], when given, is shown read-only above the list: a role's notes are its existing role layer.
 * [showCutoff] adds the inline-length setting (`limits.skills.inlineMaxChars`).
 */
@Composable
fun LayerSkillsPanel(
    state: AppState, controller: Controller, scope: String, projectId: String? = null, role: String? = null,
    canWrite: Boolean = true, roleNotes: String? = null, showCutoff: Boolean = false,
) {
    val co = rememberCoroutineScope()
    val key = layerSkillKey(scope, projectId, role)
    val skills = state.layerSkills[key]
    var name by remember(key) { mutableStateOf("") }
    var description by remember(key) { mutableStateOf("") }
    var body by remember(key) { mutableStateOf("") }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var cutoff by remember { mutableStateOf("") }
    LaunchedEffect(key, state.connection.isLive) { if (state.connection.isLive) controller.loadLayerSkills(scope, projectId, role) }

    fun run(request: suspend () -> CommandResult, onSent: () -> Unit = {}) {
        error = null
        co.launch { when (val r = request()) { is CommandResult.Refused -> error = r.reason; is CommandResult.Sent -> onSent(); else -> Unit } }
    }

    Column(Modifier.fillMaxWidth().testTag("$LAYER_SKILLS_TAG-$scope"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${scope.uppercase()} SKILLS", color = Palette.Muted, fontSize = 10.sp)
        if (!roleNotes.isNullOrBlank()) {
            Text("Role notes (role layer)", color = Palette.Muted, fontSize = 11.sp)
            Text(roleNotes.take(600) + if (roleNotes.length > 600) "…" else "", color = Palette.Text, fontSize = 11.sp)
        }
        when {
            skills == null -> Text("Loading…", color = Palette.Muted, fontSize = 12.sp)
            skills.isEmpty() -> Text("No $scope skills yet.", color = Palette.Muted, fontSize = 12.sp)
            else -> skills.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { name = s.name; description = s.description; body = s.body }) {
                        Text(s.name, color = Palette.Text, fontSize = 12.sp)
                        if (s.description.isNotBlank()) Text(s.description, color = Palette.Muted, fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = { run({ controller.deleteLayerSkill(scope, projectId, role, s.name) }) }, enabled = canWrite,
                        modifier = Modifier.testTag("$LAYER_SKILLS_TAG-delete-${s.name}"),
                    ) { Text("Delete", color = Palette.Danger, fontSize = 11.sp) }
                }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = canWrite, modifier = Modifier.fillMaxWidth().testTag("$LAYER_SKILLS_TAG-name"))
        OutlinedTextField(description, { description = it }, label = { Text("Description (one line)") }, singleLine = true, enabled = canWrite, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Body") }, minLines = 3, maxLines = 8, enabled = canWrite, modifier = Modifier.fillMaxWidth().testTag("$LAYER_SKILLS_TAG-body"))
        Button(
            onClick = { run({ controller.saveLayerSkill(scope, projectId, role, name, description, body) }) { name = ""; description = ""; body = "" } },
            enabled = canWrite && name.isNotBlank() && body.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            modifier = Modifier.testTag("$LAYER_SKILLS_TAG-save"),
        ) { Text("Save skill") }
        if (showCutoff) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(
                    cutoff, { cutoff = it.filter(Char::isDigit) }, singleLine = true, enabled = canWrite,
                    label = { Text("Inline up to N characters (default 1500)") }, modifier = Modifier.weight(1f).testTag("$LAYER_SKILLS_TAG-cutoff"),
                )
                Button(onClick = { cutoff.toIntOrNull()?.let { n -> run({ controller.setSkillInlineMax(n) }) } }, enabled = canWrite && cutoff.isNotEmpty()) { Text("Set") }
            }
        }
        error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag("$LAYER_SKILLS_TAG-error")) }
    }
}
