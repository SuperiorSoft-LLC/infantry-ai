// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run

import com.superiorsoftllc.infantry.ui.PathField
import com.superiorsoftllc.infantry.ui.PathKind
import com.superiorsoftllc.infantry.ui.PathRequest
import com.superiorsoftllc.infantry.ui.currentPathPicker
import com.superiorsoftllc.infantry.ui.shell.gate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.model.CommandSpecView
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.projectCommands
import com.superiorsoftllc.infantry.ui.model.RunState
import com.superiorsoftllc.infantry.ui.model.RunsState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.model.commandPills
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.RunContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

const val RUN_PANE_TAG = "run-pane"
const val RUN_COMMAND_PICKER_TAG = "run-command-picker"
const val RUN_START_TAG = "run-start"
const val RUN_FORM_SECRETS_TAG = "run-form-secrets"
const val RUN_STOP_TAG = "run-stop"
const val RUN_OUTPUT_TAG = "run-output"
const val RUN_ADD_TAG = "run-add"
const val RUN_EDIT_TAG = "run-edit"
const val RUN_DELETE_TAG = "run-delete"
const val RUN_DELETE_CONFIRM_TAG = "run-delete-confirm"
const val RUN_DELETE_CANCEL_TAG = "run-delete-cancel"
const val RUN_FORM_TAG = "run-form"
const val RUN_FORM_NAME_TAG = "run-form-name"
const val RUN_FORM_ID_TAG = "run-form-id"
const val RUN_FORM_ARGV_TAG = "run-form-argv"
const val RUN_FORM_CWD_TAG = "run-form-cwd"
const val RUN_FORM_GRACE_TAG = "run-form-grace"
const val RUN_FORM_DETACHED_TAG = "run-form-detached"
const val RUN_FORM_SAVE_TAG = "run-form-save"
const val RUN_FORM_CANCEL_TAG = "run-form-cancel"
const val RUN_FORM_ERROR_TAG = "run-form-error"
const val RUN_PILLS_TAG = "run-pills"
const val RUN_INTERACTIVE_TAG = "run-interactive"
const val RUN_FOLDER_TAG = "run-folder"
const val RUN_SET_FOLDER_TAG = "run-set-folder"
const val RUN_FOLDER_PATH_TAG = "run-folder-path"
const val RUN_FOLDER_SAVE_TAG = "run-folder-save"
const val RUN_UNASSIGNED_TAG = "run-unassigned"
/** PREVIEW-BRANCH-1: a branch in Start on branch's list, followed by `-<branch>`. */
const val RUN_BRANCH_TAG = "run-branch"
/** Followed by `-<command id>`. */
const val RUN_ASSIGN_TAG = "run-assign"

/** RUN-CMD-2: the command editor's pure rules (no Compose), shared by the pane and its tests. */
object RunCommandForm {
    const val DEFAULT_GRACE_MILLIS = 5000L

    /** The id derived from a name: lower-case letters, digits and single dashes, e.g. "Build web" -> "build-web". */
    fun idFor(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** One argument per line, each trimmed; blank lines are dropped. Never a shell string: no quoting, no splitting. */
    fun argvOf(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The working directory as sent: null (the repo root) when left blank. */
    fun cwdOf(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

    /** SECRETS-2: secret names, one per line or comma-separated; blanks and repeats dropped. */
    fun secretsOf(text: String): List<String> = text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/**
 * PREVIEW-BRANCH-1: a branch command (its cwd or argv names `{worktree}` or `{branch}`) starts on a branch the owner picks;
 * the daemon runs it in that branch's checkout. The rules here are the daemon's (BranchVars), read off the saved command.
 */
object RunBranches {
    const val WORKTREE = "{worktree}"
    const val BRANCH = "{branch}"
    /** The daemon refuses a start at once; a start that goes ahead is answered by the `run` broadcast, never directly. */
    const val REFUSAL_WAIT_MILLIS = 3_000L

    fun isBranchCommand(c: CommandSpecView): Boolean =
        (listOfNotNull(c.cwd) + c.argv).any { it.contains(WORKTREE) || it.contains(BRANCH) }

    /** `run_branches` answer -> rows; null when it is not that answer. */
    fun parse(data: String): List<BranchRow>? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(data) as kotlinx.serialization.json.JsonObject
        (o["branches"] as kotlinx.serialization.json.JsonArray).map { e ->
            val b = e as kotlinx.serialization.json.JsonObject
            BranchRow(
                (b["name"] as kotlinx.serialization.json.JsonPrimitive).content,
                (b["worktree"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }.getOrNull()

    /** The `run_start` body for [commandId] on [branch]. */
    fun startBody(commandId: String, project: String?, branch: String): String = kotlinx.serialization.json.buildJsonObject {
        put("commandId", kotlinx.serialization.json.JsonPrimitive(commandId))
        project?.let { put("project", kotlinx.serialization.json.JsonPrimitive(it)) }
        put("branch", kotlinx.serialization.json.JsonPrimitive(branch))
    }.toString()
}

/** PREVIEW-BRANCH-1: one local branch, with the worktree it is checked out in (a ticket's), if any. */
data class BranchRow(val name: String, val worktree: String? = null)

/** The editor's fields. [editingId] is null for a new command; an edited command keeps its id. */
internal data class CommandDraft(
    val editingId: String? = null,
    val name: String = "",
    val id: String = "",
    val idTouched: Boolean = false,
    val argv: String = "",
    val cwd: String = "",
    val grace: String = RunCommandForm.DEFAULT_GRACE_MILLIS.toString(),
    val secrets: String = "",
    /** RUN-TAB-1: launch an app that outlives the command. */
    val detached: Boolean = false,
) {
    companion object {
        fun of(c: CommandSpecView) = CommandDraft(
            editingId = c.id, name = c.name, id = c.id, idTouched = true,
            argv = c.argv.joinToString("\n"), cwd = c.cwd.orEmpty(),
            grace = (c.graceMillis ?: RunCommandForm.DEFAULT_GRACE_MILLIS).toString(),
            secrets = c.secrets.joinToString("\n"), detached = c.detached,
        )
    }
}

/*
 * In-app build run panel (design section 4): a command dropdown, Start, Stop and a scrolling monospace output pane.
 * Everything shown comes from the run context's runs; the local state is the picked command, the dropdown, and the
 * command editor.
 *
 * RUN-CMD-2: Add / Edit / Delete next to the picker. The editor sends `command_save`; the daemon's refusal shows
 * inline under the form, and the form closes once the new `commands` list arrives. Delete asks for an inline confirm
 * (never an OS dialog) and sends `command_delete`. Both are off while a run is in flight or the link is not live.
 * The button rows wrap and the form scrolls, so the compact (phone) layout fits.
 *
 * BLOCKS-LAYOUT L15: drawn as [RunPaneFrame] (the column, its title, the save watch) and its sections, which the Run tab's
 * `infantry.shell.run` node draws as child blocks ([ShellRun]). RUNTIME-7: everything here reads the plugin API contexts
 * (link, run); the window's AppState / Controller are not used.
 */

/**
 * BLOCKS-LAYOUT L15: the Run pane as its sections read it: the runs, whether the link is [live], the [picked] command, the
 * run in flight ([active]) and the one shown ([current]), the open editor, the delete confirm and the last refusal. The
 * frame owns this state; a section changes it only through these callbacks.
 */
class RunPaneHost internal constructor(
    val link: LinkContext,
    /** S2: the runs and their requests (the shell's [com.superiorsoftllc.infantry.ui.shell.ShellContext.run]). */
    val context: RunContext,
    private val scope: CoroutineScope,
    private val pickedId: MutableState<String?>,
    private val draftState: MutableState<CommandDraft?>,
    private val confirming: MutableState<Boolean>,
    private val localError: MutableState<String?>,
    private val savedAt: MutableState<Int?>,
) {
    val runs: RunsState = context.runs
    val live: Boolean = link.live
    /** POLICY-1: the named action [actionId] may be performed now (live, and the daemon's policy allows it). */
    @Composable fun can(actionId: String, kind: String = com.superiorsoftllc.infantry.plugin.api.ActionKind.WRITE): Boolean = link.gate(actionId, kind).enabled
    /** RUN-SCOPE-1: the project this tab shows; its commands run relative to its folder. */
    val project: ProjectView? = context.project
    private val scoped = projectCommands(runs.commands, project?.id)
    /** RUN-SCOPE-1: the shown project's commands only; switching projects switches the list. */
    val commands: List<CommandSpecView> = scoped.own
    /** RUN-SCOPE-1: commands that belong to no project yet, each with "assign to this project". */
    val unassigned: List<CommandSpecView> = scoped.unassigned
    /** RUN-SCOPE-1: the shown project has no folder registered, so its commands have nothing to run in. */
    val noFolder: Boolean = project != null && project.directory.isBlank()
    val picked: CommandSpecView? = commands.firstOrNull { it.id == pickedId.value } ?: commands.firstOrNull()
    val current: RunState? = runs.current
    /** The run going now (one at a time), whichever pill is selected. */
    val active: RunState? = runs.runs.values.firstOrNull { it.state != "FINISHED" }
    val inFlight: Boolean = active != null
    /** GATE-PANES-1: add / edit / delete a saved command is `commands.save`; off while a run is in flight too. */
    val canEdit: Boolean @Composable get() = can("commands.save") && !inFlight
    internal val draft: CommandDraft? get() = draftState.value
    /** The add / edit form is open. */
    val editing: Boolean get() = draftState.value != null
    val confirmDelete: Boolean get() = confirming.value
    /** A save is out, not yet answered. */
    val saving: Boolean get() = savedAt.value != null
    /** The daemon's refusal of a command edit, else the last refusal here; "no folder" in plain words. */
    val error: String? get() = (runs.commandError ?: localError.value)?.let { plainRefusal(it) }

    private fun plainRefusal(raw: String): String =
        if ("no working directory can be checked" in raw || "has no folder registered" in raw) {
            "${project?.name?.let { "\"$it\"" } ?: "This project"} has no project folder yet. Set the project folder, then save or run its commands."
        } else raw

    /** Sends [request] and keeps its refusal. */
    fun act(request: suspend () -> CommandResult) {
        localError.value = null
        scope.launch {
            val r = request()
            if (r is CommandResult.Refused) localError.value = r.reason
        }
    }

    fun pick(id: String) { pickedId.value = id; confirming.value = false }

    fun start(interactive: Boolean) {
        picked?.let { c -> act { context.start(c.id, interactive, project?.id) } }
    }

    /** PREVIEW-BRANCH-1: the picked command runs on a branch the owner picks. */
    val pickedOnBranch: Boolean get() = picked?.let { RunBranches.isBranchCommand(it) } == true

    /** PREVIEW-BRANCH-1: the shown project's local branches (`run_branches`); a failure says why in plain words. */
    suspend fun branches(): Result<List<BranchRow>> {
        val body = kotlinx.serialization.json.buildJsonObject { project?.id?.let { put("project", kotlinx.serialization.json.JsonPrimitive(it)) } }.toString()
        val r = link.request("run_branches", body) ?: return Result.failure(IllegalStateException("not connected to the daemon"))
        if (r.refused) return Result.failure(IllegalStateException(plainRefusal(r.data)))
        return RunBranches.parse(r.data)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("this daemon does not list branches (update Infantry_Run)"))
    }

    /** PREVIEW-BRANCH-1: starts the picked branch command on [branch]; a refusal shows like any other. */
    fun startOnBranch(branch: String) {
        val c = picked ?: return
        localError.value = null
        scope.launch {
            val body = RunBranches.startBody(c.id, project?.id, branch)
            val r = kotlinx.coroutines.withTimeoutOrNull(RunBranches.REFUSAL_WAIT_MILLIS) { link.request("run_start", body) }
            if (r?.refused == true) localError.value = r.data
        }
    }

    /** RUN-SCOPE-1: moves the unassigned command [c] into the shown project, its cwd unchanged (relative to the folder). */
    fun assign(c: CommandSpecView) {
        val p = project ?: return
        act { context.saveCommand(c.id, c.name, c.argv, c.cwd, c.graceMillis ?: RunCommandForm.DEFAULT_GRACE_MILLIS, c.secrets, p.id, c.detached) }
    }

    /** RUN-SCOPE-1: registers [directory] as the shown project's folder. */
    fun setFolder(directory: String) {
        val p = project ?: return
        if (directory.isBlank()) return
        act { context.setProjectFolder(p.id, directory.trim()) }
    }

    fun stop() { active?.let { r -> act { context.stop(r.runId) } } }

    /** Shows [runId]'s lines and, on the desktop, opens its console window. */
    fun openRun(runId: String) {
        context.open(runId)
    }

    fun openNew() {
        draftState.value = CommandDraft()
        confirming.value = false
        localError.value = null
    }

    fun editPicked() { picked?.let { draftState.value = CommandDraft.of(it) }; confirming.value = false; localError.value = null }

    internal fun change(draft: CommandDraft) { draftState.value = draft }

    fun cancelEdit() { draftState.value = null; savedAt.value = null; localError.value = null }

    fun saveDraft() {
        val d = draftState.value ?: return
        val grace = d.grace.trim().toLongOrNull()
        if (grace == null) {
            localError.value = "Grace ms must be a whole number of milliseconds."
        } else {
            localError.value = null
            savedAt.value = runs.commandsRevision
            scope.launch {
                val r = context.saveCommand(
                    d.id.trim(), d.name.trim(), RunCommandForm.argvOf(d.argv), RunCommandForm.cwdOf(d.cwd), grace,
                    RunCommandForm.secretsOf(d.secrets), project?.id, d.detached,
                )
                if (r is CommandResult.Refused) { localError.value = r.reason; savedAt.value = null }
            }
        }
    }

    fun askDelete() { confirming.value = true }

    fun cancelDelete() { confirming.value = false }

    fun deletePicked() {
        val c = picked ?: return
        confirming.value = false
        act { context.deleteCommand(c.id) }
    }
}

/**
 * The Run pane's frame: the column, its title, and the watch that closes the editor once a save is answered by a new
 * `commands` list. [content] gets the host and draws straight into the column. [modifier] goes on the column.
 */
@Composable
fun RunPaneFrame(link: LinkContext, ctx: RunContext, modifier: Modifier = Modifier, content: @Composable ColumnScope.(RunPaneHost) -> Unit) {
    val scope = rememberCoroutineScope()
    val runs = ctx.runs
    val pickedId = remember { mutableStateOf<String?>(null) }
    val localError = remember { mutableStateOf<String?>(null) }
    val draft = remember { mutableStateOf<CommandDraft?>(null) }
    val confirmDelete = remember { mutableStateOf(false) }
    // The `commands` revision a save was sent against: a newer list with no refusal means it was saved.
    val savedAt = remember { mutableStateOf<Int?>(null) }

    // The controller clears the old refusal before it sends, so the first answer after the save decides:
    // a refusal keeps the form open (its reason shows inline), a new list closes it.
    LaunchedEffect(runs.commandsRevision) {
        val sent = savedAt.value ?: return@LaunchedEffect
        if (runs.commandsRevision > sent) {
            savedAt.value = null
            if (runs.commandError == null) draft.value = null
        }
    }

    Column(modifier.fillMaxSize().padding(12.dp).testTag(RUN_PANE_TAG)) {
        Text("RUN", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
        content(RunPaneHost(link, ctx, scope, pickedId, draft, confirmDelete, localError, savedAt))
    }
}

/**
 * RUN-SCOPE-1: when the shown project has no folder registered, says so plainly with "Set project folder": the platform's
 * folder chooser where there is one, else a path field. Saving it is what `project_config_set directory` does. [modifier]
 * goes on the column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunFolderSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    val project = run.project ?: return
    if (!run.noFolder) return
    val picker = currentPathPicker(PathKind.FOLDER)
    val scope = rememberCoroutineScope()
    var typing by remember(project.id) { mutableStateOf(false) }
    var path by remember(project.id) { mutableStateOf("") }
    val canSet = run.live && run.can("projects.save")
    Column(modifier.fillMaxWidth().padding(bottom = 6.dp).testTag(RUN_FOLDER_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "\"${project.name}\" has no project folder. Run commands run relative to the project's folder; set it to save and run them.",
            color = Palette.Danger, fontSize = 12.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                // PATH-PICKER-1: shows the path field (typed, or Browse) and, where there is a chooser, opens it at once.
                onClick = {
                    typing = true
                    if (picker != null) scope.launch {
                        picker.pick(PathRequest(PathKind.FOLDER, "Choose the project folder", run.project?.directory.orEmpty()))
                            ?.trim()?.takeIf { it.isNotEmpty() }?.let { path = it; run.setFolder(it) }
                    }
                },
                enabled = canSet,
                modifier = Modifier.testTag(RUN_SET_FOLDER_TAG),
            ) { Text("Set project folder", fontSize = 12.sp) }
            if (typing) {
                PathField(
                    path, { path = it }, PathKind.FOLDER, "Folder (absolute path)", Modifier.widthIn(min = 260.dp, max = 480.dp),
                    tag = RUN_FOLDER_PATH_TAG, title = "Choose the project folder",
                    onPicked = { path = it; run.setFolder(it) },
                )
                OutlinedButton(
                    onClick = { run.setFolder(path) },
                    enabled = canSet && path.isNotBlank(),
                    modifier = Modifier.testTag(RUN_FOLDER_SAVE_TAG),
                ) { Text("Save folder", fontSize = 12.sp) }
            }
        }
    }
}

/**
 * RUN-SCOPE-1: commands that belong to no project (saved before commands were per project, and not inside any project's
 * folder), each with "Assign to <project>". Never hidden, never deleted silently. [modifier] goes on the column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunUnassignedSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    val project = run.project ?: return
    if (run.unassigned.isEmpty() || run.editing) return
    Column(modifier.fillMaxWidth().padding(top = 6.dp).testTag(RUN_UNASSIGNED_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("UNASSIGNED", color = Palette.Muted, fontSize = 10.sp)
        run.unassigned.forEach { c ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = Palette.Text, fontSize = 12.sp)
                c.cwd?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { run.assign(c) },
                    enabled = run.canEdit && !run.noFolder,
                    modifier = Modifier.testTag("$RUN_ASSIGN_TAG-${c.id}"),
                ) { Text("Assign to ${project.name}", fontSize = 12.sp) }
            }
        }
    }
}

/** With no saved command and no editor open: says so, with Add command. [modifier] goes on the row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunEmptySection(run: RunPaneHost, modifier: Modifier = Modifier) {
    if (run.commands.isNotEmpty() || run.editing) return
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(run.project?.let { "No run commands in \"${it.name}\" yet." } ?: "No saved commands on this daemon.", color = Palette.Muted, fontSize = 12.sp)
        OutlinedButton(onClick = run::openNew, enabled = run.canEdit, modifier = Modifier.testTag(RUN_ADD_TAG)) {
            Text("Add command", fontSize = 12.sp)
        }
    }
}

/**
 * The command picker, Start, Interactive (the desktop, on a 1.134.0 daemon), Stop, Add / Edit / Delete and the shown run's
 * state. The dropdown and the Interactive tick are its own. [modifier] goes on the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunToolbarSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    val runs = run.runs
    val live = run.live
    // POLICY-1: write controls are named actions gated by the daemon's policy (and any write policy); reads keep live.
    val canWrite = run.can("runs.start", com.superiorsoftllc.infantry.plugin.api.ActionKind.SPAWN)
    val canStop = run.can("runs.stop", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE)
    val inFlight = run.inFlight
    val picked = run.picked
    var menuOpen by remember { mutableStateOf(false) }
    var interactive by remember { mutableStateOf(false) }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                enabled = canWrite && !inFlight && run.commands.isNotEmpty(),
                modifier = Modifier.testTag(RUN_COMMAND_PICKER_TAG),
            ) { Text(picked?.name ?: "Pick a command", fontSize = 12.sp) }
            com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                run.commands.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name, fontSize = 12.sp) },
                        onClick = { run.pick(c.id); menuOpen = false },
                        modifier = Modifier.testTag("run-command-${c.id}"),
                    )
                }
            }
        }
        if (run.pickedOnBranch) {
            RunBranchStart(run, enabled = canWrite && !inFlight && picked != null && picked.rooted)
        } else OutlinedButton(
            onClick = { run.start(interactive) },
            enabled = canWrite && !inFlight && picked != null && picked.rooted,
            modifier = Modifier.testTag(RUN_START_TAG),
        ) { Text("Start", fontSize = 12.sp) }
        // Typing into a run needs a console to type in: the desktop only, on a 1.134.0 daemon.
        if (run.context.hasConsoleWindow && run.context.interactiveSupported) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = interactive, onCheckedChange = { interactive = it }, enabled = canWrite && !inFlight,
                    modifier = Modifier.testTag(RUN_INTERACTIVE_TAG),
                )
                Text("Interactive", color = Palette.Muted, fontSize = 12.sp)
            }
        }
        OutlinedButton(
            onClick = run::stop,
            enabled = canStop && inFlight,
            modifier = Modifier.testTag(RUN_STOP_TAG),
        ) { Text("Stop", fontSize = 12.sp) }
        if (run.commands.isNotEmpty()) {
            OutlinedButton(
                onClick = run::openNew,
                enabled = run.canEdit && !run.editing,
                modifier = Modifier.testTag(RUN_ADD_TAG),
            ) { Text("Add", fontSize = 12.sp) }
            OutlinedButton(
                onClick = run::editPicked,
                enabled = run.canEdit && !run.editing && picked != null,
                modifier = Modifier.testTag(RUN_EDIT_TAG),
            ) { Text("Edit", fontSize = 12.sp) }
            OutlinedButton(
                onClick = run::askDelete,
                enabled = run.canEdit && !run.editing && picked != null && !run.confirmDelete,
                modifier = Modifier.testTag(RUN_DELETE_TAG),
            ) { Text("Delete", fontSize = 12.sp) }
        }
        run.current?.let { Text(it.finished?.outcome ?: it.state, color = Palette.Muted, fontSize = 11.sp) }
    }
}

/**
 * PREVIEW-BRANCH-1: Start for a branch command: "Start on branch..." lists the project's local branches (a ticket's
 * worktree named beside its branch); picking one starts the command in that branch's checkout.
 */
@Composable
private fun RunBranchStart(run: RunPaneHost, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<BranchRow>?>(null) }
    var why by remember { mutableStateOf<String?>(null) }
    Box {
        OutlinedButton(
            onClick = {
                open = true; rows = null; why = null
                scope.launch { run.branches().fold({ rows = it }, { why = it.message }) }
            },
            enabled = enabled,
            modifier = Modifier.testTag(RUN_START_TAG),
        ) { Text("Start on branch...", fontSize = 12.sp) }
        com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val list = rows
            when {
                why != null -> Text(why.orEmpty(), color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                list == null -> Text("Loading branches...", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                list.isEmpty() -> Text("No local branches.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                else -> list.forEach { b ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(b.name, fontSize = 12.sp)
                                b.worktree?.let { Text("worktree: $it", color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp) }
                            }
                        },
                        onClick = { open = false; run.startOnBranch(b.name) },
                        modifier = Modifier.testTag("$RUN_BRANCH_TAG-${b.name}"),
                    )
                }
            }
        }
    }
}

/**
 * RUN-CONSOLE: one pill per run, newest first. A click shows that run's lines below and, on the desktop, opens its console
 * window. The pills' clock is its own: it ticks once a second while a run is going, so "running 0:42" counts. [modifier]
 * goes on the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunPillsSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    val inFlight = run.inFlight
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(inFlight) {
        now = Clock.System.now().toEpochMilliseconds()
        while (inFlight) { delay(1000); now = Clock.System.now().toEpochMilliseconds() }
    }
    val pills = commandPills(run.runs, now, run.commands.map { it.id }.toSet().takeIf { run.project != null })
    if (pills.isEmpty()) return
    FlowRow(
        modifier.padding(top = 6.dp).testTag(RUN_PILLS_TAG),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pills.forEach { p ->
            RunPillChip(p, selected = p.runId == run.runs.currentRunId, onClick = { run.openRun(p.runId) })
        }
    }
}

/** The inline delete confirm (never an OS dialog), while it is asked. [modifier] goes on the row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunConfirmSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    val picked = run.picked
    if (!run.confirmDelete || picked == null) return
    FlowRow(
        modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Delete \"${picked.name}\"?", color = Palette.Danger, fontSize = 12.sp)
        OutlinedButton(
            onClick = run::deletePicked,
            enabled = run.canEdit,
            modifier = Modifier.testTag(RUN_DELETE_CONFIRM_TAG),
        ) { Text("Delete", fontSize = 12.sp, color = Palette.Danger) }
        TextButton(onClick = run::cancelDelete, modifier = Modifier.testTag(RUN_DELETE_CANCEL_TAG)) {
            Text("Cancel", fontSize = 12.sp)
        }
    }
}

/** The add / edit form while it is open. In the pane it takes the rest of the height. [modifier] goes on the form. */
@Composable
fun RunEditorSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    val d = run.draft ?: return
    CommandEditor(
        draft = d,
        // SECRETS-2: the field shows only against a daemon that injects secrets (1.135.0).
        secretNames = run.context.secretNames,
        enabled = run.canEdit && !run.saving,
        error = run.error,
        onChange = run::change,
        onCancel = run::cancelEdit,
        onSave = run::saveDraft,
        modifier = modifier,
    )
}

/**
 * While no editor is open: the last refusal, an output gap, and how many earlier lines are hidden, each when there is one.
 * [modifier] goes on the first of them.
 */
@Composable
fun RunNoticesSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    if (run.editing) return
    val current = run.current
    val notices = listOfNotNull(
        run.error?.let { it to Palette.Danger },
        current?.gap?.let { "Output gap: expected line ${it.expected}, got ${it.got}. Later lines are not shown." to Palette.Danger },
        current?.takeIf { it.droppedLines > 0 }?.let { "${it.droppedLines} earlier lines hidden" to Palette.Muted },
    )
    notices.forEachIndexed { i, (text, color) ->
        Text(text, color = color, fontSize = 11.sp, modifier = (if (i == 0) modifier else Modifier).padding(top = 6.dp))
    }
}

/**
 * While no editor is open: the shown run's lines in monospace, stderr in red, kept scrolled to the newest. In the pane it
 * takes the rest of the height. [modifier] goes on the list.
 */
@Composable
fun RunOutputSection(run: RunPaneHost, modifier: Modifier = Modifier) {
    if (run.editing) return
    val lines = run.current?.lines.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp).background(Palette.Panel).padding(8.dp).testTag(RUN_OUTPUT_TAG),
    ) {
        items(lines) { l ->
            Text(
                l.text, fontFamily = Mono, fontSize = 12.sp,
                color = if (l.stream == "stderr") Palette.Danger else Palette.Text,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

/** RUN-CMD-2: the add / edit form. The argv is one item per line, not a shell string; the field's hint says so. */
@Composable
private fun CommandEditor(
    draft: CommandDraft,
    /** SECRETS-2: the keystore's user secrets to name; null hides the field (an older daemon). */
    secretNames: List<String>?,
    enabled: Boolean,
    error: String?,
    onChange: (CommandDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = draft.editingId != null
    val argv = RunCommandForm.argvOf(draft.argv)
    Column(
        modifier.fillMaxWidth().padding(top = 8.dp).background(Palette.Panel)
            .verticalScroll(rememberScrollState()).padding(8.dp).testTag(RUN_FORM_TAG),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (editing) "Edit command" else "New command", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = draft.name,
            onValueChange = { n -> onChange(draft.copy(name = n, id = if (draft.idTouched) draft.id else RunCommandForm.idFor(n))) },
            label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(RUN_FORM_NAME_TAG),
        )
        OutlinedTextField(
            value = draft.id,
            onValueChange = { onChange(draft.copy(id = it, idTouched = true)) },
            label = { Text("Id") }, singleLine = true, readOnly = editing,
            supportingText = {
                Text(if (editing) "An edited command keeps its id." else "Derived from the name; you can change it.", fontSize = 11.sp)
            },
            modifier = Modifier.fillMaxWidth().testTag(RUN_FORM_ID_TAG),
        )
        OutlinedTextField(
            value = draft.argv,
            onValueChange = { onChange(draft.copy(argv = it)) },
            label = { Text("Arguments") }, minLines = 3,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
            supportingText = {
                Text(
                    "One argument per line, program first (e.g. gradlew.bat, then build). " +
                        "Not a shell command: no quoting, pipes or &&.",
                    fontSize = 11.sp,
                )
            },
            modifier = Modifier.fillMaxWidth().testTag(RUN_FORM_ARGV_TAG),
        )
        OutlinedTextField(
            value = draft.cwd,
            onValueChange = { onChange(draft.copy(cwd = it)) },
            label = { Text("Working dir (optional)") }, singleLine = true,
            supportingText = { Text("Relative to the repo, e.g. web. Blank = the repo root.", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth().testTag(RUN_FORM_CWD_TAG),
        )
        OutlinedTextField(
            value = draft.grace,
            onValueChange = { onChange(draft.copy(grace = it.filter(Char::isDigit))) },
            label = { Text("Grace ms") }, singleLine = true,
            supportingText = { Text("How long Stop waits before it kills the process tree.", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth().testTag(RUN_FORM_GRACE_TAG),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.detached, onCheckedChange = { onChange(draft.copy(detached = it)) }, enabled = enabled,
                modifier = Modifier.testTag(RUN_FORM_DETACHED_TAG),
            )
            Text("Detached (launch an app that stays open)", color = Palette.Muted, fontSize = 12.sp)
        }
        if (secretNames != null) {
            OutlinedTextField(
                value = draft.secrets,
                onValueChange = { onChange(draft.copy(secrets = it)) },
                label = { Text("Secrets (optional)") }, minLines = 1,
                textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
                supportingText = {
                    Text(
                        "One secret name per line; the run gets each of its fields as an environment variable, masked in its output. " +
                            (if (secretNames.isEmpty()) "Add secrets in Configuration > Secrets." else "Yours: ${secretNames.joinToString(", ")}."),
                        fontSize = 11.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag(RUN_FORM_SECRETS_TAG),
            )
        }
        error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(RUN_FORM_ERROR_TAG)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSave,
                enabled = enabled && draft.id.isNotBlank() && argv.isNotEmpty(),
                modifier = Modifier.testTag(RUN_FORM_SAVE_TAG),
            ) { Text("Save", fontSize = 12.sp) }
            TextButton(onClick = onCancel, modifier = Modifier.testTag(RUN_FORM_CANCEL_TAG)) { Text("Cancel", fontSize = 12.sp) }
        }
    }
}
