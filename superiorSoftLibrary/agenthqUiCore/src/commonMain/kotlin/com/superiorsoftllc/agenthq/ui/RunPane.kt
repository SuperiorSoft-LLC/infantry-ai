package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
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
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.CommandSpecView
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.model.runPills
import com.superiorsoftllc.agenthq.ui.net.Controller
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
const val RUN_FORM_SAVE_TAG = "run-form-save"
const val RUN_FORM_CANCEL_TAG = "run-form-cancel"
const val RUN_FORM_ERROR_TAG = "run-form-error"
const val RUN_PILLS_TAG = "run-pills"
const val RUN_INTERACTIVE_TAG = "run-interactive"

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

/** The editor's fields. [editingId] is null for a new command; an edited command keeps its id. */
private data class CommandDraft(
    val editingId: String? = null,
    val name: String = "",
    val id: String = "",
    val idTouched: Boolean = false,
    val argv: String = "",
    val cwd: String = "",
    val grace: String = RunCommandForm.DEFAULT_GRACE_MILLIS.toString(),
    val secrets: String = "",
) {
    companion object {
        fun of(c: CommandSpecView) = CommandDraft(
            editingId = c.id, name = c.name, id = c.id, idTouched = true,
            argv = c.argv.joinToString("\n"), cwd = c.cwd.orEmpty(),
            grace = (c.graceMillis ?: RunCommandForm.DEFAULT_GRACE_MILLIS).toString(),
            secrets = c.secrets.joinToString("\n"),
        )
    }
}

/**
 * In-app build run panel (design section 4): a command dropdown, Start, Stop and a scrolling monospace output
 * pane. Everything shown comes from [AppState.runs]; the local state is the picked command, the dropdown, and
 * the command editor.
 *
 * RUN-CMD-2: Add / Edit / Delete next to the picker. The editor sends `command_save`; the daemon's refusal shows
 * inline under the form, and the form closes once the new `commands` list arrives. Delete asks for an inline confirm
 * (never an OS dialog) and sends `command_delete`. Both are off while a run is in flight or the link is not live.
 * The button rows wrap and the form scrolls, so the compact (phone) layout fits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunPane(state: AppState, controller: Controller) {
    val scope = rememberCoroutineScope()
    val runs = state.runs
    val live = state.connection.isLive
    var pickedId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<CommandDraft?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The `commands` revision a save was sent against: a newer list with no refusal means it was saved.
    var savedAt by remember { mutableStateOf<Int?>(null) }
    // RUN-CONSOLE: the desktop opens a pill's console window; web and Android show its lines below instead.
    val console = LocalRunConsole.current
    var interactive by remember { mutableStateOf(false) }

    val picked = runs.commands.firstOrNull { it.id == pickedId } ?: runs.commands.firstOrNull()
    val current = runs.current
    // The run going now (one at a time), whichever pill is selected.
    val active = runs.runs.values.firstOrNull { it.state != "FINISHED" }
    val inFlight = active != null
    val canEdit = live && !inFlight
    // The pills' clock: ticks once a second while a run is going, so "running 0:42" counts.
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(inFlight) {
        now = Clock.System.now().toEpochMilliseconds()
        while (inFlight) { delay(1000); now = Clock.System.now().toEpochMilliseconds() }
    }

    // The controller clears the old refusal before it sends, so the first answer after the save decides:
    // a refusal keeps the form open (its reason shows inline), a new list closes it.
    LaunchedEffect(runs.commandsRevision) {
        val sent = savedAt ?: return@LaunchedEffect
        if (runs.commandsRevision > sent) {
            savedAt = null
            if (runs.commandError == null) draft = null
        }
    }

    fun act(request: suspend () -> CommandResult) {
        localError = null
        scope.launch {
            val r = request()
            if (r is CommandResult.Refused) localError = r.reason
        }
    }

    fun openNew() {
        draft = CommandDraft()
        confirmDelete = false
        localError = null
    }

    Column(Modifier.fillMaxSize().padding(12.dp).testTag(RUN_PANE_TAG)) {
        Text("RUN", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (runs.commands.isEmpty() && draft == null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text("No saved commands on this daemon.", color = Palette.Muted, fontSize = 12.sp)
                OutlinedButton(onClick = ::openNew, enabled = canEdit, modifier = Modifier.testTag(RUN_ADD_TAG)) {
                    Text("Add command", fontSize = 12.sp)
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = live && !inFlight && runs.commands.isNotEmpty(),
                    modifier = Modifier.testTag(RUN_COMMAND_PICKER_TAG),
                ) { Text(picked?.name ?: "Pick a command", fontSize = 12.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    runs.commands.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.name, fontSize = 12.sp) },
                            onClick = { pickedId = c.id; menuOpen = false; confirmDelete = false },
                            modifier = Modifier.testTag("run-command-${c.id}"),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    picked?.let { c ->
                        act { if (interactive) controller.startRun(c.id, interactive = true) else controller.startRun(c.id) }
                    }
                },
                enabled = live && !inFlight && picked != null,
                modifier = Modifier.testTag(RUN_START_TAG),
            ) { Text("Start", fontSize = 12.sp) }
            // Typing into a run needs a console to type in: the desktop only, on a 1.134.0 daemon.
            if (console != null && controller.supportsRunConsole()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = interactive, onCheckedChange = { interactive = it }, enabled = live && !inFlight,
                        modifier = Modifier.testTag(RUN_INTERACTIVE_TAG),
                    )
                    Text("Interactive", color = Palette.Muted, fontSize = 12.sp)
                }
            }
            OutlinedButton(
                onClick = { active?.let { r -> act { controller.stopRun(r.runId) } } },
                enabled = live && inFlight,
                modifier = Modifier.testTag(RUN_STOP_TAG),
            ) { Text("Stop", fontSize = 12.sp) }
            if (runs.commands.isNotEmpty()) {
                OutlinedButton(
                    onClick = ::openNew,
                    enabled = canEdit && draft == null,
                    modifier = Modifier.testTag(RUN_ADD_TAG),
                ) { Text("Add", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { picked?.let { draft = CommandDraft.of(it) }; confirmDelete = false; localError = null },
                    enabled = canEdit && draft == null && picked != null,
                    modifier = Modifier.testTag(RUN_EDIT_TAG),
                ) { Text("Edit", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = canEdit && draft == null && picked != null && !confirmDelete,
                    modifier = Modifier.testTag(RUN_DELETE_TAG),
                ) { Text("Delete", fontSize = 12.sp) }
            }
            current?.let { Text(it.finished?.outcome ?: it.state, color = Palette.Muted, fontSize = 11.sp) }
        }
        // RUN-CONSOLE: one pill per run, newest first. A click shows that run's lines below and, on the desktop,
        // opens its console window.
        val pills = runPills(runs, now)
        if (pills.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 6.dp).testTag(RUN_PILLS_TAG),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pills.forEach { p ->
                    RunPillChip(p, selected = p.runId == runs.currentRunId, onClick = {
                        controller.selectRun(p.runId)
                        console?.open(p.runId, controller)
                    })
                }
            }
        }
        if (confirmDelete && picked != null) {
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Delete \"${picked.name}\"?", color = Palette.Danger, fontSize = 12.sp)
                OutlinedButton(
                    onClick = { confirmDelete = false; act { controller.deleteCommand(picked.id) } },
                    enabled = canEdit,
                    modifier = Modifier.testTag(RUN_DELETE_CONFIRM_TAG),
                ) { Text("Delete", fontSize = 12.sp, color = Palette.Danger) }
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.testTag(RUN_DELETE_CANCEL_TAG)) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        }
        val d = draft
        if (d != null) {
            CommandEditor(
                draft = d,
                // SECRETS-2: the field shows only against a daemon that injects secrets (1.135.0).
                secretNames = if (controller.supportsKeyValueSecrets()) state.secrets?.entries?.map { it.name }.orEmpty() else null,
                enabled = canEdit && savedAt == null,
                error = runs.commandError ?: localError,
                onChange = { draft = it },
                onCancel = { draft = null; savedAt = null; localError = null },
                onSave = {
                    val grace = d.grace.trim().toLongOrNull()
                    if (grace == null) {
                        localError = "Grace ms must be a whole number of milliseconds."
                    } else {
                        localError = null
                        savedAt = runs.commandsRevision
                        scope.launch {
                            val r = controller.saveCommand(
                                d.id.trim(), d.name.trim(), RunCommandForm.argvOf(d.argv), RunCommandForm.cwdOf(d.cwd), grace,
                                RunCommandForm.secretsOf(d.secrets),
                            )
                            if (r is CommandResult.Refused) { localError = r.reason; savedAt = null }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        (runs.commandError ?: localError)?.let {
            Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        current?.gap?.let {
            Text("Output gap: expected line ${it.expected}, got ${it.got}. Later lines are not shown.", color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        current?.takeIf { it.droppedLines > 0 }?.let {
            Text("${it.droppedLines} earlier lines hidden", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        val lines = current?.lines.orEmpty()
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).background(Palette.Panel).padding(8.dp).testTag(RUN_OUTPUT_TAG),
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
