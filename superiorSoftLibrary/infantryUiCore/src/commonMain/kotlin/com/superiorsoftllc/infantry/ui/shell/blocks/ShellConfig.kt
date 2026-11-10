// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.AboutEntry
import com.superiorsoftllc.infantry.ui.BLUR_PREF_KEY
import com.superiorsoftllc.infantry.ui.ConfigAboutSection
import com.superiorsoftllc.infantry.ui.ConfigDialog
import com.superiorsoftllc.infantry.ui.ConfigDialogHost
import com.superiorsoftllc.infantry.ui.ConfigEntry
import com.superiorsoftllc.infantry.ui.ConfigLogsSection
import com.superiorsoftllc.infantry.ui.ConfigModulesSection
import com.superiorsoftllc.infantry.ui.ConfigPairSection
import com.superiorsoftllc.infantry.ui.ConfigPluginsSection
import com.superiorsoftllc.infantry.ui.ConfigRegistrySection
import com.superiorsoftllc.infantry.ui.ConfigSecretsSection
import com.superiorsoftllc.infantry.ui.ConfigServerUpdateSection
import com.superiorsoftllc.infantry.ui.ConfigUpdatesSection
import com.superiorsoftllc.infantry.ui.configSectionNode
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.PairEntry
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.SecretActions
import com.superiorsoftllc.infantry.ui.SecretProjectRef
import com.superiorsoftllc.infantry.ui.THEME_PREF_KEY
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.infantry.ui.block.layout.asPlaceholder
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.block.layout.flattened
import com.superiorsoftllc.infantry.ui.model.UserPortrait
import com.superiorsoftllc.infantry.ui.model.contractAtLeast
import com.superiorsoftllc.infantry.ui.pair.applyPairing
import com.superiorsoftllc.infantry.ui.plugin.config.ConfigPlan
import com.superiorsoftllc.infantry.ui.plugin.config.GeneratedCards
import com.superiorsoftllc.infantry.ui.plugin.config.PlanFormCard
import com.superiorsoftllc.infantry.ui.plugin.config.generatedEntries
import kotlinx.coroutines.launch

private const val CONFIG = "infantry.shell.config"

/** The open dialog's host; null outside [ConfigBlock] (a section moved elsewhere draws nothing). */
private val LocalConfigDialogHost = compositionLocalOf<ConfigDialogHost?> { null }

/**
 * BLOCKS-SHELL-1: the configuration dialog (the footer cog, Ctrl+,), while it is open.
 * SHELL-EMPTY-DROPIN S3: a container of child blocks `infantry.shell.config.<entry>`, one per section, drawn straight into
 * the selected tab's scrolled column, top to bottom. The frame (dialog, title, lifetime note, search and its results, tab
 * strip, Done) stays its own. The container is locked, and so are its trust sections ([ShellConfigTrust]); the settings
 * (the `infantry.shell.config.settings` slot, S4c: Infantry_AppUi fills it) are open. A child whose id is a section's ([ConfigSectionBlock.entry]) is drawn only on that
 * section's tab, an override of it too (it keeps the id); any other child (an injected one) on every tab.
 */
class ConfigBlock(children: List<Block>, place: Place = Place()) : Block(CONFIG, CONFIG, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val ui = s.ui
        if (!ui.showConfig) return
        val state = s.state
        val controller = s.controller
        val actionScope = s.actionScope
        val host = s.host
        val flags = ui.flags
        // CONFIG-11: the daemon's compiled sections join the generated dialog (only when its contract serves them); below it, none.
        val serves = state.connection.servesConfigSchema
        androidx.compose.runtime.SideEffect {
            s.tabs.config.applyDaemon(
                schema = state.daemonConfigSchema.takeIf { serves },
                values = state.daemonConfigValues.takeIf { serves },
                error = state.daemonConfigError,
                writer = if (serves) { node, key, value -> controller.setDaemonConfig(node, key, value) is com.superiorsoftllc.infantry.ui.net.CommandResult.Sent } else null,
            )
        }
        ConfigDialog(
            flags,
            onFlags = { next ->
                if (next.blurBehindDialogs != flags.blurBehindDialogs) host.saveUiPref(BLUR_PREF_KEY, if (next.blurBehindDialogs) "on" else "off")
                if (next.progressStaleMin != flags.progressStaleMin) host.saveUiPref(com.superiorsoftllc.infantry.ui.model.PROGRESS_STALE_PREF_KEY, next.progressStaleMin.toString())
                if (next.chatMarkdownEngine != flags.chatMarkdownEngine) host.saveUiPref(com.superiorsoftllc.infantry.ui.model.CHAT_MARKDOWN_PREF_KEY, next.chatMarkdownEngine)
                if (next.reasoningPlacement != flags.reasoningPlacement) host.saveUiPref(com.superiorsoftllc.infantry.ui.model.REASONING_PLACEMENT_PREF_KEY, next.reasoningPlacement.name)
                // AVATAR-SEAM-1: the avatar style is a UI preference, remembered like the markdown engine.
                if (next.agentAvatarStyle != flags.agentAvatarStyle) host.saveUiPref(com.superiorsoftllc.infantry.ui.AGENT_AVATAR_PREF_KEY, next.agentAvatarStyle)
                next.commsSound.save(host, flags.commsSound)
                ui.flags = next
            }, onDismiss = { ui.showConfig = false },
            keepAliveMinutes = state.keepAliveMinutes,
            userPortrait = if (contractAtLeast(state.connection.contract, UserPortrait.SINCE_MAJOR, UserPortrait.SINCE_MINOR)) state.userPortrait to { png: ByteArray? -> actionScope.launch { controller.setUserPortrait(png) }; Unit } else null,
            onKeepAliveMinutes = { minutes -> actionScope.launch { controller.setKeepAliveMinutes(minutes) } },
            cliLocations = state.cliLocations,
            onCliLocation = { id, path -> actionScope.launch { controller.setCliLocation(id, path) } },
            harnessInstall = state.harnessInstall,
            harnessInstallBusy = state.harnessInstallBusy,
            harnessInstallLog = state.harnessInstallLog,
            onHarnessCheck = { latest -> actionScope.launch { controller.checkHarnesses(latest) } },
            onHarnessInstall = { ids, update -> actionScope.launch { controller.installHarnesses(ids, update) } },
            toolApproval = state.toolApproval,
            onToolApproval = { mode -> actionScope.launch { controller.setToolApproval(mode) } },
            // ASK-TIMER-2: the prompt timer, under Tool approval (a daemon older than 1.139.0 has none).
            promptTimer = state.promptTimer,
            onPromptTimer = if (contractAtLeast(state.connection.contract, com.superiorsoftllc.infantry.ui.model.PromptTimerSetting.SINCE_MAJOR, com.superiorsoftllc.infantry.ui.model.PromptTimerSetting.SINCE_MINOR)) {
                { seconds, onTimeout -> actionScope.launch { controller.setPromptTimer(seconds, onTimeout) } }
            } else null,
            // HARNESS-PLUGIN-4 H5a: the daemon's harness state per module (1.145.0); an older daemon has none to read.
            harnessModules = if (contractAtLeast(state.connection.contract, com.superiorsoftllc.infantry.ui.net.HttpPluginsApi.SINCE_MAJOR, com.superiorsoftllc.infantry.ui.net.HttpPluginsApi.SINCE_MINOR)) {
                val api = androidx.compose.runtime.remember(ui.url, ui.token) { com.superiorsoftllc.infantry.ui.net.HttpPluginsApi(ui.url, ui.token.ifBlank { null }) }
                api::harnessStatuses
            } else null,
            onBrowse = s.pickFile,
            logs = s.logs,
            updates = s.updates,
            serverUpdate = s.serverUpdate,
            serverMismatch = s.serverMismatch,
            theme = Palette.theme,
            onTheme = { next -> Palette.theme = next; host.saveUiPref(THEME_PREF_KEY, next.encode()) },
            about = AboutEntry(
                host.appBuild, state.connection.daemonBuild, state.connection.isLive,
                website = state.siteUrl,
                links = com.superiorsoftllc.infantry.ui.model.SourceLinks.of(
                    state.sourceRepo,
                    com.superiorsoftllc.infantry.ui.model.ReportFacts(host.appBuild, state.connection.daemonBuild, com.superiorsoftllc.infantry.ui.platformName(), state.connection.daemonProvider),
                ),
            ),
            secrets = state.secrets,
            pair = PairEntry(ui.url, ui.token.ifBlank { null }, null) { link ->
                // PAIR-1: save the server + token through the host's store, then connect with the bearer.
                applyPairing(link, s.pairStore) { u, t ->
                    ui.url = u; ui.token = t.orEmpty(); ui.showConfig = false
                    controller.connect(u, t.orEmpty())
                }
            },
            secretActions = SecretActions(
                set = { n, v -> actionScope.launch { controller.setSecret(n, v) } },
                clear = { n -> actionScope.launch { controller.clearSecret(n) } },
                import = { n -> actionScope.launch { controller.importSecret(n) } },
                // SECRETS-2: the key/value part only against a daemon that has it (1.135.0).
                setField = if (controller.supportsKeyValueSecrets()) { n, f, v -> actionScope.launch { controller.setSecretField(n, f, v) }; Unit } else null,
                deleteField = { n, f -> actionScope.launch { controller.deleteSecretField(n, f) } },
                delete = { n -> actionScope.launch { controller.deleteSecret(n) } },
                // SECRETS-2b: an app secret can move to the project on screen (1.152.0).
                move = if (controller.supportsProjectSecrets()) { n, from, to -> actionScope.launch { controller.moveSecret(n, from, to) }; Unit } else null,
                currentProject = state.projects.firstOrNull { it.id == state.activeProjectId }?.let { SecretProjectRef(it.id, it.name) },
                setAuthMode = { id, m -> actionScope.launch { controller.setHarnessAuthMode(id, m) } },
                login = { id -> actionScope.launch { controller.startHarnessLogin(id); ui.showConfig = false } },
            ),
            onOpenRoles = { ui.showRoles = true },
            limits = state.limitCatalog,
            onLimit = if (contractAtLeast(state.connection.contract, com.superiorsoftllc.infantry.ui.model.LimitsSetting.SINCE_MAJOR, com.superiorsoftllc.infantry.ui.model.LimitsSetting.SINCE_MINOR)) { key, value -> actionScope.launch { controller.setLimit(key, value) }; Unit } else null,
            onSendTestQuestion = { state.selectedId?.let { id -> actionScope.launch { controller.sendTestQuestion(id) } } },
            testQuestionReady = state.selectedId != null,
            initialTab = ui.configTab,
            onTab = { ui.configTab = it },
            modules = s.tabs.takeIf { it.loadsAtRunTime },
            // S4c: only the sections among the children are offered; an unfilled settings slot leaves the trust tabs.
            drawn = children.flattened().filter { it !is PlaceholderBlock }.mapNotNull { sectionEntries[it.id] }.toSet(),
            // CONFIG-5: with a compiled manifest (snapshot) every tab and card is generated from it; without one the
            // hand-written sections and tabs are drawn as before.
            plan = { present -> s.tabs.config.planDeclarations?.let { snap -> ConfigPlan.of(snap, present, children.flattened().filter { it !is PlaceholderBlock }.map { it.id }.toSet()) } },
            sections = { config ->
                // CONFIG-3: a generated tab (Appearance) draws its cards from the compiled manifest first, instead of the
                // hand-written sections they replace; with no card (no jar, or no snapshot and no local declaration) nothing changes.
                val plan = config.plan
                val all = children.flattened().filter { c -> c !is PlaceholderBlock }
                val surface = all.firstOrNull { it.id == SETTINGS_SLOT }
                val kids = all.filter { it.id != SETTINGS_SLOT }
                if (surface != null) {
                    // CONFIG-10b: a replacement settings plugin draws the body (the frame and trust sections stay core).
                    CompositionLocalProvider(LocalConfigDialogHost provides config) { RenderPlaced(listOf(surface)) }
                } else if (plan != null) {
                    val placed = plan.cards.mapNotNull { it.block }.toSet()
                    CompositionLocalProvider(LocalConfigDialogHost provides config) {
                        for (card in plan.cardsOf(config.tabLabel.orEmpty())) {
                            // The escape hatch: the card is the named fill; any other card is a form.
                            if (card.block != null) RenderPlaced(kids.filter { it.id == card.block })
                            else PlanFormCard(s.tabs, card, config.highlight, config.highlightKey)
                        }
                        RenderPlaced(kids.filter { c -> c.id !in placed }.filter { c -> sectionEntries[c.id]?.let { it in plan.uncovered && config.shows(it) } ?: true })
                    }
                } else {
                    val generated = generatedEntries(s.tabs, config.tab)
                    CompositionLocalProvider(LocalConfigDialogHost provides config) {
                        GeneratedCards(s.tabs, config.tab, config.highlight)
                        RenderPlaced(kids.filter { c -> sectionEntries[c.id]?.let { it !in generated && config.shows(it) } ?: true })
                    }
                }
            },
        )
    }

    override fun withChildren(children: List<Block>) = ConfigBlock(children, place)
}

/** One section of the dialog ([entry]); its placement goes on the section's column. */
class ConfigSectionBlock(
    val entry: ConfigEntry,
    name: String,
    private val draw: @Composable (ConfigDialogHost, Modifier) -> Unit,
) : Block("configSection", "$CONFIG.$name") {
    @Composable
    override fun Render(scope: BlockScope) {
        draw(LocalConfigDialogHost.current ?: return, scope.modifier)
    }
}

/**
 * The trust kernel (docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md, 3d): where a plugin is disabled, a secret is typed, a device
 * is paired, an update is checked or approved and the logs are found; P1 (f60f4d97) adds where plugins are registered
 * (`registry`), which step 2 makes where a registered child is turned off. Each is in [CoreShell.locked], so no module
 * replaces or wraps one, and a bad drop can always be undone from Configuration > Plugins.
 */
val ShellConfigTrust: List<ConfigSectionBlock> = listOf(
    // POLICY-1: the ONE switch (which policy preset is active) and what it turns off; locked like the rest of the kernel.
    ConfigSectionBlock(ConfigEntry.POLICY, "policy") { c, m -> com.superiorsoftllc.infantry.ui.ConfigPolicySection(c, m) },
    ConfigSectionBlock(ConfigEntry.PLUGINS, "plugins") { c, m -> ConfigPluginsSection(c, m) },
    ConfigSectionBlock(ConfigEntry.MODULES, "modules") { c, m -> ConfigModulesSection(c, m) },
    ConfigSectionBlock(ConfigEntry.REGISTRY, "registry") { c, m -> ConfigRegistrySection(c, m) },
    ConfigSectionBlock(ConfigEntry.SECRETS, "secrets") { c, m -> ConfigSecretsSection(c, m) },
    ConfigSectionBlock(ConfigEntry.PAIR, "pair") { c, m -> ConfigPairSection(c, m) },
    ConfigSectionBlock(ConfigEntry.ABOUT, "about") { c, m -> ConfigAboutSection(c, m) },
    ConfigSectionBlock(ConfigEntry.UPDATES, "updates") { c, m -> ConfigUpdatesSection(c, m) },
    ConfigSectionBlock(ConfigEntry.SERVER_UPDATE, "serverUpdate") { c, m -> ConfigServerUpdateSection(c, m) },
    ConfigSectionBlock(ConfigEntry.LOGS, "logs") { c, m -> ConfigLogsSection(c, m) },
)

/**
 * Section node id -> its entry, for the container's tab filter: `infantry.shell.config.<entry in lowerCamelCase>`. S4c:
 * derived from [ConfigEntry], since the open sections' blocks are ShellConfigSettings'.
 */
private val sectionEntries: Map<String, ConfigEntry> = ConfigEntry.entries.associateBy { e -> configSectionNode(e) }

/** S4c: the open settings group, the shell's own since RUNTIME-26 (replaceable by id); the trust sections are locked. */
const val CONFIG_SETTINGS = "$CONFIG.settings"

/**
 * RUNTIME-19: the sections an official plugin owns, one empty slot each (`infantry.shell.config.<name>`): Usage fills limits,
 * Roles fills roles, Alerts fills voice and incomingSound, Attention fills questions, Runners fills runners. An unfilled slot draws nothing and
 * offers no tab entry.
 */
val CONFIG_OWNED_SECTION_SLOTS: List<String> = listOf("limits", "roles", "voice", "incomingSound", "questions", "runners").map { "$CONFIG.$it" }

/**
 * CONFIG-10b: the settings surface slot (docs/CONFIG-MANIFEST-DESIGN.md section 12). Empty, the dialog draws the generated
 * body from the snapshot; a plugin's fill (fills[].replaces "Infantry_Settings") draws in its place, on the same config.
 */
const val SETTINGS_SLOT = "infantry.shell.settings"

val ShellConfig = ConfigBlock(
    listOf(ShellConfigSettings.asPlaceholder(), PlaceholderBlock(SETTINGS_SLOT, null)) + CONFIG_OWNED_SECTION_SLOTS.map { PlaceholderBlock(it, null) } + ShellConfigTrust,
)
