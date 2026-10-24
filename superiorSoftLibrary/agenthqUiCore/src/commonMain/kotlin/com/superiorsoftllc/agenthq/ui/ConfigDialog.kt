package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.Alignment
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
import com.superiorsoftllc.agenthq.ui.protocol.SecretRow
import com.superiorsoftllc.agenthq.ui.protocol.SecretState
import com.superiorsoftllc.agenthq.ui.model.ToolApprovalSetting
import com.superiorsoftllc.agenthq.ui.protocol.ToolApprovalInfo
import com.superiorsoftllc.agenthq.ui.model.FLAGS_LIFETIME_NOTE
import com.superiorsoftllc.agenthq.ui.model.LifecycleSetting
import com.superiorsoftllc.agenthq.ui.model.UserPortrait
import com.superiorsoftllc.agenthq.ui.model.UiFlags
import com.superiorsoftllc.agenthq.ui.model.ThemeAccent
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings

/** PAIR-1: the connection "Pair a device" shares ([url], [token]) and what a link scanned or pasted there does. */
class PairEntry(val url: String, val token: String?, val name: String?, val onLink: (com.superiorsoftllc.agenthq.ui.pair.PairingLink) -> Unit) {
    companion object {
        const val TITLE = "Pair a device"
        const val APPLIES_TO = "This connection; a link carries the server address and its token."
    }
}

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
    /** USER-PORTRAIT: the daemon's stored portrait (base64, null = default) and the save callback (null png = reset); null hides the section. */
    userPortrait: Pair<String?, (ByteArray?) -> Unit>? = null,
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
    /** SECRETS-1: the daemon keystore's state (null = not read yet / an older daemon); [secretActions] null hides the section. */
    secrets: SecretState? = null,
    secretActions: SecretActions? = null,
    /** PAIR-1: the connection to share and the callback for a scanned / pasted link; null hides "Pair a device". */
    pair: PairEntry? = null,
    /** ROLE-EDIT-1: opens the role manager dialog; null hides the section. */
    onOpenRoles: (() -> Unit)? = null,
    /** SERVER-UPDATE-4: the connected server's version and update state, and the client/server mismatch warning; null hides the section. */
    serverUpdate: com.superiorsoftllc.agenthq.ui.model.ServerUpdateState? = null,
    serverMismatch: String? = null,
    /** THEME-1: the current theme and the callback that applies and stores a new one; null hides the section. */
    theme: ThemeSettings? = null,
    onTheme: ((ThemeSettings) -> Unit)? = null,
    /** BUILD-STAMP-1: this app's and the daemon's build strings; null hides the About section. */
    about: AboutEntry? = null,
    /** CONFIG-TABS-1: the tab to open on (the host remembers the last one); falls back to the first tab with something to show. */
    initialTab: ConfigTab? = null,
    /** CONFIG-TABS-1: told when the user picks another tab, so the host can reopen there. */
    onTab: ((ConfigTab) -> Unit)? = null,
) {
    // VOICE-PLUGIN-1: the host's installable voice plugins (desktop); null = no Plugins tab.
    val voicePlugins = com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost.current.voicePlugins
    // CONFIG-TABS-1: which sections this host has; a tab with none of them is not offered.
    val present = buildSet {
        if (theme != null && onTheme != null) add(ConfigEntry.THEME)
        add(ConfigEntry.BLUR)
        if (userPortrait != null) add(ConfigEntry.USER_PORTRAIT)
        if (about != null) add(ConfigEntry.ABOUT)
        add(ConfigEntry.AGENT_SCOPE)
        add(ConfigEntry.PROGRESS_STALE)
        if (onKeepAliveMinutes != null) add(ConfigEntry.LIFECYCLE)
        if (onCliLocation != null) add(ConfigEntry.CLI_LOCATIONS)
        if (onToolApproval != null) add(ConfigEntry.TOOL_APPROVAL)
        if (onOpenRoles != null) add(ConfigEntry.ROLES)
        add(ConfigEntry.VOICE)
        if (voicePlugins != null) add(ConfigEntry.PLUGINS)
        if (secretActions != null && secrets != null) add(ConfigEntry.SECRETS)
        if (pair != null) add(ConfigEntry.PAIR)
        if (updates != null) add(ConfigEntry.UPDATES)
        if (serverUpdate != null) add(ConfigEntry.SERVER_UPDATE)
        if (logs != null) add(ConfigEntry.LOGS)
    }
    val tabs = visibleConfigTabs(present)
    var wanted by remember { mutableStateOf(initialTab) }
    val tab = pickConfigTab(wanted, present)
    // CONFIG-SEARCH-1: a non-empty query shows matching sections instead of the tab body; picking one jumps to its
    // tab and briefly outlines it (highlight), scrolled into view.
    var query by remember { mutableStateOf("") }
    var highlight by remember { mutableStateOf<ConfigEntry?>(null) }
    androidx.compose.runtime.LaunchedEffect(highlight) {
        if (highlight != null) { kotlinx.coroutines.delay(CONFIG_HIGHLIGHT_MS); highlight = null }
    }
    val results = searchConfig(query, present)
    // A section shows when its tab is the selected one and this host has it.
    val shows: (ConfigEntry) -> Boolean = { it.tab == tab && it in present }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        // CONFIG-TABS-1: one width whatever the tab, so switching tabs does not resize the dialog.
        modifier = Modifier.fillMaxWidth(),
        title = { Text("Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(FLAGS_LIFETIME_NOTE, color = Palette.Muted, fontSize = 11.sp)
                ConfigSearchField(query) { query = it }
                ConfigTabRow(tabs, tab) { picked -> query = ""; wanted = picked; onTab?.invoke(picked) }
                if (query.isNotBlank()) {
                    ConfigSearchResults(results) { e -> query = ""; wanted = e.tab; onTab?.invoke(e.tab); highlight = e }
                } else
                // Each tab scrolls on its own, from the top: the key gives each tab a fresh scroll state.
                androidx.compose.runtime.key(tab) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 480.dp).verticalScroll(rememberScrollState()).testTag(CONFIG_TAB_BODY_TAG),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                            if (shows(ConfigEntry.THEME) && theme != null && onTheme != null) {
                                FlagSection(ConfigEntry.THEME, highlight) { ThemeSection(theme, onTheme) }
                            }
                            if (shows(ConfigEntry.BLUR)) FlagSection(ConfigEntry.BLUR, highlight) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    androidx.compose.material3.Switch(
                                        checked = flags.blurBehindDialogs,
                                        onCheckedChange = { onFlags(flags.copy(blurBehindDialogs = it)) },
                                        modifier = Modifier.testTag(BLUR_SWITCH_TAG),
                                    )
                                    Text(if (flags.blurBehindDialogs) "On" else "Off", fontSize = 12.sp)
                                }
                            }
                            if (shows(ConfigEntry.USER_PORTRAIT) && userPortrait != null) {
                                FlagSection(ConfigEntry.USER_PORTRAIT, highlight) { UserPortraitSection(userPortrait.first, userPortrait.second) }
                            }
                            if (shows(ConfigEntry.AGENT_SCOPE)) FlagSection(ConfigEntry.AGENT_SCOPE, highlight) {
                                AgentScopeDropdown(flags.agentScope) { onFlags(flags.copy(agentScope = it)) }
                            }
                            if (shows(ConfigEntry.PROGRESS_STALE)) FlagSection(ConfigEntry.PROGRESS_STALE, highlight) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    com.superiorsoftllc.agenthq.ui.model.PROGRESS_STALE_CHOICES.forEach { min ->
                                        val picked = flags.progressStaleMin == min
                                        OutlinedButton(
                                            onClick = { onFlags(flags.copy(progressStaleMin = min)) },
                                            modifier = Modifier.testTag(PROGRESS_STALE_CHOICE_TAG_PREFIX + min),
                                        ) { Text("$min min", fontSize = 11.sp, color = if (picked) Palette.Accent else Palette.Text, fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal) }
                                    }
                                }
                            }
                            // BUG-M2-11e: daemon state, not a window flag: it is stored by the daemon and survives a restart.
                            if (shows(ConfigEntry.LIFECYCLE) && onKeepAliveMinutes != null) {
                                FlagSection(ConfigEntry.LIFECYCLE, highlight) { KeepAliveDropdown(keepAliveMinutes, onKeepAliveMinutes) }
                            }
                            // CLIPATH-1: daemon state too: the daemon validates and stores each path, and says where each CLI resolved.
                            if (shows(ConfigEntry.CLI_LOCATIONS) && onCliLocation != null) {
                                FlagSection(ConfigEntry.CLI_LOCATIONS, highlight) {
                                    CliLocationsSection(cliLocations, onCliLocation, onBrowse, harnessInstall, harnessInstallBusy, onHarnessInstall)
                                    if (harnessInstall != null && onHarnessCheck != null && onHarnessInstall != null) {
                                        HarnessInstallControls(harnessInstall, harnessInstallBusy, harnessInstallLog, onHarnessCheck, onHarnessInstall)
                                    }
                                }
                            }
                            // BUG-PERM-1: daemon state: no harness can relay an approval prompt yet, so this decides what tools may run.
                            if (shows(ConfigEntry.TOOL_APPROVAL) && onToolApproval != null) {
                                FlagSection(ConfigEntry.TOOL_APPROVAL, highlight) { ToolApprovalDropdown(toolApproval, onToolApproval) }
                            }
                            // ROLE-EDIT-1: the role manager is its own dialog (shared modal).
                            if (shows(ConfigEntry.ROLES) && onOpenRoles != null) {
                                FlagSection(ConfigEntry.ROLES, highlight) {
                                    OutlinedButton(onClick = onOpenRoles, modifier = Modifier.testTag(ROLE_MANAGER_OPEN_TAG)) { Text("Manage roles") }
                                }
                            }
                            if (shows(ConfigEntry.VOICE)) FlagSection(ConfigEntry.VOICE, highlight) {
                                com.superiorsoftllc.agenthq.ui.voice.VoiceSettingsSection(flags.voice) { onFlags(flags.copy(voice = it)) }
                            }
                            if (shows(ConfigEntry.PLUGINS) && voicePlugins != null) FlagSection(ConfigEntry.PLUGINS, highlight) {
                                com.superiorsoftllc.agenthq.ui.voice.plugin.PluginsSection(voicePlugins)
                            }
                            // SECRETS-1: the daemon owns the secrets; this only sends "set / clear / import" and shows set / not set.
                            if (shows(ConfigEntry.SECRETS) && secretActions != null && secrets != null) {
                                FlagSection(ConfigEntry.SECRETS, highlight) { SecretsSection(secrets, secretActions) }
                            }
                            // PAIR-1: show this connection as a QR / link, or connect to another server from one.
                            if (shows(ConfigEntry.PAIR) && pair != null) {
                                FlagSection(ConfigEntry.PAIR, highlight) {
                                    com.superiorsoftllc.agenthq.ui.pair.PairDeviceSection(pair.url, pair.token, pair.name, pair.onLink)
                                }
                            }
                            // BUILD-STAMP-1: first in About, it answers "which build am I running?".
                            if (shows(ConfigEntry.ABOUT) && about != null) {
                                FlagSection(ConfigEntry.ABOUT, highlight) { AboutSection(about) }
                            }
                            // UPDATE-1: the check itself runs in the daemon side's updater; this only asks for it.
                            if (shows(ConfigEntry.UPDATES) && updates != null) {
                                FlagSection(ConfigEntry.UPDATES, highlight) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Running ${updates.version}", fontSize = 12.sp, modifier = Modifier.testTag(UPDATES_VERSION_TAG))
                                        OutlinedButton(onClick = updates.check, modifier = Modifier.testTag(UPDATES_CHECK_TAG)) { Text("Check for updates") }
                                    }
                                }
                            }
                            // SERVER-UPDATE-4: the daemon's own version and updater, over the existing connection.
                            if (shows(ConfigEntry.SERVER_UPDATE) && serverUpdate != null) {
                                FlagSection(ConfigEntry.SERVER_UPDATE, highlight) { ServerUpdateSection(serverUpdate, serverMismatch) }
                            }
                            // PKG-3: where the installed app writes its log, so a Start-menu launch (no console) can still report a problem.
                            if (shows(ConfigEntry.LOGS) && logs != null) {
                                FlagSection(ConfigEntry.LOGS, highlight) { LogsSection(logs) }
                            }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * CONFIG-TABS-1: the tab strip. Scrollable, so a phone can swipe through tabs that do not fit;
 * each tab carries CONFIG_TAB_TAG_PREFIX + its name.
 */
@Composable
private fun ConfigTabRow(tabs: List<ConfigTab>, selected: ConfigTab?, onPick: (ConfigTab) -> Unit) {
    if (tabs.isEmpty()) return
    val index = tabs.indexOf(selected).coerceAtLeast(0)
    androidx.compose.material3.PrimaryScrollableTabRow(
        selectedTabIndex = index,
        edgePadding = 0.dp,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = Palette.Accent,
        modifier = Modifier.fillMaxWidth().testTag(CONFIG_TAB_ROW_TAG),
    ) {
        tabs.forEach { t ->
            val on = t == selected
            androidx.compose.material3.Tab(
                selected = on,
                onClick = { onPick(t) },
                modifier = Modifier.testTag(CONFIG_TAB_TAG_PREFIX + t.name),
                text = {
                    Text(
                        t.label, fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Palette.Accent else Palette.Muted,
                    )
                },
            )
        }
    }
}

/**
 * CONFIG-TABS-1: a registered section: its title and applies-to copy come from [entry], and it carries a test tag.
 * CONFIG-SEARCH-1: when it is the [highlight] (a search result just picked), it is outlined in the accent and
 * scrolled into view.
 */
@Composable
private fun FlagSection(entry: ConfigEntry, highlight: ConfigEntry?, control: @Composable () -> Unit) {
    val lit = entry == highlight
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(lit) { if (lit) requester.bringIntoView() }
    val mod = Modifier.testTag(CONFIG_SECTION_TAG_PREFIX + entry.name)
        .bringIntoViewRequester(requester)
        .then(if (lit) Modifier.border(2.dp, Palette.Accent, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(8.dp) else Modifier)
        .semantics { if (lit) stateDescription = CONFIG_HIGHLIGHT_STATE }
    FlagSection(entry.title, entry.applies, mod, control)
}

/** CONFIG-SEARCH-1: the search box above the tabs. */
@Composable
private fun ConfigSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text("Search settings", fontSize = 12.sp) },
        trailingIcon = if (query.isEmpty()) null else {
            { Text("✕", color = Palette.Muted, modifier = Modifier.clickable(role = Role.Button) { onQuery("") }.semantics { contentDescription = "Clear search" }.padding(8.dp)) }
        },
        modifier = Modifier.fillMaxWidth().testTag(CONFIG_SEARCH_TAG).semantics { contentDescription = "Search settings" },
    )
}

/** CONFIG-SEARCH-1: the sections matching the query, each with its tab; picking one opens it. */
@Composable
private fun ConfigSearchResults(results: List<ConfigEntry>, onPick: (ConfigEntry) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 480.dp).verticalScroll(rememberScrollState()).testTag(CONFIG_SEARCH_RESULTS_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (results.isEmpty()) {
            Text("No setting matches.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp).testTag(CONFIG_SEARCH_EMPTY_TAG))
        }
        results.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(e) }.padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag(CONFIG_RESULT_TAG_PREFIX + e.name),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(e.applies, color = Palette.Muted, fontSize = 11.sp, maxLines = 2)
                }
                Text(
                    e.tab.label, fontSize = 10.sp, color = Palette.Accent,
                    modifier = Modifier.border(1.dp, Palette.Accent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FlagSection(title: String, applies: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

const val SECRETS_TITLE = "Secrets"
const val SECRETS_APPLIES_TO = "Stored encrypted by the Infantry AI daemon. A saved secret is never shown again, only whether it is set"

/** SECRETS-1: what the Secrets section can ask of the daemon. */
class SecretActions(val set: (name: String, value: String) -> Unit, val clear: (name: String) -> Unit, val import: (name: String) -> Unit)

/** The text a row shows: set (with its masked tail) or not set. Never a value. */
fun secretStatusText(row: SecretRow): String = when {
    row.overriddenByEnv -> "Set (the environment variable overrides it)"
    row.set -> "Set ${row.tail}".trim()
    else -> "Not set"
}

@Composable
private fun SecretsSection(state: SecretState, actions: SecretActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in state.secrets) {
            var draft by remember { mutableStateOf("") }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(secretStatusText(row), fontSize = 12.sp, modifier = Modifier.testTag("$SECRET_STATUS_TAG-${row.name}"))
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true,
                    label = { Text(if (row.set) "Replace with" else "Paste it here") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("$SECRET_INPUT_TAG-${row.name}"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.set(row.name, draft); draft = "" }, enabled = draft.isNotBlank(),
                        modifier = Modifier.testTag("$SECRET_SAVE_TAG-${row.name}"),
                    ) { Text(if (row.set) "Replace" else "Save") }
                    if (row.set) OutlinedButton(onClick = { actions.clear(row.name) }, modifier = Modifier.testTag("$SECRET_DELETE_TAG-${row.name}")) { Text("Delete") }
                    if (row.importable) OutlinedButton(onClick = { actions.import(row.name) }, modifier = Modifier.testTag("$SECRET_IMPORT_TAG-${row.name}")) {
                        Text("Import from environment")
                    }
                }
            }
        }
        Text("Protected by: ${protectionText(state.protection)}", color = Palette.Muted, fontSize = 11.sp)
    }
}

private fun protectionText(wire: String): String = when (wire) {
    "os" -> "the operating system's credential facility"
    "external-key" -> "a key supplied to the daemon (AGENTHQ_KEYSTORE_KEY)"
    "key-file" -> "a key file in the daemon's state folder (weaker: set AGENTHQ_KEYSTORE_KEY on a shared machine)"
    else -> "unknown"
}

const val SECRET_STATUS_TAG = "secret-status"
const val SECRET_INPUT_TAG = "secret-input"
const val SECRET_SAVE_TAG = "secret-save"
const val SECRET_DELETE_TAG = "secret-delete"
const val SECRET_IMPORT_TAG = "secret-import"

/** UPDATE-1: Configuration > Updates. [check] asks the installed app's updater to look for a newer release now. */
class UpdatesEntry(val version: String, val check: () -> Unit) {
    companion object {
        const val TITLE = "Updates"
        const val APPLIES_TO = "New Infantry AI releases on GitHub. Also checked at every start"
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

const val PORTRAIT_CHANGE_TAG = "portrait-change"
const val PORTRAIT_RESET_TAG = "portrait-reset"
const val PORTRAIT_ERROR_TAG = "portrait-error"
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

/**
 * USER-PORTRAIT: the current portrait as a circle (the same crop chat uses), "Change portrait" (a png / jpeg / webp up to
 * 5 MB, centre-cropped to a square and downscaled to 256 px before it is saved) and "Reset to default". A refused file
 * says why, in words, and changes nothing.
 */
@Composable
private fun UserPortraitSection(portrait: String?, onSave: (ByteArray?) -> Unit) {
    val host = com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost.current
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        UserAvatar(size = 64, portrait = portrait)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        val path = host.pickFile("Choose your portrait") ?: return@OutlinedButton
                        val size = host.fileSize(path)
                        val bytes = if (size != null && size > UserPortrait.MAX_PICK_BYTES) null else host.readBytes(path)
                        val refusal = UserPortrait.refusal(bytes, size ?: bytes?.size?.toLong() ?: 0)
                        val png = if (refusal == null) host.portraitPng(bytes!!, UserPortrait.SIDE) else null
                        error = refusal ?: if (png == null) "That image could not be read." else null
                        if (png != null) onSave(png)
                    },
                    enabled = host.canPickFiles,
                    modifier = Modifier.testTag(PORTRAIT_CHANGE_TAG),
                ) { Text("Change portrait") }
                androidx.compose.material3.TextButton(
                    onClick = { error = null; onSave(null) },
                    enabled = portrait != null,
                    modifier = Modifier.testTag(PORTRAIT_RESET_TAG),
                ) { Text("Reset to default") }
            }
            if (!host.canPickFiles) Text("Choose a portrait from the desktop app; it shows here too.", color = Palette.Muted, fontSize = 11.sp)
            error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(PORTRAIT_ERROR_TAG)) }
        }
    }
}

const val BLUR_SWITCH_TAG = "blur-behind-dialogs-switch"
const val PROGRESS_STALE_CHOICE_TAG_PREFIX = "progress-stale-choice-"
const val BLUR_APPLIES_TO = "Applies to: every dialog. On blurs the window behind it; off dims it instead. Android before 12 always dims. Remembered across launches."

const val THEME_ACCENT_TAG_PREFIX = "theme-accent-"
const val THEME_DARK_SWITCH_TAG = "theme-dark-switch"
const val THEME_RESET_TAG = "theme-reset"

/**
 * THEME-1: accent presets as swatches (a radio group: the chosen one is ringed and announced as selected), a dark / light
 * switch, and "Reset to default" (amber on dark). Each pick goes straight to [onPick], which applies it live.
 */
@Composable
private fun ThemeSection(theme: ThemeSettings, onPick: (ThemeSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeAccent.entries.forEach { accent ->
                AccentSwatch(accent, selected = accent == theme.accent) { onPick(theme.copy(accent = accent)) }
            }
        }
        Text("Accent: ${theme.accent.label}", fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Switch(
                checked = theme.dark,
                onCheckedChange = { onPick(theme.copy(dark = it)) },
                modifier = Modifier.testTag(THEME_DARK_SWITCH_TAG),
            )
            Text(if (theme.dark) "Dark" else "Light", fontSize = 12.sp)
        }
        TextButton(
            onClick = { onPick(ThemeSettings()) },
            enabled = !theme.isDefault,
            modifier = Modifier.testTag(THEME_RESET_TAG),
        ) { Text("Reset to default") }
    }
}

@Composable
private fun AccentSwatch(accent: ThemeAccent, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent
    androidx.compose.foundation.layout.Box(
        Modifier.size(32.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = accent.label }
            .handCursor()
            .testTag(THEME_ACCENT_TAG_PREFIX + accent.name)
            .border(2.dp, ring, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color(accent.argb)),
    )
}
