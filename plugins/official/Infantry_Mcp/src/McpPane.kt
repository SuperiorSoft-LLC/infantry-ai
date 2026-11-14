// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mcp

import com.superiorsoftllc.infantry.ui.protocol.McpServerStatusWire
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.ActionKind
import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import com.superiorsoftllc.infantry.ui.AhqIcons
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.IconAction
import com.superiorsoftllc.infantry.ui.model.PanelLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import com.superiorsoftllc.infantry.ui.DropdownMenuItem
import com.superiorsoftllc.infantry.ui.InfantryDropdownMenu
import com.superiorsoftllc.infantry.ui.OutlinedButton
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.ShellContext
import com.superiorsoftllc.infantry.ui.shell.gate
import kotlinx.coroutines.launch

const val MCP_ADD_TAG = "mcp-add"
const val MCP_ERROR_TAG = "mcp-error"

private fun stateColor(state: String) = when (state) {
    "connected" -> Palette.Ok
    "failed", "needs_auth" -> Palette.Danger
    "pending_approval", "disabled" -> Palette.Warn
    else -> Palette.Muted
}

private val FieldStyle = TextStyle(fontSize = 12.sp)

/** A value picked from a list the app knows (never typed): a trigger button and a menu; [options] are (value, label). */
@Composable
private fun Picker(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = Palette.Muted, fontSize = 10.sp)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Choose...", fontSize = 12.sp, maxLines = 1)
            }
            InfantryDropdownMenu(open, { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l, fontSize = 12.sp) }, onClick = { onSelect(v); open = false }) }
            }
        }
    }
}

/** The MCP Tools tab: servers by source (each a bordered card), add / edit / remove / enable / test, and the grants. */
@Composable
fun McpPane(tab: TabContext) {
    val ctx = LocalShellContext.current ?: return
    val vm = remember(ctx.link) {
        McpViewModel({ type, data -> ctx.link.request(type, data)?.let { it.type to it.data } }, { ctx.session.activeProjectId })
    }
    McpScreen(
        vm, ctx,
        roleOptions = tab.state.roles.map { it.id to it.name },
        agentOptions = tab.state.agents.keys.map { it to it },
        ticketOptions = tab.state.tickets.map { it.id to "${it.id} ${it.title}" },
        live = liveSessions(tab.state.agents.values.map { it.session.id to it.session.mcpServers }),
    )
}

@Composable
fun McpScreen(vm: McpViewModel, ctx: ShellContext, roleOptions: List<Pair<String, String>>, agentOptions: List<Pair<String, String>>, ticketOptions: List<Pair<String, String>>, live: Map<String, String> = emptyMap()) {
    val scope = rememberCoroutineScope()
    val link = ctx.link
    val canWrite = link.gate("mcp.write").enabled
    val canRemove = link.gate("mcp.remove", ActionKind.DESTRUCTIVE).enabled
    var draft by remember { mutableStateOf<ServerDraft?>(null) }
    var grantDraft by remember { mutableStateOf<GrantDraft?>(null) }

    LaunchedEffect(link.live) { if (link.live) vm.refresh() }
    // Any change, from here or the CLI, re-reads the list.
    LaunchedEffect(link) { link.frames(McpWire.CHANGED).collect { vm.refresh() } }
    // Notices go to the notification bus, never a toast of our own.
    LaunchedEffect(vm.notice) { vm.notice?.let { ctx.toast.notifications.notify("MCP Tools", it); vm.clearNotice() } }

    val targets = vm.snapshot.targets

    var tab by remember { mutableStateOf(McpTab.SERVERS) }
    var filter by remember { mutableStateOf(GrantFilter()) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = PanelLayout.isCompact(maxWidth.value)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MCP TOOLS", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { draft = ServerDraft() }, enabled = canWrite, modifier = Modifier.testTag(MCP_ADD_TAG)) { Text("Add server", fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                McpTab.entries.forEach { t ->
                    OutlinedButton(onClick = { tab = t }, modifier = Modifier.testTag("mcp-tab-${t.name.lowercase()}")) {
                        Text(if (t == McpTab.ASSIGNMENTS) "${t.label} (${vm.snapshot.grants.size})" else t.label, fontSize = 11.sp, color = if (tab == t) Palette.Text else Palette.Muted, fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Text("Walled by default: an agent gets a server only through a grant, with a purpose.", color = Palette.Muted, fontSize = 11.sp)
            if (draft == null && grantDraft == null) vm.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(MCP_ERROR_TAG)) }
            if (vm.loading) Text("Loading MCP servers...", color = Palette.Muted, fontSize = 12.sp)
            else if (tab == McpTab.SERVERS && vm.snapshot.servers.isEmpty()) Text("No MCP servers yet. Add one, or install one in your CLI and it appears here.", color = Palette.Muted, fontSize = 12.sp)

            if (tab == McpTab.SERVERS) vm.groups.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Text(g.title, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    if (g.readOnly) Chip("read-only", Palette.Muted)
                }
                g.servers.forEach { s ->
                    val target = targets.firstOrNull { it.id == s.target }
                    ServerCard(
                        s, vm, g.readOnly, canWrite, live[s.name].orEmpty(), compact,
                        canEdit = canWrite && (s.owned || target?.canEdit == true),
                        canDelete = canRemove && (s.owned || target?.canRemove == true),
                        onEdit = {
                            draft = ServerDraft(
                                editing = s, target = s.target, scope = s.scope ?: "app", name = s.name,
                                useUrl = s.url != null, command = s.command.joinToString(" "), url = s.url ?: "",
                            )
                        },
                        onGrant = { grantDraft = GrantDraft(server = s.name) },
                        onTest = { scope.launch { vm.test(s) } },
                        onToggle = { scope.launch { vm.setEnabled(s, !s.enabled) } },
                    )
                }
            }

            if (tab == McpTab.ASSIGNMENTS) AssignmentsTab(vm, filter, { filter = it }, canRemove, compact) { scope.launch { vm.revoke(it) } }
        }

        vm.pendingRemove?.let { s ->
            AhqAlertDialog(
                onDismissRequest = vm::cancelRemove,
                title = { Text("Remove ${s.name}?") },
                text = { Text("Removes the MCP server ${s.name}${s.scope?.let { " ($it)" } ?: ""}. Agents granted it lose it.", fontSize = 12.sp) },
                confirmButton = { OutlinedButton(onClick = { scope.launch { vm.confirmRemove() } }, modifier = Modifier.testTag("mcp-remove-confirm")) { Text("Remove", color = Palette.Danger) } },
                dismissButton = { OutlinedButton(onClick = vm::cancelRemove) { Text("Cancel") } },
            )
        }

        draft?.let { d ->
            val adders = targets.filter { it.canAdd && it.id != McpWire.INFANTRY }
            val scopes = if (d.target == McpWire.INFANTRY) listOf("app", "project") else targets.firstOrNull { it.id == d.target }?.scopes.orEmpty()
            AhqAlertDialog(
                onDismissRequest = { draft = null },
                wide = true,
                modifier = Modifier.fillMaxWidth(0.9f).testTag("mcp-server-dialog"),
                title = { Text(if (d.editing == null) "Add MCP server" else "Edit ${d.editing.name}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (d.editing == null) {
                            val tOpts = listOf(McpWire.INFANTRY to "Infantry (managed here)") + adders.map { it.id to it.id }
                            Picker("Where", tOpts, d.target, { t -> draft = d.copy(target = t, scope = if (t == McpWire.INFANTRY) "app" else targets.firstOrNull { it.id == t }?.scopes?.firstOrNull() ?: "") })
                            if (scopes.size > 1) Picker("Scope", scopes.map { it to it }, d.scope, { draft = d.copy(scope = it) })
                        }
                        OutlinedTextField(d.name, { draft = d.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = d.editing == null, textStyle = FieldStyle, shape = Palette.FieldShape, modifier = Modifier.fillMaxWidth().testTag("mcp-name"))
                        Picker("Transport", listOf("cmd" to "Command (stdio)", "url" to "URL (http)"), if (d.useUrl) "url" else "cmd", { draft = d.copy(useUrl = it == "url") })
                        if (d.useUrl) OutlinedTextField(d.url, { draft = d.copy(url = it) }, label = { Text("URL") }, singleLine = true, textStyle = FieldStyle, shape = Palette.FieldShape, modifier = Modifier.fillMaxWidth().testTag("mcp-url"))
                        else OutlinedTextField(d.command, { draft = d.copy(command = it) }, label = { Text("Command") }, singleLine = true, textStyle = FieldStyle, shape = Palette.FieldShape, modifier = Modifier.fillMaxWidth().testTag("mcp-command"))
                        var advanced by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced", fontSize = 11.sp) }
                        if (advanced) {
                            OutlinedTextField(d.env, { draft = d.copy(env = it) }, label = { Text("Environment, KEY=value per line (plain values only)") }, minLines = 2, textStyle = FieldStyle, shape = Palette.FieldShape, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(d.envSecrets, { draft = d.copy(envSecrets = it) }, label = { Text("Secrets, KEY=name of a Secrets entry per line") }, minLines = 2, textStyle = FieldStyle, shape = Palette.FieldShape, modifier = Modifier.fillMaxWidth())
                            d.editing?.takeIf { it.envKeys.isNotEmpty() || it.headerKeys.isNotEmpty() }?.let {
                                Text("Values are never shown. Saving replaces the server: re-enter its environment (${(it.envKeys + it.headerKeys).joinToString()}).", color = Palette.Warn, fontSize = 10.sp)
                            }
                        }
                        vm.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(MCP_ERROR_TAG)) }
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = { scope.launch { if (vm.save(d)) draft = null } }, enabled = canWrite && serverProblem(d) == null, modifier = Modifier.testTag("mcp-save")) { Text("Save") }
                },
                dismissButton = { OutlinedButton(onClick = { draft = null }) { Text("Cancel") } },
            )
        }

        grantDraft?.let { g ->
            val targetOptions = when (g.scope) { "agent" -> agentOptions; "ticket" -> ticketOptions; else -> roleOptions }
            AhqAlertDialog(
                onDismissRequest = { grantDraft = null },
                wide = true,
                modifier = Modifier.fillMaxWidth(0.9f).testTag("mcp-grant-dialog"),
                title = { Text("Grant ${g.server}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Picker("Grant to", listOf("role" to "A role", "agent" to "An agent", "ticket" to "A ticket"), g.scope, { grantDraft = g.copy(scope = it, target = "") })
                        Picker("Which one", targetOptions, g.target, { grantDraft = g.copy(target = it) })
                        OutlinedTextField(g.purpose, { grantDraft = g.copy(purpose = it) }, label = { Text("Purpose (required): why does it need this server?") }, minLines = 2, textStyle = FieldStyle, shape = Palette.FieldShape, modifier = Modifier.fillMaxWidth().testTag("mcp-purpose"))
                        vm.error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(MCP_ERROR_TAG)) }
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = { scope.launch { if (vm.grant(g)) grantDraft = null } }, enabled = canWrite && grantProblem(g) == null, modifier = Modifier.testTag("mcp-grant-save")) { Text("Grant") }
                },
                dismissButton = { OutlinedButton(onClick = { grantDraft = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ServerCard(
    s: McpServer, vm: McpViewModel, readOnly: Boolean, canWrite: Boolean, live: String, compact: Boolean, canEdit: Boolean, canDelete: Boolean,
    onEdit: () -> Unit, onGrant: () -> Unit, onTest: () -> Unit, onToggle: () -> Unit,
) {
    val tested = vm.tested[s.key]
    val state = tested?.first ?: if (!s.enabled) "disabled" else s.state
    Column(Modifier.fillMaxWidth().border(1.dp, Palette.Border, RoundedCornerShape(6.dp)).padding(10.dp).testTag("mcp-server-${s.name}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            Chip(s.transport.ifBlank { "stdio" }, Palette.Info)
            Chip(if (state == "unknown" || state == "configured") "untested" else state.replace('_', ' '), stateColor(state))
        }
        if (s.address.isNotBlank()) Text(s.address, color = Palette.Muted, fontSize = 11.sp, maxLines = 2)
        (tested?.second ?: s.detail)?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, maxLines = 2) }
        s.shadowedBy?.let { Text("Shadowed by $it: never delivered.", color = Palette.Warn, fontSize = 10.sp) }
        Text("Granted to: ${grantSummary(s, vm.snapshot.grants)}", color = Palette.Muted, fontSize = 11.sp)
        live.takeIf { it.isNotEmpty() }?.let { Text("In sessions: $it", color = Palette.Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.testTag("mcp-live-${s.name}")) }
        if (compact) {
            var open by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = onTest, enabled = canWrite, modifier = Modifier.testTag("mcp-test-${s.name}")) { Text("Test", fontSize = 11.sp) }
                Box {
                    IconAction(AhqIcons.MoreVert, "More actions", { open = true }, Modifier.testTag("mcp-more-${s.name}"))
                    InfantryDropdownMenu(open, { open = false }) {
                        overflowActions(readOnly, s.owned).forEach { act ->
                            val label = when (act) {
                                CardAction.GRANT -> "Grant"
                                CardAction.TOGGLE -> if (s.enabled) "Disable" else "Enable"
                                CardAction.EDIT -> "Edit"
                                CardAction.REMOVE -> "Remove"
                            }
                            val allowed = when (act) { CardAction.GRANT, CardAction.TOGGLE -> canWrite; CardAction.EDIT -> canEdit; CardAction.REMOVE -> canDelete }
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 12.sp, color = if (act == CardAction.REMOVE) Palette.Danger else Palette.Text) },
                                enabled = allowed,
                                onClick = { open = false; when (act) { CardAction.GRANT -> onGrant(); CardAction.TOGGLE -> onToggle(); CardAction.EDIT -> onEdit(); CardAction.REMOVE -> vm.askRemove(s) } },
                                modifier = Modifier.testTag("mcp-${act.name.lowercase()}-${s.name}"),
                            )
                        }
                    }
                }
            }
        } else
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onTest, enabled = canWrite, modifier = Modifier.testTag("mcp-test-${s.name}")) { Text("Test", fontSize = 11.sp) }
            OutlinedButton(onClick = onGrant, enabled = canWrite, modifier = Modifier.testTag("mcp-grant-${s.name}")) { Text("Grant", fontSize = 11.sp) }
            if (!readOnly) {
                if (s.owned) OutlinedButton(onClick = onToggle, enabled = canWrite, modifier = Modifier.testTag("mcp-toggle-${s.name}")) { Text(if (s.enabled) "Disable" else "Enable", fontSize = 11.sp) }
                OutlinedButton(onClick = onEdit, enabled = canEdit, modifier = Modifier.testTag("mcp-edit-${s.name}")) { Text("Edit", fontSize = 11.sp) }
                OutlinedButton(onClick = { vm.askRemove(s) }, enabled = canDelete, modifier = Modifier.testTag("mcp-remove-${s.name}")) { Text("Remove", fontSize = 11.sp, color = Palette.Danger) }
            }
        }
    }
}


/** MCP-UI-2: every grant (server, target, purpose, approver, date) with revoke, filtered by server or target. */
@Composable
private fun AssignmentsTab(vm: McpViewModel, filter: GrantFilter, onFilter: (GrantFilter) -> Unit, canRevoke: Boolean, compact: Boolean, onRevoke: (McpGrant) -> Unit) {
    val all = vm.snapshot.grants
    val shown = filterGrants(all, filter)
    val servers = listOf("" to "All servers") + all.map { it.server }.distinct().sorted().map { it to it }
    val targets = listOf("" to "All targets") + all.map { it.target }.distinct().sorted().map { it to it }
    if (compact) {
        Picker("Server", servers, filter.server, { onFilter(filter.copy(server = it)) }, Modifier.fillMaxWidth())
        Picker("Target", targets, filter.target, { onFilter(filter.copy(target = it)) }, Modifier.fillMaxWidth())
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("Server", servers, filter.server, { onFilter(filter.copy(server = it)) }, Modifier.weight(1f))
        Picker("Target", targets, filter.target, { onFilter(filter.copy(target = it)) }, Modifier.weight(1f))
    }
    if (shown.isEmpty()) Text(if (all.isEmpty()) "No grants yet: every server is walled. Grant one from a server card." else "No grant matches this filter.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag("mcp-grants-empty"))
    shown.forEach { g ->
        Column(Modifier.fillMaxWidth().border(1.dp, Palette.Border, RoundedCornerShape(6.dp)).padding(10.dp).testTag("mcp-grant-${g.id}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${g.server}  ->  ${g.scope} ${g.target}", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Purpose: ${g.purpose}", color = Palette.Muted, fontSize = 11.sp)
            Text("Approved by ${g.approvedBy}" + grantDate(g.approvedAt).let { if (it.isEmpty()) "" else " on $it" }, color = Palette.Muted, fontSize = 10.sp)
            OutlinedButton(onClick = { onRevoke(g) }, enabled = canRevoke, modifier = Modifier.padding(top = 4.dp).testTag("mcp-revoke-${g.id}")) { Text("Revoke", fontSize = 11.sp, color = Palette.Danger) }
        }
    }
}

/** MCP-STATUS-1: server name -> its live state in each running session that reported it, compact (`1234abcd connected, ...`). */
internal fun liveSessions(sessions: List<Pair<String, List<McpServerStatusWire>>>): Map<String, String> =
    sessions.flatMap { (id, l) -> l.map { it.name to "${id.takeLast(8)} ${it.state.replace('_', ' ')}" } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.joinToString(", ") }
