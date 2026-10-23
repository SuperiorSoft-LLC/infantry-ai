package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AGENT_SCOPE_APPLIES_TO
import com.superiorsoftllc.agenthq.ui.model.AgentScope
import com.superiorsoftllc.agenthq.ui.model.CliLocationsSetting
import com.superiorsoftllc.agenthq.ui.model.HarnessInstallSetting
import com.superiorsoftllc.agenthq.ui.protocol.CliLocation
import com.superiorsoftllc.agenthq.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.agenthq.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.agenthq.ui.model.ToolApprovalSetting
import com.superiorsoftllc.agenthq.ui.protocol.ToolApprovalInfo
import com.superiorsoftllc.agenthq.ui.model.FLAGS_LIFETIME_NOTE
import com.superiorsoftllc.agenthq.ui.model.LifecycleSetting
import com.superiorsoftllc.agenthq.ui.model.UiFlags

/**
 * CONFIG: the configuration modal opened from the footer cog. Each flag is a Material 3 control, its SCOPE is
 * printed above it and its EFFECT under it. A change goes straight to [onFlags]; the flags live in the window,
 * so they are remembered until the window closes (see [FLAGS_LIFETIME_NOTE]).
 */
@Composable
fun ConfigDialog(
    flags: UiFlags,
    onFlags: (UiFlags) -> Unit,
    onDismiss: () -> Unit,
    keepAliveMinutes: Int? = null,
    onKeepAliveMinutes: ((Int) -> Unit)? = null,
    /** CLIPATH-1: the daemon's CLI locations (null = not read yet / an older daemon); [onCliLocation] null hides the section. */
    cliLocations: List<CliLocation>? = null,
    /** CLIPATH-1: save CLI `id`'s path; a null path clears it back to the default. */
    onCliLocation: ((id: String, path: String?) -> Unit)? = null,
    /** HARNESS-INSTALL-1: the daemon's own check of its harness CLIs (null = older daemon: no install controls), the install state, and its log. */
    harnessInstall: HarnessInstallReport? = null,
    harnessInstallBusy: Boolean = false,
    harnessInstallLog: List<String> = emptyList(),
    onHarnessCheck: ((checkLatest: Boolean) -> Unit)? = null,
    onHarnessInstall: ((ids: List<String>, update: Boolean) -> Unit)? = null,
    /** CLIPATH-1: a platform file picker (title -> chosen path, null = cancelled); null = no Browse button on this platform. */
    onBrowse: ((title: String) -> String?)? = null,
    /** BUG-PERM-1: the daemon's global Tool approval (null = not read yet / an older daemon). */
    toolApproval: ToolApprovalInfo? = null,
    /** BUG-PERM-1: pick a mode; null hides the section. */
    onToolApproval: ((String) -> Unit)? = null,
    /** PKG-3: the installed app's log folder; null (dev, Android) hides the Logs section. */
    logs: LogsFolder? = null,
    /** UPDATE-1: the installed app's version and "Check for updates"; null (dev, Android) hides the Updates section. */
    updates: UpdatesEntry? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(FLAGS_LIFETIME_NOTE, color = Palette.Muted, fontSize = 11.sp)
                FlagSection(title = "Agent scope", applies = AGENT_SCOPE_APPLIES_TO) {
                    AgentScopeDropdown(flags.agentScope) { onFlags(flags.copy(agentScope = it)) }
                }
                // BUG-M2-11e: daemon state, not a window flag: it is stored by the daemon and survives a restart.
                if (onKeepAliveMinutes != null) {
                    FlagSection(title = LifecycleSetting.TITLE, applies = LifecycleSetting.APPLIES_TO) {
                        KeepAliveDropdown(keepAliveMinutes, onKeepAliveMinutes)
                    }
                }
                // CLIPATH-1: daemon state too: the daemon validates and stores each path, and says where each CLI resolved.
                if (onCliLocation != null) {
                    FlagSection(title = CliLocationsSetting.TITLE, applies = CliLocationsSetting.APPLIES_TO) {
                        CliLocationsSection(cliLocations, onCliLocation, onBrowse, harnessInstall, harnessInstallBusy, onHarnessInstall)
                        if (harnessInstall != null && onHarnessCheck != null && onHarnessInstall != null) {
                            HarnessInstallControls(harnessInstall, harnessInstallBusy, harnessInstallLog, onHarnessCheck, onHarnessInstall)
                        }
                    }
                }
                // BUG-PERM-1: daemon state: no harness can relay an approval prompt yet, so this decides what tools may run.
                if (onToolApproval != null) {
                    FlagSection(title = ToolApprovalSetting.TITLE, applies = ToolApprovalSetting.APPLIES_TO) {
                        ToolApprovalDropdown(toolApproval, onToolApproval)
                    }
                }
                // PKG-3: where the installed app writes its log, so a Start-menu launch (no console) can still report a problem.
                if (logs != null) {
                    FlagSection(title = LogsFolder.TITLE, applies = LogsFolder.APPLIES_TO) {
                        LogsSection(logs)
                    }
                }
                // UPDATE-1: the check itself runs in the daemon side's updater; this only asks for it.
                if (updates != null) {
                    FlagSection(title = UpdatesEntry.TITLE, applies = UpdatesEntry.APPLIES_TO) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Running ${updates.version}", fontSize = 12.sp, modifier = Modifier.testTag(UPDATES_VERSION_TAG))
                            OutlinedButton(onClick = updates.check, modifier = Modifier.testTag(UPDATES_CHECK_TAG)) { Text("Check for updates") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun FlagSection(title: String, applies: String, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(applies, color = Palette.Muted, fontSize = 11.sp)
        control()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentScopeDropdown(value: AgentScope, onPick: (AgentScope) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).handCursor(overrideDescendants = true).fillMaxWidth().testTag("agent-scope-field"),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AgentScope.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { onPick(option); expanded = false },
                        modifier = Modifier.testTag("agent-scope-option-${option.name}"),
                    )
                }
            }
        }
        Text(value.effect, color = Palette.Muted, fontSize = 11.sp)
    }
}

/**
 * BUG-M2-11e: the `session.keepAliveMinutes` control. [value] null = the daemon has not answered (or has no such setting):
 * the field says so and offers no choice, so a guessed value is never shown as the daemon's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeepAliveDropdown(value: Int?, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && value != null }) {
        OutlinedTextField(
            value = value?.let(LifecycleSetting::label) ?: "Not read from the daemon",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = value != null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).handCursor(overrideDescendants = true).fillMaxWidth().testTag(KEEP_ALIVE_FIELD_TAG),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LifecycleSetting.options(value).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(LifecycleSetting.label(minutes)) },
                    onClick = { expanded = false; if (minutes != value) onPick(minutes) },
                    modifier = Modifier.testTag(KEEP_ALIVE_OPTION_TAG_PREFIX + minutes),
                )
            }
        }
    }
}

/**
 * CLIPATH-1: one row per CLI: where the daemon runs it from (path + source, or "not found" with the paths it tried), a
 * path field, Browse (where the platform has a file picker), Save and Clear to default. The row shows the DAEMON's answer:
 * the text field is only what will be sent.
 */
@Composable
private fun CliLocationsSection(
    locations: List<CliLocation>?,
    onSave: (id: String, path: String?) -> Unit,
    onBrowse: ((title: String) -> String?)?,
    harness: HarnessInstallReport?,
    harnessBusy: Boolean,
    onInstall: ((ids: List<String>, update: Boolean) -> Unit)?,
) {
    if (locations == null) {
        Text("Not read from the daemon", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(CLI_LOCATIONS_PENDING_TAG))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        locations.forEach { location -> CliLocationRow(location, onSave, onBrowse, harness?.harnesses?.firstOrNull { it.id == location.id }, harnessBusy, onInstall) }
    }
}

@Composable
private fun CliLocationRow(
    location: CliLocation,
    onSave: (id: String, path: String?) -> Unit,
    onBrowse: ((title: String) -> String?)?,
    check: HarnessInstallStatus?,
    busy: Boolean,
    onInstall: ((ids: List<String>, update: Boolean) -> Unit)?,
) {
    // Re-seeded whenever the daemon reports a different override, so a save or clear elsewhere shows here.
    var text by remember(location.id, location.override) { mutableStateOf(CliLocationsSetting.initialText(location)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(location.label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            CliLocationsSetting.status(location),
            color = if (location.path == null) Palette.Muted else androidx.compose.ui.graphics.Color.Unspecified,
            fontSize = 11.sp,
            modifier = Modifier.testTag(CLI_STATUS_TAG_PREFIX + location.id),
        )
        if (check != null && check.id in HarnessInstallSetting.INSTALLABLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(HarnessInstallSetting.statusText(check), fontSize = 11.sp, modifier = Modifier.testTag(HARNESS_STATE_TAG_PREFIX + check.id))
                val label = HarnessInstallSetting.action(check)
                if (label != null && onInstall != null) {
                    TextButton(
                        onClick = { onInstall(listOf(check.id), HarnessInstallSetting.isUpdate(check)) },
                        enabled = !busy,
                        modifier = Modifier.testTag(HARNESS_INSTALL_TAG_PREFIX + check.id),
                    ) { Text(label) }
                }
            }
        }
        CliLocationsSetting.triedLines(location).forEach { tried ->
            Text("tried: $tried", color = Palette.Muted, fontSize = 10.sp)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("Absolute path to the ${location.label} executable", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth().testTag(CLI_FIELD_TAG_PREFIX + location.id),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onBrowse != null) {
                TextButton(
                    onClick = { onBrowse("Locate ${location.label}")?.let { text = it } },
                    modifier = Modifier.testTag(CLI_BROWSE_TAG_PREFIX + location.id),
                ) { Text("Browse...") }
            }
            val toSave = CliLocationsSetting.pathToSave(text)
            TextButton(
                onClick = { onSave(location.id, toSave) },
                enabled = toSave != null && toSave != location.override,
                modifier = Modifier.testTag(CLI_SAVE_TAG_PREFIX + location.id),
            ) { Text("Save") }
            TextButton(
                onClick = { text = ""; onSave(location.id, null) },
                enabled = location.override != null,
                modifier = Modifier.testTag(CLI_CLEAR_TAG_PREFIX + location.id),
            ) { Text("Clear to default") }
        }
    }
}

/**
 * PKG-3: the installed app's log folder, shown in Configuration > Logs. [open] asks the platform to show it in its
 * file manager (desktop only, through `PlatformHost.openFolder`) and says whether it could.
 */
class LogsFolder(val path: String, val open: () -> Boolean) {
    companion object {
        const val TITLE = "Logs"
        const val APPLIES_TO = "This app's log files (newest is agenthq.log). Attach them to a bug report"
        const val OPEN_FAILED = "Could not open the folder here; open the path above in Explorer"
    }
}

@Composable
private fun LogsSection(logs: LogsFolder) {
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(logs.path, fontSize = 12.sp, modifier = Modifier.testTag(LOGS_PATH_TAG))
        }
        OutlinedButton(onClick = { failed = !logs.open() }, modifier = Modifier.testTag(LOGS_OPEN_TAG)) { Text("Open logs folder") }
        if (failed) Text(LogsFolder.OPEN_FAILED, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(LOGS_OPEN_FAILED_TAG))
    }
}

/** UPDATE-1: Configuration > Updates. [check] asks the installed app's updater to look for a newer release now. */
class UpdatesEntry(val version: String, val check: () -> Unit) {
    companion object {
        const val TITLE = "Updates"
        const val APPLIES_TO = "New AgentHQ releases on GitHub. Also checked at every start"
    }
}

const val UPDATES_VERSION_TAG = "updates-version"
const val UPDATES_CHECK_TAG = "updates-check"
const val LOGS_PATH_TAG = "logs-path"
const val LOGS_OPEN_TAG = "logs-open"
const val LOGS_OPEN_FAILED_TAG = "logs-open-failed"

const val CLI_LOCATIONS_PENDING_TAG = "cli-locations-pending"
const val CLI_STATUS_TAG_PREFIX = "cli-status-"
const val CLI_FIELD_TAG_PREFIX = "cli-path-field-"
const val CLI_BROWSE_TAG_PREFIX = "cli-browse-"
const val CLI_SAVE_TAG_PREFIX = "cli-save-"
const val CLI_CLEAR_TAG_PREFIX = "cli-clear-"

/**
 * BUG-PERM-1: the Tool approval control. [value] null = the daemon has not answered (or is older than 1.76.0): the field
 * says so and offers no choice. Under it: what a run gets now, and where that came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolApprovalDropdown(value: ToolApprovalInfo?, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && value != null }) {
            OutlinedTextField(
                value = value?.let { ToolApprovalSetting.label(ToolApprovalSetting.shown(it)) } ?: "Not read from the daemon",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                enabled = value != null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).handCursor(overrideDescendants = true).fillMaxWidth().testTag(TOOL_APPROVAL_FIELD_TAG),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ToolApprovalSetting.options(value).forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(ToolApprovalSetting.label(mode)) },
                        onClick = { onPick(mode); expanded = false },
                        modifier = Modifier.testTag(TOOL_APPROVAL_OPTION_TAG_PREFIX + mode),
                    )
                }
            }
        }
        if (value != null) Text(ToolApprovalSetting.status(value), color = Palette.Muted, fontSize = 11.sp)
    }
}

const val TOOL_APPROVAL_FIELD_TAG = "tool-approval-field"
const val TOOL_APPROVAL_OPTION_TAG_PREFIX = "tool-approval-option-"

const val KEEP_ALIVE_FIELD_TAG = "keep-alive-field"
const val KEEP_ALIVE_OPTION_TAG_PREFIX = "keep-alive-option-"

const val HARNESS_STATE_TAG_PREFIX = "harness-state-"
const val HARNESS_INSTALL_TAG_PREFIX = "harness-install-"
const val HARNESS_CHECK_TAG = "harness-check"
const val HARNESS_INSTALL_ALL_TAG = "harness-install-all"
const val HARNESS_LOG_TAG = "harness-log"

/**
 * HARNESS-INSTALL-1: check / install-all buttons, Node/npm guidance, and the last install's log. Everything runs on the
 * DAEMON's machine; this only sends the request and shows the answer.
 */
@Composable
private fun HarnessInstallControls(
    report: HarnessInstallReport,
    busy: Boolean,
    log: List<String>,
    onCheck: (checkLatest: Boolean) -> Unit,
    onInstall: (ids: List<String>, update: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Harness CLIs install on the machine the daemon runs on, into ${report.prefixDir}. Sign-in stays with each CLI.", color = Palette.Muted, fontSize = 10.sp)
        report.guidance?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onCheck(true) }, enabled = !busy, modifier = Modifier.testTag(HARNESS_CHECK_TAG)) { Text("Check for updates") }
            val needed = report.harnesses.filter { HarnessInstallSetting.action(it) != null }
            TextButton(
                onClick = { onInstall(emptyList(), true) },
                enabled = !busy && needed.isNotEmpty() && report.npmPath != null,
                modifier = Modifier.testTag(HARNESS_INSTALL_ALL_TAG),
            ) { Text(if (busy) "Working..." else "Install / update all") }
        }
        if (log.isNotEmpty()) {
            Text(log.joinToString("\n"), color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.testTag(HARNESS_LOG_TAG))
        }
    }
}
