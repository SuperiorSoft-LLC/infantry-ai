package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.AboutEntry
import com.superiorsoftllc.agenthq.ui.BLUR_PREF_KEY
import com.superiorsoftllc.agenthq.ui.ConfigDialog
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.PairEntry
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.SecretActions
import com.superiorsoftllc.agenthq.ui.THEME_PREF_KEY
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.model.UserPortrait
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.pair.applyPairing
import kotlinx.coroutines.launch

/** BLOCKS-SHELL-1: the configuration dialog (the footer cog, Ctrl+,), while it is open. */
object ShellConfig : Block("infantry.shell.config", "infantry.shell.config") {
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
        )
    }
}
