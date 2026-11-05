// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.plugin.config.ConfigHit
import com.superiorsoftllc.infantry.ui.plugin.config.ConfigPlan

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
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
import com.superiorsoftllc.infantry.ui.model.AGENT_SCOPE_APPLIES_TO
import com.superiorsoftllc.infantry.ui.model.AgentScope
import com.superiorsoftllc.infantry.ui.model.CliLocationsSetting
import com.superiorsoftllc.infantry.ui.model.HarnessInstallSetting
import com.superiorsoftllc.infantry.ui.protocol.CliLocation
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.infantry.ui.protocol.SecretEntryRow
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import com.superiorsoftllc.infantry.ui.protocol.SecretState
import com.superiorsoftllc.infantry.ui.model.ToolApprovalSetting
import com.superiorsoftllc.infantry.ui.protocol.ToolApprovalInfo
import com.superiorsoftllc.infantry.ui.model.FLAGS_LIFETIME_NOTE
import com.superiorsoftllc.infantry.ui.model.LifecycleSetting
import com.superiorsoftllc.infantry.ui.model.UserPortrait
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.model.ThemeAccent
import com.superiorsoftllc.infantry.ui.model.ThemeSettings

/** S4b: the Manage roles button (Configuration > Agents); stays with it in core when the role manager moves out. */
const val ROLE_MANAGER_OPEN_TAG = "role-manager-open"

/** PAIR-1: the connection "Pair a device" shares ([url], [token]) and what a link scanned or pasted there does. */
class PairEntry(val url: String, val token: String?, val name: String?, val onLink: (com.superiorsoftllc.infantry.ui.pair.PairingLink) -> Unit) {
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
    /** Superseded by PATH-PICKER-1 (path fields use the shell's picker); kept so callers still compile. */
    onBrowse: ((title: String) -> String?)? = null,
    /** BUG-PERM-1: the daemon's global Tool approval (null = not read yet / an older daemon). */
    toolApproval: ToolApprovalInfo? = null,
    /** BUG-PERM-1: pick a mode; null hides the section. */
    onToolApproval: ((String) -> Unit)? = null,
    /** ASK-TIMER-2: the daemon's prompt timer (null = not read yet / an older daemon). */
    promptTimer: com.superiorsoftllc.infantry.ui.model.PromptTimerSetting.Value? = null,
    /** ASK-TIMER-2: store the timer (seconds, 0 = Indefinite) and/or the on-expiry fallback; null = no timer control. */
    onPromptTimer: ((seconds: Int?, onTimeout: String?) -> Unit)? = null,
    /** HARNESS-PLUGIN-4 H5a: reads the daemon's harness state per module (`GET /plugins`); null = not shown. */
    harnessModules: (suspend () -> Map<String, String>?)? = null,
    /** PKG-3: the installed app's log folder; null (dev, Android) hides the Logs section. */
    logs: LogsFolder? = null,
    /** UPDATE-1: the installed app's version and "Check for updates"; null (dev, Android) hides the Updates section. */
    updates: UpdatesEntry? = null,
    /** SECRETS-1: the daemon keystore's state (null = not read yet / an older daemon); [secretActions] null hides the section. */
    secrets: SecretState? = null,
    secretActions: SecretActions? = null,
    /** PAIR-1: the connection to share and the callback for a scanned / pasted link; null hides "Pair a device". */
    pair: PairEntry? = null,
    /** No-hard-coded-limits (1.133.0): every daemon limit (null = not read yet) and the save callback; [onLimit] null hides the section. */
    limits: List<com.superiorsoftllc.infantry.ui.model.LimitInfo>? = null,
    onLimit: ((key: String, value: Long) -> Unit)? = null,
    /** ROLE-EDIT-1: opens the role manager dialog; null hides the section. */
    onOpenRoles: (() -> Unit)? = null,
    /** ASK-2: raises a sample owner question to the open session; null hides the section, [testQuestionReady] false disables the button. */
    onSendTestQuestion: (() -> Unit)? = null,
    testQuestionReady: Boolean = true,
    /** SERVER-UPDATE-4: the connected server's version and update state, and the client/server mismatch warning; null hides the section. */
    serverUpdate: com.superiorsoftllc.infantry.ui.model.ServerUpdateState? = null,
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
    /** PLUGINS-1: the loaded modules, for Plugins > Modules; null = this host has only compiled-in modules (no section). */
    modules: com.superiorsoftllc.infantry.ui.plugin.TabFactory? = null,
    /**
     * SHELL-EMPTY-DROPIN S3: what goes in the selected tab's scrolled body; the default draws every section in order
     * ([ConfigSections]). The shell's `infantry.shell.config` passes its child blocks instead.
     */
    sections: @Composable androidx.compose.foundation.layout.ColumnScope.(ConfigDialogHost) -> Unit = { ConfigSections(it) },
    /**
     * S4c: the sections [sections] draws; null = every one. The shell passes the sections among its child blocks, so a
     * shell without the app UI (whose fill holds the open settings) offers only the trust tabs.
     */
    drawn: Set<ConfigEntry>? = null,
    /**
     * CONFIG-5: the cards and tabs generated from the daemon's compiled manifest; null (no snapshot) = the hand-written
     * [ConfigEntry] tabs and search, as before.  Called with the sections this host has ([ConfigDialogHost.present]).
     */
    plan: ((present: Set<ConfigEntry>) -> ConfigPlan?)? = null,
) {
    // VOICE-PLUGIN-1: the host's installable voice plugins (desktop); null = no Plugins tab.
    val voicePlugins = com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost.current.voicePlugins
    val policyLink = com.superiorsoftllc.infantry.ui.shell.LocalShellContext.current?.link
    // CONFIG-TABS-1: which sections this host has; a tab with none of them is not offered.
    val present = buildSet {
        if (theme != null && onTheme != null) add(ConfigEntry.THEME)
        add(ConfigEntry.BLUR)
        add(ConfigEntry.MARKDOWN_ENGINE)
        add(ConfigEntry.TAB_GROUPS)
        if (userPortrait != null) add(ConfigEntry.USER_PORTRAIT)
        if (about != null) add(ConfigEntry.ABOUT)
        add(ConfigEntry.AGENT_SCOPE)
        add(ConfigEntry.PROGRESS_STALE)
        if (onKeepAliveMinutes != null) add(ConfigEntry.LIFECYCLE)
        // POLICY-1: offered once the daemon has sent its policy (a daemon too old to have one has no switch).
        if (policyLink?.policy?.presets?.isNotEmpty() == true) add(ConfigEntry.POLICY)
        if (onCliLocation != null) add(ConfigEntry.CLI_LOCATIONS)
        if (onToolApproval != null) add(ConfigEntry.TOOL_APPROVAL)
        if (onOpenRoles != null) add(ConfigEntry.ROLES)
        if (onLimit != null) add(ConfigEntry.LIMITS)
        add(ConfigEntry.VOICE)
        add(ConfigEntry.INCOMING_SOUND)
        if (onSendTestQuestion != null) add(ConfigEntry.QUESTIONS)
        if (voicePlugins != null) add(ConfigEntry.PLUGINS)
        if (modules != null) add(ConfigEntry.MODULES)
        if (modules != null) add(ConfigEntry.REGISTRY)
        if (secretActions != null && secrets != null) add(ConfigEntry.SECRETS)
        if (pair != null) add(ConfigEntry.PAIR)
        if (updates != null) add(ConfigEntry.UPDATES)
        if (serverUpdate != null) add(ConfigEntry.SERVER_UPDATE)
        if (logs != null) add(ConfigEntry.LOGS)
    }.let { all -> drawn?.let(all::intersect) ?: all }
    val generated = plan?.invoke(present)
    val tabs = visibleConfigTabs(present)
    var wanted by remember { mutableStateOf(initialTab?.label) }
    val tabLabel = if (generated != null) generated.pick(wanted) else pickConfigTab(ConfigTab.entries.firstOrNull { it.label == wanted }, present)?.label
    val tab = ConfigTab.entries.firstOrNull { it.label == tabLabel }
    val tabLabels = generated?.tabs ?: tabs.map { it.label }
    // CONFIG-SEARCH-1: a non-empty query shows matching sections instead of the tab body; picking one jumps to its
    // tab and briefly outlines it (highlight), scrolled into view.
    var query by remember { mutableStateOf("") }
    var highlight by remember { mutableStateOf<ConfigEntry?>(null) }
    var highlightKey by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(highlight, highlightKey) {
        if (highlight != null || highlightKey != null) { kotlinx.coroutines.delay(CONFIG_HIGHLIGHT_MS); highlight = null; highlightKey = null }
    }
    val results = generated?.search(query)
        ?: searchConfig(query, present).map { ConfigHit(it.name, it.tab.label, it.title, it.applies, it) }
    val config = ConfigDialogHost(
        flags, onFlags, keepAliveMinutes, userPortrait, onKeepAliveMinutes, cliLocations, onCliLocation,
        harnessInstall, harnessInstallBusy, harnessInstallLog, onHarnessCheck, onHarnessInstall, onBrowse,
        toolApproval, onToolApproval, logs, updates, secrets, secretActions, pair, limits, onLimit, onOpenRoles,
        onSendTestQuestion, testQuestionReady, serverUpdate, serverMismatch, theme, onTheme, about, modules,
        voicePlugins, present, tab, highlight, promptTimer = promptTimer, onPromptTimer = onPromptTimer,
        plan = generated, tabLabel = tabLabel, highlightKey = highlightKey, harnessModules = harnessModules,
    )
    // Ticket cb1ceb93: the scroll cue fades into the dialog's own surface.
    val surface = androidx.compose.material3.AlertDialogDefaults.containerColor
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        // CONFIG-TABS-1: one width whatever the tab, so switching tabs does not resize the dialog.
        // Ticket cb1ceb93: most of the window's width (past Material's 560dp default), capped on very wide windows.
        modifier = Modifier.widthIn(max = CONFIG_DIALOG_MAX_WIDTH).fillMaxWidth(CONFIG_DIALOG_WIDTH_FRACTION).testTag(CONFIG_DIALOG_TAG),
        containerColor = surface,
        wide = true,
        title = { ConfigHeader() },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(FLAGS_LIFETIME_NOTE, color = Palette.Muted, fontSize = 11.sp)
                ConfigSearchField(query) { query = it }
                ConfigTabRow(tabLabels, tabLabel) { picked -> query = ""; wanted = picked; ConfigTab.entries.firstOrNull { it.label == picked }?.let { onTab?.invoke(it) } }
                if (query.isNotBlank()) {
                    ConfigSearchResults(results, surface) { h ->
                        query = ""; wanted = h.tab; ConfigTab.entries.firstOrNull { it.label == h.tab }?.let { onTab?.invoke(it) }
                        highlight = h.entry; highlightKey = h.key
                    }
                } else
                // Each tab scrolls on its own, from the top: the key gives each tab a fresh scroll state.
                androidx.compose.runtime.key(tabLabel) {
                    ScrollCueColumn(
                        Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 480.dp).testTag(CONFIG_TAB_BODY_TAG),
                        fade = surface,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { sections(config) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * SHELL-EMPTY-DROPIN S3: what the configuration dialog's sections read and act on: the dialog's inputs, which sections
 * this host has ([present]), the selected [tab] and the search result just picked ([highlight]). The dialog owns the tab,
 * the query and the highlight; a section only reads them, and changes settings only through the callbacks here
 * ([onFlags], [onTheme], [onLimit], ...), as it did when it was inline.
 */
class ConfigDialogHost internal constructor(
    val flags: UiFlags,
    val onFlags: (UiFlags) -> Unit,
    val keepAliveMinutes: Int?,
    val userPortrait: Pair<String?, (ByteArray?) -> Unit>?,
    val onKeepAliveMinutes: ((Int) -> Unit)?,
    val cliLocations: List<CliLocation>?,
    val onCliLocation: ((id: String, path: String?) -> Unit)?,
    val harnessInstall: HarnessInstallReport?,
    val harnessInstallBusy: Boolean,
    val harnessInstallLog: List<String>,
    val onHarnessCheck: ((checkLatest: Boolean) -> Unit)?,
    val onHarnessInstall: ((ids: List<String>, update: Boolean) -> Unit)?,
    val onBrowse: ((title: String) -> String?)?,
    val toolApproval: ToolApprovalInfo?,
    val onToolApproval: ((String) -> Unit)?,
    val logs: LogsFolder?,
    val updates: UpdatesEntry?,
    val secrets: SecretState?,
    val secretActions: SecretActions?,
    val pair: PairEntry?,
    val limits: List<com.superiorsoftllc.infantry.ui.model.LimitInfo>?,
    val onLimit: ((key: String, value: Long) -> Unit)?,
    val onOpenRoles: (() -> Unit)?,
    val onSendTestQuestion: (() -> Unit)?,
    val testQuestionReady: Boolean,
    val serverUpdate: com.superiorsoftllc.infantry.ui.model.ServerUpdateState?,
    val serverMismatch: String?,
    val theme: ThemeSettings?,
    val onTheme: ((ThemeSettings) -> Unit)?,
    val about: AboutEntry?,
    val modules: com.superiorsoftllc.infantry.ui.plugin.TabFactory?,
    val voicePlugins: com.superiorsoftllc.infantry.ui.voice.plugin.VoicePlugins?,
    val present: Set<ConfigEntry>,
    val tab: ConfigTab?,
    val highlight: ConfigEntry?,
    /** ASK-TIMER-2: the daemon's prompt timer and its save callback (null = no timer control). */
    val promptTimer: com.superiorsoftllc.infantry.ui.model.PromptTimerSetting.Value? = null,
    val onPromptTimer: ((seconds: Int?, onTimeout: String?) -> Unit)? = null,
    /** CONFIG-5: the generated plan (null = hand-written tabs), the selected tab's label (a plugin's tab has no [tab]) and the card a search just picked. */
    val plan: ConfigPlan? = null,
    val tabLabel: String? = tab?.label,
    val highlightKey: String? = null,
    /** HARNESS-PLUGIN-4 H5a: reads the daemon's harness state per module for Plugins > Modules; null = not shown. */
    val harnessModules: (suspend () -> Map<String, String>?)? = null,
) {
    /** A section shows when its tab is the selected one (the plan's, when a card moved it) and this host has it. */
    fun shows(entry: ConfigEntry): Boolean = entry in present && if (plan != null) plan.tabOf(entry) == tabLabel else entry.tab == tab
}

/** SHELL-EMPTY-DROPIN S3: every section, in display order (each draws nothing off its tab or when this host lacks it). */
@Composable
fun ConfigSections(config: ConfigDialogHost) {
    ConfigThemeSection(config)
    ConfigBlurSection(config)
    ConfigMarkdownEngineSection(config)
    ConfigReasoningSection(config)
    ConfigTabGroupsSection(config)
    ConfigUserPortraitSection(config)
    ConfigAgentScopeSection(config)
    ConfigProgressStaleSection(config)
    ConfigLifecycleSection(config)
    ConfigCliLocationsSection(config)
    ConfigToolApprovalSection(config)
    ConfigRolesSection(config)
    ConfigLimitsSection(config)
    ConfigVoiceSection(config)
    ConfigIncomingSoundSection(config)
    ConfigQuestionsSection(config)
    ConfigPluginsSection(config)
    ConfigModulesSection(config)
    ConfigRegistrySection(config)
    ConfigSecretsSection(config)
    ConfigPairSection(config)
    ConfigAboutSection(config)
    ConfigUpdatesSection(config)
    ConfigServerUpdateSection(config)
    ConfigLogsSection(config)
}

// SHELL-EMPTY-DROPIN S3: one composable per section. Each draws nothing unless it shows ([ConfigDialogHost.shows]) and
// its inputs are there; its placement goes on the section's column, before the section's own tag.

@Composable
fun ConfigThemeSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val theme = config.theme ?: return
    val onTheme = config.onTheme ?: return
    if (!config.shows(ConfigEntry.THEME)) return
    FlagSection(ConfigEntry.THEME, config.highlight, modifier) { ThemeSection(theme, onTheme) }
}

@Composable
fun ConfigBlurSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.BLUR)) return
    val flags = config.flags
    FlagSection(ConfigEntry.BLUR, config.highlight, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = flags.blurBehindDialogs,
                onCheckedChange = { config.onFlags(flags.copy(blurBehindDialogs = it)) },
                modifier = Modifier.testTag(BLUR_SWITCH_TAG),
            )
            Text(if (flags.blurBehindDialogs) "On" else "Off", fontSize = 12.sp)
        }
    }
}

@Composable
fun ConfigMarkdownEngineSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.MARKDOWN_ENGINE)) return
    FlagSection(ConfigEntry.MARKDOWN_ENGINE, config.highlight, modifier) {
        MarkdownEngineDropdown(config.flags.chatMarkdownEngine) { config.onFlags(config.flags.copy(chatMarkdownEngine = it)) }
    }
}

@Composable
fun ConfigReasoningSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.REASONING)) return
    FlagSection(ConfigEntry.REASONING, config.highlight, modifier) {
        ReasoningPlacementChoice(config.flags.reasoningPlacement) { config.onFlags(config.flags.copy(reasoningPlacement = it)) }
    }
}

/** SETTINGS-TAB-1: one switch per host tab (Settings): its group as sub-tabs, or back in the strip. */
@Composable
fun ConfigTabGroupsSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.TAB_GROUPS)) return
    val factory = LocalTabFactory.current
    FlagSection(ConfigEntry.TAB_GROUPS, config.highlight, modifier) {
        com.superiorsoftllc.infantry.ui.plugin.TabGroupsSection(factory)
    }
}

@Composable
fun ConfigUserPortraitSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val userPortrait = config.userPortrait ?: return
    if (!config.shows(ConfigEntry.USER_PORTRAIT)) return
    FlagSection(ConfigEntry.USER_PORTRAIT, config.highlight, modifier) { UserPortraitSection(userPortrait.first, userPortrait.second) }
}

@Composable
fun ConfigAgentScopeSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.AGENT_SCOPE)) return
    FlagSection(ConfigEntry.AGENT_SCOPE, config.highlight, modifier) {
        AgentScopeDropdown(config.flags.agentScope) { config.onFlags(config.flags.copy(agentScope = it)) }
    }
}

@Composable
fun ConfigProgressStaleSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.PROGRESS_STALE)) return
    val flags = config.flags
    FlagSection(ConfigEntry.PROGRESS_STALE, config.highlight, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.superiorsoftllc.infantry.ui.model.PROGRESS_STALE_CHOICES.forEach { min ->
                val picked = flags.progressStaleMin == min
                OutlinedButton(
                    onClick = { config.onFlags(flags.copy(progressStaleMin = min)) },
                    modifier = Modifier.testTag(PROGRESS_STALE_CHOICE_TAG_PREFIX + min),
                ) { Text("$min min", fontSize = 11.sp, color = if (picked) Palette.Accent else Palette.Text, fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal) }
            }
        }
    }
}

/** BUG-M2-11e: daemon state, not a window flag: it is stored by the daemon and survives a restart. */
@Composable
fun ConfigLifecycleSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val onKeepAliveMinutes = config.onKeepAliveMinutes ?: return
    if (!config.shows(ConfigEntry.LIFECYCLE)) return
    FlagSection(ConfigEntry.LIFECYCLE, config.highlight, modifier) { KeepAliveDropdown(config.keepAliveMinutes, onKeepAliveMinutes) }
}

/** CLIPATH-1: daemon state too: the daemon validates and stores each path, and says where each CLI resolved. */
@Composable
fun ConfigCliLocationsSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val onCliLocation = config.onCliLocation ?: return
    if (!config.shows(ConfigEntry.CLI_LOCATIONS)) return
    val harnessInstall = config.harnessInstall
    val onHarnessCheck = config.onHarnessCheck
    val onHarnessInstall = config.onHarnessInstall
    FlagSection(ConfigEntry.CLI_LOCATIONS, config.highlight, modifier) {
        CliLocationsSection(config.cliLocations, onCliLocation, harnessInstall, config.harnessInstallBusy, onHarnessInstall)
        if (harnessInstall != null && onHarnessCheck != null && onHarnessInstall != null) {
            HarnessInstallControls(harnessInstall, config.harnessInstallBusy, config.harnessInstallLog, onHarnessCheck, onHarnessInstall)
        }
    }
}

/** BUG-PERM-1: daemon state: no harness can relay an approval prompt yet, so this decides what tools may run. */
@Composable
fun ConfigToolApprovalSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val onToolApproval = config.onToolApproval ?: return
    if (!config.shows(ConfigEntry.TOOL_APPROVAL)) return
    FlagSection(ConfigEntry.TOOL_APPROVAL, config.highlight, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolApprovalDropdown(config.toolApproval, onToolApproval)
            config.onPromptTimer?.let { PromptTimerControl(config.promptTimer, it) }
        }
    }
}

/** ROLE-EDIT-1: the role manager is its own dialog (shared modal). */
@Composable
fun ConfigRolesSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val onOpenRoles = config.onOpenRoles ?: return
    if (!config.shows(ConfigEntry.ROLES)) return
    FlagSection(ConfigEntry.ROLES, config.highlight, modifier) {
        OutlinedButton(onClick = onOpenRoles, modifier = Modifier.testTag(ROLE_MANAGER_OPEN_TAG)) { Text("Manage roles") }
    }
}

/** No-hard-coded-limits: daemon state; the daemon validates each value and names the range on a refusal. */
@Composable
fun ConfigLimitsSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val onLimit = config.onLimit ?: return
    if (!config.shows(ConfigEntry.LIMITS)) return
    FlagSection(ConfigEntry.LIMITS, config.highlight, modifier) { LimitsSection(config.limits, onLimit) }
}

@Composable
fun ConfigVoiceSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.VOICE)) return
    FlagSection(ConfigEntry.VOICE, config.highlight, modifier) {
        com.superiorsoftllc.infantry.ui.voice.VoiceSettingsSection(config.flags.voice) { config.onFlags(config.flags.copy(voice = it)) }
    }
}

@Composable
fun ConfigIncomingSoundSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    if (!config.shows(ConfigEntry.INCOMING_SOUND)) return
    FlagSection(ConfigEntry.INCOMING_SOUND, config.highlight, modifier) {
        com.superiorsoftllc.infantry.ui.sound.IncomingCommsSoundSection(config.flags.commsSound) { config.onFlags(config.flags.copy(commsSound = it)) }
    }
}

@Composable
fun ConfigQuestionsSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val onSendTestQuestion = config.onSendTestQuestion ?: return
    if (!config.shows(ConfigEntry.QUESTIONS)) return
    FlagSection(ConfigEntry.QUESTIONS, config.highlight, modifier) {
        OutlinedButton(onClick = onSendTestQuestion, enabled = config.testQuestionReady, modifier = Modifier.testTag(TEST_QUESTION_TAG)) {
            Text(if (config.testQuestionReady) "Send a test question" else "Open a session first")
        }
    }
}

@Composable
fun ConfigPluginsSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val voicePlugins = config.voicePlugins ?: return
    if (!config.shows(ConfigEntry.PLUGINS)) return
    FlagSection(ConfigEntry.PLUGINS, config.highlight, modifier) {
        com.superiorsoftllc.infantry.ui.voice.plugin.PluginsSection(voicePlugins)
    }
}

@Composable
fun ConfigModulesSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val modules = config.modules ?: return
    if (!config.shows(ConfigEntry.MODULES)) return
    // H5a: the daemon's harness state per module, read once each time the section is shown.
    val read = config.harnessModules
    val harness by androidx.compose.runtime.produceState<Map<String, String>?>(null, read) { value = read?.invoke() }
    FlagSection(ConfigEntry.MODULES, config.highlight, modifier) {
        com.superiorsoftllc.infantry.ui.plugin.ModulesSection(modules, harnessStatus = harness)
    }
}

/** P1 (f60f4d97): the container nodes and what is registered in each, read-only (Plugins > Registered). */
@Composable
fun ConfigRegistrySection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val modules = config.modules ?: return
    if (!config.shows(ConfigEntry.REGISTRY)) return
    FlagSection(ConfigEntry.REGISTRY, config.highlight, modifier) {
        com.superiorsoftllc.infantry.ui.plugin.RegistrySection(modules)
    }
}

/** SECRETS-1: the daemon owns the secrets; this only sends "set / clear / import" and shows set / not set. */
@Composable
fun ConfigSecretsSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val secretActions = config.secretActions ?: return
    val secrets = config.secrets ?: return
    if (!config.shows(ConfigEntry.SECRETS)) return
    FlagSection(ConfigEntry.SECRETS, config.highlight, modifier) { SecretsSection(secrets, secretActions) }
}

/** PAIR-1: show this connection as a QR / link, or connect to another server from one. */
@Composable
fun ConfigPairSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val pair = config.pair ?: return
    if (!config.shows(ConfigEntry.PAIR)) return
    FlagSection(ConfigEntry.PAIR, config.highlight, modifier) {
        com.superiorsoftllc.infantry.ui.pair.PairDeviceSection(pair.url, pair.token, pair.name, pair.onLink)
    }
}

/** BUILD-STAMP-1: first in About, it answers "which build am I running?". */
@Composable
fun ConfigAboutSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val about = config.about ?: return
    if (!config.shows(ConfigEntry.ABOUT)) return
    FlagSection(ConfigEntry.ABOUT, config.highlight, modifier) { AboutSection(about) }
}

/** UPDATE-1: the check itself runs in the daemon side's updater; this only asks for it. */
@Composable
fun ConfigUpdatesSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val updates = config.updates ?: return
    if (!config.shows(ConfigEntry.UPDATES)) return
    FlagSection(ConfigEntry.UPDATES, config.highlight, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Running ${updates.version}", fontSize = 12.sp, modifier = Modifier.testTag(UPDATES_VERSION_TAG))
            OutlinedButton(onClick = updates.check, modifier = Modifier.testTag(UPDATES_CHECK_TAG)) { Text("Check for updates") }
        }
    }
}

/** SERVER-UPDATE-4: the daemon's own version and updater, over the existing connection. */
@Composable
fun ConfigServerUpdateSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val serverUpdate = config.serverUpdate ?: return
    if (!config.shows(ConfigEntry.SERVER_UPDATE)) return
    FlagSection(ConfigEntry.SERVER_UPDATE, config.highlight, modifier) { ServerUpdateSection(serverUpdate, config.serverMismatch) }
}

/** PKG-3: where the installed app writes its log, so a Start-menu launch (no console) can still report a problem. */
@Composable
fun ConfigLogsSection(config: ConfigDialogHost, modifier: Modifier = Modifier) {
    val logs = config.logs ?: return
    if (!config.shows(ConfigEntry.LOGS)) return
    FlagSection(ConfigEntry.LOGS, config.highlight, modifier) { LogsSection(logs) }
}


/** Ticket cb1ceb93: the title, after the same drawn cog as the footer button that opens this dialog. */
@Composable
private fun ConfigHeader() =
    ConfigCogLabel(Palette.Accent, 22.dp, 10.dp, Modifier.testTag(CONFIG_HEADER_ICON_TAG), fontWeight = FontWeight.Bold)

/** The cog and "Configuration": the dialog's title ([ConfigHeader]) and, S4c, the bare shell's way in (ShellConfigEntry). */
@Composable
internal fun ConfigCogLabel(
    tint: androidx.compose.ui.graphics.Color,
    iconSize: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
    iconModifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        androidx.compose.material3.Icon(AhqIcons.Cog, contentDescription = null, tint = tint, modifier = iconModifier.size(iconSize))
        Text("Configuration", color = color, fontSize = fontSize, fontWeight = fontWeight)
    }
}

/**
 * CONFIG-TABS-1: the tab strip. Scrollable, so a phone can swipe through tabs that do not fit;
 * each tab carries CONFIG_TAB_TAG_PREFIX + its name.
 */
@Composable
private fun ConfigTabRow(tabs: List<String>, selected: String?, onPick: (String) -> Unit) {
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
                modifier = Modifier.handCursor().testTag(CONFIG_TAB_TAG_PREFIX + (ConfigTab.entries.firstOrNull { it.label == t }?.name ?: t)),
                text = {
                    Text(
                        t, fontSize = 12.sp,
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
internal fun FlagSection(entry: ConfigEntry, highlight: ConfigEntry?, modifier: Modifier, control: @Composable () -> Unit) {
    val lit = entry == highlight
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(lit) { if (lit) requester.bringIntoView() }
    val mod = modifier.testTag(CONFIG_SECTION_TAG_PREFIX + entry.name)
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
private fun ConfigSearchResults(results: List<ConfigHit>, fade: androidx.compose.ui.graphics.Color, onPick: (ConfigHit) -> Unit) {
    ScrollCueColumn(
        Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 480.dp).testTag(CONFIG_SEARCH_RESULTS_TAG),
        fade = fade,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (results.isEmpty()) {
            Text("No setting matches.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp).testTag(CONFIG_SEARCH_EMPTY_TAG))
        }
        results.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(e) }.padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag(CONFIG_RESULT_TAG_PREFIX + (e.entry?.name ?: e.key)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(e.applies, color = Palette.Muted, fontSize = 11.sp, maxLines = 2)
                }
                Text(
                    e.tab, fontSize = 10.sp, color = Palette.Accent,
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
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = Palette.PopupShape, containerColor = Palette.Raised) {
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
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = Palette.PopupShape, containerColor = Palette.Raised) {
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
    harness: HarnessInstallReport?,
    harnessBusy: Boolean,
    onInstall: ((ids: List<String>, update: Boolean) -> Unit)?,
) {
    if (locations == null) {
        Text("Not read from the daemon", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(CLI_LOCATIONS_PENDING_TAG))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        locations.forEach { location -> CliLocationRow(location, onSave, harness?.harnesses?.firstOrNull { it.id == location.id }, harnessBusy, onInstall) }
    }
}

@Composable
private fun CliLocationRow(
    location: CliLocation,
    onSave: (id: String, path: String?) -> Unit,
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
        // H5a: [check] comes from the daemon's install report, so its presence is what makes the row installable.
        if (check != null) {
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
        // PATH-PICKER-1: the shared path field; Browse (a native file dialog, off the UI thread) fills it, Save stores it.
        PathField(
            text, { text = it }, PathKind.FILE, "${location.label} executable", Modifier.fillMaxWidth(),
            tag = CLI_FIELD_TAG_PREFIX + location.id, title = "Locate ${location.label}",
            placeholder = "Absolute path to the ${location.label} executable",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        const val APPLIES_TO = "This app's log files (newest is infantry.log). Attach them to a bug report"
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

/** SECRETS-2b: the project on screen, so an app secret can be moved into it. */
data class SecretProjectRef(val id: String, val name: String)

/**
 * SECRETS-1: what the Secrets section can ask of the daemon. SECRETS-2: [setField] / [deleteField] / [delete] edit the user's
 * key/value secrets in the app scope; null (an older daemon) hides that part. SECRETS-2b: [move] (name, from, to; null = the app
 * scope) moves a secret between scopes, and [currentProject] is where an app secret can go; null hides the move.
 */
class SecretActions(
    val set: (name: String, value: String) -> Unit,
    val clear: (name: String) -> Unit,
    val import: (name: String) -> Unit,
    val setField: ((name: String, field: String, value: String) -> Unit)? = null,
    val deleteField: ((name: String, field: String) -> Unit)? = null,
    val delete: ((name: String) -> Unit)? = null,
    val move: ((name: String, from: String?, to: String?) -> Unit)? = null,
    val currentProject: SecretProjectRef? = null,
)

/**
 * SECRETS-2b: the edits of ONE scope (the app's, or one project's), so the same cards serve Configuration > Secrets and a
 * project's settings. [move] sends a secret to the other scope ([moveLabel] names it); null hides the button.
 */
class SecretScopeActions(
    val setField: (name: String, field: String, value: String) -> Unit,
    val deleteField: ((name: String, field: String) -> Unit)? = null,
    val delete: ((name: String) -> Unit)? = null,
    val move: ((name: String) -> Unit)? = null,
    val moveLabel: String = "",
)

/** SECRETS-2: what the add forms accept, the daemon's own rules, so a bad name is caught before it is sent. */
object SecretForm {
    private val NAME = Regex("""^[a-z0-9][a-z0-9._-]{0,63}$""")
    private val FIELD = Regex("""^[A-Za-z_][A-Za-z0-9_]{0,63}$""")
    fun nameOk(name: String) = NAME.matches(name)
    fun fieldOk(field: String) = FIELD.matches(field)
    const val NAME_HINT = "Lowercase letters, digits, '.', '_' and '-', e.g. android-signing"
    const val FIELD_HINT = "The field name becomes the environment variable's name: letters, digits and '_', e.g. storePassword"
    const val TITLE = "App secrets"
    const val APPLIES_TO = "Shared by every project. A project's own secret of the same name wins inside that project"
    const val PROJECT_APPLIES_TO = "Only this project's run commands get these. A name here wins over an app secret of the same name"
    const val EMPTY = "No secrets yet. A secret is a named set of fields (e.g. android-signing: storeFile, storePassword); a saved run command " +
        "that names it gets every field as an environment variable, masked in its output"
    const val LOCKED = "Locked: the daemon's lockdown is on, so secrets cannot be changed and no run gets one"
    const val BUILT_IN_TITLE = "Built-in"
    const val BUILT_IN_APPLIES_TO = "Secrets the app itself uses. They never go to a run command"
    const val SCOPE_APP = "App"
    const val SCOPE_PROJECT = "Project"
    fun projectsNote(count: Int) = "$count project secret${if (count == 1) "" else "s"} live in their projects: Project settings > Secrets"
}

/** The text a row shows: set (with its masked tail) or not set. Never a value. */
fun secretStatusText(row: SecretRow): String = when {
    row.overriddenByEnv -> "Set (the environment variable overrides it)"
    row.set -> "Set ${row.tail}".trim()
    else -> "Not set"
}

/** SECRETS-2b: the card frame every secret sits in: raised, a border you can see, the popup/card corner token. */
private fun Modifier.secretCard(): Modifier = this.fillMaxWidth()
    .background(Palette.Raised, Palette.PopupShape)
    .border(1.dp, Palette.Muted.copy(alpha = 0.45f), Palette.PopupShape)
    .padding(12.dp)

@Composable
private fun SecretsSection(state: SecretState, actions: SecretActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.secrets.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretsHeading(SecretForm.BUILT_IN_TITLE, SecretForm.BUILT_IN_APPLIES_TO)
                for (row in state.secrets) androidx.compose.runtime.key(row.name) { BuiltInSecretCard(row, actions) }
            }
        }
        val setField = actions.setField
        if (setField != null) {
            val project = actions.currentProject
            val ownInProject = project?.let { p -> state.entries.filter { it.project == p.id }.map { it.name }.toSet() }.orEmpty()
            val move = actions.move
            SecretsScopeEditor(
                entries = state.entries.filter { it.project == null },
                locked = state.locked,
                title = SecretForm.TITLE,
                hint = SecretForm.APPLIES_TO,
                scopeBadge = SecretForm.SCOPE_APP,
                actions = SecretScopeActions(
                    setField = setField, deleteField = actions.deleteField, delete = actions.delete,
                    move = if (move != null && project != null) { name -> move(name, null, project.id) } else null,
                    moveLabel = project?.let { "Move to ${it.name}" }.orEmpty(),
                ),
                note = { name -> if (name in ownInProject) "Overridden in ${project?.name} by its own secret" else null },
            )
            val elsewhere = state.entries.count { it.project != null }
            if (elsewhere > 0) Text(SecretForm.projectsNote(elsewhere), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(SECRET_PROJECTS_NOTE_TAG))
        }
        Text("Protected by: ${protectionText(state.protection)}", color = Palette.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SecretsHeading(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        Text(hint, color = Palette.Muted, fontSize = 11.sp)
    }
}

/** SECRETS-1: a built-in single-value secret as one card: its status, then its input and actions inside the same card. */
@Composable
private fun BuiltInSecretCard(row: SecretRow, actions: SecretActions) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.secretCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.weight(1f))
            Text(secretStatusText(row), fontSize = 12.sp, color = if (row.set) Palette.Ok else Palette.Muted, modifier = Modifier.testTag("$SECRET_STATUS_TAG-${row.name}"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaskedInput(draft, { draft = it }, if (row.set) "Replace with" else "Paste it here", Modifier.weight(1f).testTag("$SECRET_INPUT_TAG-${row.name}"))
            SecretButton(if (row.set) "Replace" else "Save", draft.isNotBlank(), "$SECRET_SAVE_TAG-${row.name}") { actions.set(row.name, draft); draft = "" }
            if (row.set) SecretButton("Delete", true, "$SECRET_DELETE_TAG-${row.name}", danger = true) { actions.clear(row.name) }
            if (row.importable) SecretButton("Import from environment", true, "$SECRET_IMPORT_TAG-${row.name}") { actions.import(row.name) }
        }
    }
}

/**
 * SECRETS-2b: one scope's key/value secrets: a heading, each secret as a bordered card (header, its fields as rows, an inline
 * add-field row), then a separate "New secret" card. Public so a project's settings show the same cards for its own scope.
 * [note] adds a line under a secret's header (e.g. "overridden in ..."); [editable] false shows without any change control.
 */
@Composable
fun SecretsScopeEditor(
    entries: List<SecretEntryRow>,
    locked: Boolean,
    title: String,
    hint: String,
    scopeBadge: String,
    actions: SecretScopeActions,
    modifier: Modifier = Modifier,
    editable: Boolean = !locked,
    note: (String) -> String? = { null },
) {
    Column(modifier.testTag(SECRET_ENTRIES_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecretsHeading(title, hint)
        if (locked) Text(SecretForm.LOCKED, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(SECRET_LOCKED_TAG))
        if (entries.isEmpty()) Text(SecretForm.EMPTY, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(SECRET_EMPTY_TAG))
        for (entry in entries) {
            androidx.compose.runtime.key(entry.project, entry.name) { SecretEntryCard(entry, editable, actions, scopeBadge, note(entry.name)) }
        }
        if (editable) NewSecretCard(entries.map { it.name }.toSet(), actions.setField)
    }
}

@Composable
private fun SecretEntryCard(entry: SecretEntryRow, editable: Boolean, actions: SecretScopeActions, scopeBadge: String, note: String?) {
    var confirmDelete by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.secretCard().testTag("$SECRET_ENTRY_TAG-${entry.name}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Header: name, field count, scope, then the secret-wide actions.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.name, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text("${entry.fields.size} field${if (entry.fields.size == 1) "" else "s"}", color = Palette.Muted, fontSize = 11.sp)
            ScopeBadge(scopeBadge, Modifier.testTag("$SECRET_SCOPE_TAG-${entry.name}"))
            Spacer(Modifier.weight(1f))
            val move = actions.move
            if (editable && move != null && !confirmDelete) {
                TextButton(onClick = { move(entry.name) }, modifier = Modifier.testTag("$SECRET_MOVE_TAG-${entry.name}")) { Text(actions.moveLabel, fontSize = 12.sp, color = Palette.Text) }
            }
            val delete = actions.delete
            if (editable && delete != null && !confirmDelete) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("$SECRET_ENTRY_DELETE_TAG-${entry.name}")) {
                    Text("Delete secret", fontSize = 12.sp, color = Palette.Danger)
                }
            }
            if (confirmDelete && delete != null) {
                Text("Delete it and its ${entry.fields.size} fields?", color = Palette.Danger, fontSize = 12.sp)
                SecretButton("Delete", true, "$SECRET_ENTRY_DELETE_CONFIRM_TAG-${entry.name}", danger = true) { confirmDelete = false; delete(entry.name) }
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", fontSize = 12.sp, color = Palette.Text) }
            }
        }
        if (note != null) Text(note, color = Palette.Warn, fontSize = 11.sp, modifier = Modifier.testTag("$SECRET_NOTE_TAG-${entry.name}"))
        androidx.compose.material3.HorizontalDivider(color = Palette.Muted.copy(alpha = 0.25f))
        // Its fields, one row each, on the darker inner surface.
        for (field in entry.fields) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().background(Palette.Panel, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp).testTag("$SECRET_FIELD_TAG-${entry.name}-${field.name}"),
            ) {
                Text(field.name, fontFamily = Mono, fontSize = 12.sp, color = Palette.Text, modifier = Modifier.weight(1f))
                Text(field.tail.ifEmpty { "set" }, fontFamily = Mono, color = Palette.Muted, fontSize = 12.sp)
                if (editable) {
                    TextButton(onClick = { replacing = if (replacing == field.name) null else field.name; draft = "" }, modifier = Modifier.testTag("$SECRET_FIELD_REPLACE_TAG-${entry.name}-${field.name}")) {
                        Text("Replace", fontSize = 12.sp, color = Palette.Text)
                    }
                    actions.deleteField?.let { del ->
                        TextButton(onClick = { del(entry.name, field.name) }, modifier = Modifier.testTag("$SECRET_FIELD_DELETE_TAG-${entry.name}-${field.name}")) {
                            Text("Delete", fontSize = 12.sp, color = Palette.Danger)
                        }
                    }
                }
            }
            if (editable && replacing == field.name) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 10.dp)) {
                    MaskedInput(draft, { draft = it }, "New value for ${field.name}", Modifier.weight(1f).testTag("$SECRET_FIELD_INPUT_TAG-${entry.name}-${field.name}"))
                    SecretButton("Save", draft.isNotBlank(), "$SECRET_FIELD_SAVE_TAG-${entry.name}-${field.name}") {
                        actions.setField(entry.name, field.name, draft); draft = ""; replacing = null
                    }
                }
            }
        }
        if (editable) AddFieldRow(entry, actions.setField)
    }
}

/** A small outlined chip naming a secret's scope (App / Project). */
@Composable
private fun ScopeBadge(label: String, modifier: Modifier = Modifier) {
    Text(
        label, fontSize = 10.sp, color = Palette.Info,
        modifier = modifier.border(1.dp, Palette.Info.copy(alpha = 0.6f), Palette.PopupShape).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun AddFieldRow(entry: SecretEntryRow, setField: (String, String, String) -> Unit) {
    var field by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val taken = entry.fields.any { it.name == field.trim() }
    val ok = SecretForm.fieldOk(field.trim()) && !taken && value.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("Add a field to ${entry.name}", color = Palette.Muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretTextField(
                field, { field = it }, "Field name", isError = field.isNotBlank() && (!SecretForm.fieldOk(field.trim()) || taken),
                modifier = Modifier.weight(1f).testTag("$SECRET_ADD_FIELD_NAME_TAG-${entry.name}"),
            )
            MaskedInput(value, { value = it }, "Value", Modifier.weight(1f).testTag("$SECRET_ADD_FIELD_VALUE_TAG-${entry.name}"))
            SecretButton("Add field", ok, "$SECRET_ADD_FIELD_TAG-${entry.name}") { setField(entry.name, field.trim(), value); field = ""; value = "" }
        }
    }
}

@Composable
private fun NewSecretCard(existing: Set<String>, setField: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var field by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val n = name.trim()
    val nameBad = n.isNotEmpty() && (!SecretForm.nameOk(n) || n in existing)
    val fieldBad = field.isNotBlank() && !SecretForm.fieldOk(field.trim())
    Column(Modifier.padding(top = 8.dp).secretCard().testTag(SECRET_NEW_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("New secret", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        SecretTextField(name, { name = it }, "Secret name", isError = nameBad, modifier = Modifier.fillMaxWidth().testTag(SECRET_NEW_NAME_TAG))
        Text(
            if (n in existing) "A secret with this name exists; add a field to it above" else SecretForm.NAME_HINT,
            color = if (nameBad) Palette.Danger else Palette.Muted, fontSize = 11.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretTextField(field, { field = it }, "First field", isError = fieldBad, modifier = Modifier.weight(1f).testTag(SECRET_NEW_FIELD_TAG))
            MaskedInput(value, { value = it }, "Value", Modifier.weight(1f).testTag(SECRET_NEW_VALUE_TAG))
        }
        Text(SecretForm.FIELD_HINT, color = Palette.Muted, fontSize = 11.sp)
        Row {
            Spacer(Modifier.weight(1f))
            SecretButton(
                "Create secret", SecretForm.nameOk(n) && n !in existing && SecretForm.fieldOk(field.trim()) && value.isNotBlank(), SECRET_NEW_SAVE_TAG,
            ) { setField(n, field.trim(), value); name = ""; field = ""; value = "" }
        }
    }
}

/** Inputs with an outline you can see on the dialog (the theme's border tone alone nearly vanishes there). */
@Composable
private fun secretFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Palette.Muted.copy(alpha = 0.6f),
    focusedBorderColor = Palette.Accent,
    disabledBorderColor = Palette.Muted.copy(alpha = 0.3f),
    unfocusedContainerColor = Palette.Panel,
    focusedContainerColor = Palette.Panel,
    unfocusedLabelColor = Palette.Muted,
)

@Composable
private fun SecretTextField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) }, isError = isError,
        shape = Palette.PopupShape, colors = secretFieldColors(), modifier = modifier,
    )
}

/** A value input that never shows what is typed (no reveal: the owner's rule). */
@Composable
private fun MaskedInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false,
        ),
        shape = Palette.PopupShape, colors = secretFieldColors(),
        modifier = modifier,
    )
}

/** An outlined action whose outline shows; dim (not hidden) until it can act. */
@Composable
private fun SecretButton(label: String, enabled: Boolean, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    val tone = if (danger) Palette.Danger else Palette.Text
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Palette.PopupShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) tone.copy(alpha = 0.7f) else Palette.Muted.copy(alpha = 0.3f)),
        modifier = Modifier.testTag(tag),
    ) { Text(label, fontSize = 12.sp, color = if (enabled) tone else Palette.Muted) }
}

private fun protectionText(wire: String): String = when (wire) {
    "os" -> "the operating system's credential facility"
    "external-key" -> "a key supplied to the daemon (INFANTRY_KEYSTORE_KEY)"
    "key-file" -> "a key file in the daemon's state folder (weaker: set INFANTRY_KEYSTORE_KEY on a shared machine)"
    else -> "unknown"
}

const val SECRET_STATUS_TAG = "secret-status"
const val SECRET_INPUT_TAG = "secret-input"
const val SECRET_SAVE_TAG = "secret-save"
const val SECRET_DELETE_TAG = "secret-delete"
const val SECRET_IMPORT_TAG = "secret-import"
const val SECRET_ENTRIES_TAG = "secret-entries"
const val SECRET_LOCKED_TAG = "secret-locked"
const val SECRET_ENTRY_TAG = "secret-entry"
const val SECRET_ENTRY_DELETE_TAG = "secret-entry-delete"
const val SECRET_ENTRY_DELETE_CONFIRM_TAG = "secret-entry-delete-confirm"
const val SECRET_FIELD_TAG = "secret-field"
const val SECRET_FIELD_REPLACE_TAG = "secret-field-replace"
const val SECRET_FIELD_DELETE_TAG = "secret-field-delete"
const val SECRET_FIELD_INPUT_TAG = "secret-field-input"
const val SECRET_FIELD_SAVE_TAG = "secret-field-save"
const val SECRET_ADD_FIELD_NAME_TAG = "secret-add-field-name"
const val SECRET_ADD_FIELD_VALUE_TAG = "secret-add-field-value"
const val SECRET_ADD_FIELD_TAG = "secret-add-field"
const val SECRET_NEW_TAG = "secret-new"
const val SECRET_NEW_NAME_TAG = "secret-new-name"
const val SECRET_NEW_FIELD_TAG = "secret-new-field"
const val SECRET_NEW_VALUE_TAG = "secret-new-value"
const val SECRET_NEW_SAVE_TAG = "secret-new-save"
const val SECRET_EMPTY_TAG = "secret-empty"
const val SECRET_SCOPE_TAG = "secret-scope"
const val SECRET_MOVE_TAG = "secret-move"
const val SECRET_NOTE_TAG = "secret-note"
const val SECRET_PROJECTS_NOTE_TAG = "secret-projects-note"

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
/** PATH-PICKER-1: a CLI path field's Browse is `CLI_FIELD_TAG_PREFIX + id + PATH_FIELD_BROWSE_TAG_SUFFIX`; this prefix is no longer drawn. */
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
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = Palette.PopupShape, containerColor = Palette.Raised) {
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

/**
 * ASK-TIMER-2: the prompt timer under Tool approval: a seconds field (0 = Indefinite) with Save, an Indefinite / 120 s
 * shortcut row, and the on-expiry fallback for an approval with no recommended option. [value] null = not read yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptTimerControl(
    value: com.superiorsoftllc.infantry.ui.model.PromptTimerSetting.Value?,
    onSave: (seconds: Int?, onTimeout: String?) -> Unit,
) {
    val setting = com.superiorsoftllc.infantry.ui.model.PromptTimerSetting
    // Re-seeded whenever the daemon reports another value, so a change elsewhere shows here.
    var text by remember(value?.timeoutSec) { mutableStateOf((value?.timeoutSec ?: setting.SUGGESTED_SEC).toString()) }
    val typed = setting.parseSeconds(text)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(PROMPT_TIMER_TAG)) {
        Text(setting.TITLE, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(setting.APPLIES_TO, color = Palette.Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                enabled = value != null,
                label = { Text("Seconds (0 = Indefinite)") },
                isError = typed == null,
                modifier = Modifier.weight(1f).testTag(PROMPT_TIMER_FIELD_TAG),
            )
            TextButton(
                onClick = { typed?.let { onSave(it, null) } },
                enabled = value != null && typed != null && typed != value.timeoutSec,
                modifier = Modifier.testTag(PROMPT_TIMER_SAVE_TAG),
            ) { Text("Save") }
            TextButton(
                onClick = { text = "0"; onSave(0, null) },
                enabled = value != null && value.timeoutSec != 0,
                modifier = Modifier.testTag(PROMPT_TIMER_INDEFINITE_TAG),
            ) { Text("Indefinite") }
            TextButton(
                onClick = { text = setting.SUGGESTED_SEC.toString(); onSave(setting.SUGGESTED_SEC, null) },
                enabled = value != null && value.timeoutSec != setting.SUGGESTED_SEC,
            ) { Text("${setting.SUGGESTED_SEC} s (suggested)") }
        }
        Text(
            when {
                value == null -> "Not read from the daemon"
                value.timeoutSec == null -> "Timer not reported by the daemon"
                else -> "Now: " + setting.label(value.timeoutSec)
            },
            color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(PROMPT_TIMER_STATUS_TAG),
        )
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && value != null }) {
            OutlinedTextField(
                value = value?.onTimeout?.let(setting::onTimeoutLabel) ?: "Not read from the daemon",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                enabled = value != null,
                label = { Text("On expiry, when the agent recommended nothing") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).handCursor(overrideDescendants = true).fillMaxWidth().testTag(PROMPT_ON_TIMEOUT_FIELD_TAG),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = Palette.PopupShape, containerColor = Palette.Raised) {
                setting.ON_TIMEOUT_CHOICES.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(setting.onTimeoutLabel(choice)) },
                        onClick = { expanded = false; if (choice != value?.onTimeout) onSave(null, choice) },
                        modifier = Modifier.testTag(PROMPT_ON_TIMEOUT_OPTION_TAG_PREFIX + choice),
                    )
                }
            }
        }
    }
}

const val PROMPT_TIMER_TAG = "prompt-timer"
const val PROMPT_TIMER_FIELD_TAG = "prompt-timer-field"
const val PROMPT_TIMER_SAVE_TAG = "prompt-timer-save"
const val PROMPT_TIMER_INDEFINITE_TAG = "prompt-timer-indefinite"
const val PROMPT_TIMER_STATUS_TAG = "prompt-timer-status"
const val PROMPT_ON_TIMEOUT_FIELD_TAG = "prompt-on-timeout-field"
const val PROMPT_ON_TIMEOUT_OPTION_TAG_PREFIX = "prompt-on-timeout-option-"

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
    val host = com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost.current
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        UserAvatar(size = 64, portrait = portrait)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        host.pickAttachment("Choose your portrait", true, UserPortrait.MAX_PICK_BYTES) { picked ->
                            if (picked != null) {
                                val bytes = picked.bytes.takeIf { it.isNotEmpty() }
                                val refusal = UserPortrait.refusal(bytes, picked.size)
                                val png = if (refusal == null) host.portraitPng(bytes!!, UserPortrait.SIDE) else null
                                error = refusal ?: if (png == null) "That image could not be read." else null
                                if (png != null) onSave(png)
                            }
                        }
                    },
                    enabled = host.canPickAttachment,
                    modifier = Modifier.testTag(PORTRAIT_CHANGE_TAG),
                ) { Text("Change portrait") }
                TextButton(
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
 * switch, and "Reset to default" (red on dark). Each pick goes straight to [onPick], which applies it live.
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
            Switch(
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

/** CHAT-MD-ENGINE: pick the chat markdown engine among the registered ones; an unknown stored id shows as the built-in fallback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownEngineDropdown(value: String, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val engines = com.superiorsoftllc.infantry.ui.model.ChatMarkdown.engines
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = engines.resolve(value).label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).handCursor(overrideDescendants = true).fillMaxWidth().testTag("markdown-engine-field"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = Palette.PopupShape, containerColor = Palette.Raised) {
            engines.available().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onPick(option.id); expanded = false },
                    modifier = Modifier.testTag("markdown-engine-option-${option.id}"),
                )
            }
        }
    }
}
