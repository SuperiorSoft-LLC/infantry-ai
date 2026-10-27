package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.AboutEntry
import com.superiorsoftllc.agenthq.ui.BLUR_PREF_KEY
import com.superiorsoftllc.agenthq.ui.ConfigAboutSection
import com.superiorsoftllc.agenthq.ui.ConfigDialog
import com.superiorsoftllc.agenthq.ui.ConfigDialogHost
import com.superiorsoftllc.agenthq.ui.ConfigEntry
import com.superiorsoftllc.agenthq.ui.ConfigLogsSection
import com.superiorsoftllc.agenthq.ui.ConfigModulesSection
import com.superiorsoftllc.agenthq.ui.ConfigPairSection
import com.superiorsoftllc.agenthq.ui.ConfigPluginsSection
import com.superiorsoftllc.agenthq.ui.ConfigRegistrySection
import com.superiorsoftllc.agenthq.ui.ConfigSecretsSection
import com.superiorsoftllc.agenthq.ui.ConfigServerUpdateSection
import com.superiorsoftllc.agenthq.ui.ConfigUpdatesSection
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.PairEntry
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.SecretActions
import com.superiorsoftllc.agenthq.ui.THEME_PREF_KEY
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.block.layout.flattened
import com.superiorsoftllc.agenthq.ui.model.UserPortrait
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.pair.applyPairing
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
        ConfigDialog(
            flags,
            onFlags = { next ->
                if (next.blurBehindDialogs != flags.blurBehindDialogs) host.saveUiPref(BLUR_PREF_KEY, if (next.blurBehindDialogs) "on" else "off")
                if (next.progressStaleMin != flags.progressStaleMin) host.saveUiPref(com.superiorsoftllc.agenthq.ui.model.PROGRESS_STALE_PREF_KEY, next.progressStaleMin.toString())
                if (next.chatMarkdownEngine != flags.chatMarkdownEngine) host.saveUiPref(com.superiorsoftllc.agenthq.ui.model.CHAT_MARKDOWN_PREF_KEY, next.chatMarkdownEngine)
                if (next.reasoningPlacement != flags.reasoningPlacement) host.saveUiPref(com.superiorsoftllc.agenthq.ui.model.REASONING_PLACEMENT_PREF_KEY, next.reasoningPlacement.name)
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
            onBrowse = s.pickFile,
            logs = s.logs,
            updates = s.updates,
            serverUpdate = s.serverUpdate,
            serverMismatch = s.serverMismatch,
            theme = Palette.theme,
            onTheme = { next -> Palette.theme = next; host.saveUiPref(THEME_PREF_KEY, next.encode()) },
            about = AboutEntry(host.appBuild, state.connection.daemonBuild, state.connection.isLive),
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
            ),
            onOpenRoles = { ui.showRoles = true },
            limits = state.limitCatalog,
            onLimit = if (contractAtLeast(state.connection.contract, com.superiorsoftllc.agenthq.ui.model.LimitsSetting.SINCE_MAJOR, com.superiorsoftllc.agenthq.ui.model.LimitsSetting.SINCE_MINOR)) { key, value -> actionScope.launch { controller.setLimit(key, value) }; Unit } else null,
            onSendTestQuestion = { state.selectedId?.let { id -> actionScope.launch { controller.sendTestQuestion(id) } } },
            testQuestionReady = state.selectedId != null,
            initialTab = ui.configTab,
            onTab = { ui.configTab = it },
            modules = s.tabs.takeIf { it.loadsAtRunTime },
            sections = { config ->
                CompositionLocalProvider(LocalConfigDialogHost provides config) {
                    RenderPlaced(children.flattened().filter { c -> sectionEntries[c.id]?.let(config::shows) ?: true })
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
 * derived from [ConfigEntry], since the open sections' blocks are the app UI's (Infantry_AppUi's ShellConfigSettings).
 */
private val sectionEntries: Map<String, ConfigEntry> = ConfigEntry.entries.associateBy { e ->
    "$CONFIG." + e.name.lowercase().split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")
}

/** S4c: the open settings group, an empty slot ([CoreShell.slots]) that Infantry_AppUi fills; the trust sections are the shell's. */
const val CONFIG_SETTINGS = "$CONFIG.settings"

val ShellConfig = ConfigBlock(listOf(PlaceholderBlock(CONFIG_SETTINGS, null)) + ShellConfigTrust)
