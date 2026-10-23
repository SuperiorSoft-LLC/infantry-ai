package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import kotlinx.coroutines.launch

/**
 * Agent profiles as one flat list (contract 1.4.0 `agents`), outside the Roles tab: every profile with its role,
 * provider and model, editable in place (a save with the profile's id overwrites it), deletable, and startable.
 * Roles stay the place to browse a role's persona; this is where you manage the agents a ticket can be given to.
 *
 * The harness + model control is [ProfileExecutorField] (shared with the Roles pane's profile editor).
 */

@Composable
internal fun ProfilesPane(state: AppState, controller: Controller, onSessionStarted: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.visibleProfiles.size} agent profiles", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Button(onClick = { creating = !creating }, enabled = state.connection.isLive) { Text(if (creating) "Close" else "+ New profile", fontSize = 12.sp) }
        }
        if (creating) ProfileForm(null, state, controller, onDone = { creating = false })
        if (state.visibleProfiles.isEmpty() && !creating) {
            Text(if (state.connection.isLive) "No agent profile yet." else "Connect to a daemon to load its profiles.", color = Palette.Text)
            Text("A profile binds a role to a provider and model plus standing instructions. Tickets are assigned to a profile or to a role.", color = Palette.Muted, fontSize = 11.sp)
        }
        state.visibleProfiles.forEach { p -> ProfileRow(p, state, controller, onSessionStarted) }
    }
}

@Composable
private fun ProfileRow(p: AgentProfile, state: AppState, controller: Controller, onSessionStarted: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    var editing by remember(p.id) { mutableStateOf(false) }
    var first by remember(p.id) { mutableStateOf("") }
    val live = state.connection.isLive
    val role = state.role(p.role)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(p.role, p.role, 28, role?.color)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Palette.Text, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(role?.name ?: p.role, p.id).joinToString("  -  "), color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp)
            }
            Chip(p.provider ?: state.providers.firstOrNull { it.default }?.id?.let { "$it (default)" } ?: "default provider")
            Spacer(Modifier.width(4.dp))
            Chip(p.model ?: "default model")
            OutlinedButton(onClick = { editing = !editing }, enabled = live, modifier = Modifier.padding(start = 6.dp)) { Text(if (editing) "Close" else "Edit", fontSize = 11.sp) }
            TextButton(onClick = { p.id?.let { id -> actionScope.launch { controller.deleteProfile(id) } } }, enabled = live && p.id != null) {
                Text("Delete", color = Palette.Danger, fontSize = 11.sp)
            }
        }
        p.instructions?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 3) }
        if (editing) ProfileForm(p, state, controller, onDone = { editing = false })
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                first, { first = it }, label = { Text("First message") }, singleLine = true,
                modifier = Modifier.weight(1f), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                enabled = live && first.isNotBlank() && p.id != null,
                onClick = { actionScope.launch { if (controller.spawnAs(p.id!!, first.trim()) is CommandResult.Sent) { first = ""; onSessionStarted() } } },
            ) { Text("Start session") }
        }
    }
}

/** Create ([existing] null) or edit a profile. An edit keeps the id, so the daemon overwrites the stored profile. */
@Composable
private fun ProfileForm(existing: AgentProfile?, state: AppState, controller: Controller, onDone: () -> Unit) {
    val actionScope = rememberCoroutineScope()
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember(existing?.id) { mutableStateOf(existing?.role ?: state.roles.firstOrNull()?.id.orEmpty()) }
    var provider by remember(existing?.id) { mutableStateOf(existing?.provider) }
    var model by remember(existing?.id) { mutableStateOf(existing?.model.orEmpty()) }
    var instructions by remember(existing?.id) { mutableStateOf(existing?.instructions.orEmpty()) }
    // BUG-PROF-1: a sent save is not a stored one. The form closes only when the daemon's `agent` answer lands, and
    // stays open with the refusal shown when it answers `agent_save refused/failed` instead.
    var pending by remember(existing?.id) { mutableStateOf<PendingSave?>(null) }
    var refusal by remember(existing?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(pending, state.agentError, state.profiles) {
        val sent = pending ?: return@LaunchedEffect
        val error = state.agentError
        if (error != null) {
            refusal = error
            pending = null
        } else if (sent.landedIn(state.profiles)) {
            pending = null
            onDone()
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (existing == null) "New profile" else "Edit ${existing.name}", color = Palette.Text, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Role", color = Palette.Muted, fontSize = 11.sp)
            state.roles.forEach { r -> Chip(r.id, if (role == r.id) Palette.Accent else Palette.Muted, Modifier.clickable { role = r.id }) }
        }
        ProfileExecutorField(state, existing?.id, provider, model) { p, m -> provider = p; model = m }
        val chosenProvider = state.providers.firstOrNull { it.id == provider } ?: state.providers.firstOrNull { it.default }
        if (chosenProvider?.modelsStale == true) {
            Text(
                "The model list may be out of date: the daemon could not refresh it from the provider.",
                color = Palette.Muted, fontSize = 11.sp,
            )
        }
        OutlinedTextField(
            instructions, { instructions = it }, label = { Text("Instructions (start of every first message)") },
            minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = state.connection.isLive && name.isNotBlank() && role.isNotBlank(),
                onClick = {
                    actionScope.launch {
                        val profile = AgentProfile(
                            id = existing?.id, name = name.trim(), role = role,
                            // The model id is stored EXACTLY as chosen/typed: the daemon trims and validates
                            // on save (AGENT-UX-2), so the UI never rewrites it.
                            provider = provider, model = model.ifEmpty { null },
                            instructions = instructions.trim().ifEmpty { null },
                            projectId = existing?.projectId ?: state.activeProjectId,
                        )
                        refusal = null
                        val before = PendingSave(existing?.id, profile.name, state.profiles)
                        when (val r = controller.saveProfile(profile)) {
                            is CommandResult.Sent -> pending = before
                            else -> refusal = (r as? CommandResult.Refused)?.reason ?: "the save could not be sent"
                        }
                    }
                },
            ) { Text(if (existing == null) "Save profile" else "Save changes") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
        refusal?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(PROFILE_FORM_ERROR_TAG)) }
    }
}

/** BUG-PROF-1: the profile form's refusal line (the daemon's `agent_save refused/failed: ...` text). */
const val PROFILE_FORM_ERROR_TAG = "profile-form-error"

/**
 * A save the form sent and is waiting on. It has landed once the daemon's `agent` answer is in [AppState.profiles]: for
 * an edit, the profile with [id] is a NEW object (every `agent` frame decodes a fresh one); for a create, a profile
 * named [name] with an id that was not there before.
 */
private class PendingSave(val id: String?, val name: String, val before: List<AgentProfile>) {
    fun landedIn(profiles: List<AgentProfile>): Boolean {
        if (profiles === before) return false
        return if (id != null) {
            val old = before.firstOrNull { it.id == id }
            profiles.any { it.id == id && it !== old }
        } else {
            val known = before.mapNotNull { it.id }.toSet()
            profiles.any { it.id != null && it.id !in known && it.name == name }
        }
    }
}

/**
 * PICKER-ALL-2: the harness + model of a profile form, through the shared [ExecutorPicker]. [provider] null is the
 * daemon's default harness and is kept null on Save until the user picks one; [model] "" is the harness default.
 */
@Composable
internal fun ProfileExecutorField(state: AppState, key: Any?, provider: String?, model: String, onChange: (String?, String) -> Unit) {
    val stored = (provider ?: state.providers.firstOrNull { it.default }?.id)?.let { PickedModel(it, model.ifEmpty { null }) }
    SeededExecutorPicker(
        state.providers, state.modelCatalog, stored, key, PROFILE_EXECUTOR_TAGS,
        onPick = { picked -> onChange(picked?.provider ?: provider, picked?.model.orEmpty()) },
    )
}
