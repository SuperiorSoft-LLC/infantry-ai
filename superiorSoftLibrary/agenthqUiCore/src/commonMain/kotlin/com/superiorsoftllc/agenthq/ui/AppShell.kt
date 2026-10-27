package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.model.RightPanelWidth
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateState
import com.superiorsoftllc.agenthq.ui.model.SidebarWidth
import com.superiorsoftllc.agenthq.ui.model.UiFlags
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import com.superiorsoftllc.agenthq.ui.shell.ServerChoice
import com.superiorsoftllc.agenthq.ui.shell.ServerSwitcher
import kotlinx.coroutines.CoroutineScope

/**
 * BLOCKS-SHELL-1: the window's presentation state (query, tabs, panel widths, which dialog is open). [App] remembers
 * one and is its only owner; the shell blocks read and write it through [LocalShell].
 */
class ShellState(homeTab: String, rightHomeTab: String, flags: UiFlags, url: String, token: String) {
    var query by mutableStateOf("")
    var roleFilter by mutableStateOf<String?>(null)
    var centerTab by mutableStateOf(homeTab)
    var rightTab by mutableStateOf(rightHomeTab)
    var rightPanelWidth by mutableStateOf(RightPanelWidth.DEFAULT) // in-memory only: no UI-prefs store exists yet
    var sidebarWidth by mutableStateOf(SidebarWidth.DEFAULT) // M2-9: in-memory only, not persisted (owner's call)
    var showConnect by mutableStateOf(false)
    var showConfig by mutableStateOf(false)
    // CONFIG-TABS-1: the configuration tab last shown, so the modal reopens where the user left it (while the app runs).
    var configTab by mutableStateOf<ConfigTab?>(null)
    var showRoles by mutableStateOf(false)
    var showNewAgent by mutableStateOf(false)
    var showBoard by mutableStateOf(false)
    // DIFF-MODAL: the changed file whose diff is open over the window; another project closes it.
    var diffTarget by mutableStateOf<DiffTarget?>(null)
    var flags by mutableStateOf(flags)
    var url by mutableStateOf(url)
    var token by mutableStateOf(token)
    var showServers by mutableStateOf(false)
    var switchError by mutableStateOf<String?>(null)
    // PANEL-TOGGLE-1 (plan rule 6): Ctrl+K on a hidden sidebar shows it first; its search box is focused only once the
    // sidebar is composed again, because focusing a requester whose node is not attached throws.
    var focusSearchPending by mutableStateOf(false)
}

/** BLOCKS-SHELL-1: what [App] hands its blocks each composition: the state, its owner's objects and actions. */
class Shell(
    val state: AppState,
    val controller: Controller,
    val actionScope: CoroutineScope,
    val tabs: TabFactory,
    val homeTab: String,
    val ui: ShellState,
    val chat: ChatPanelState,
    val assistantPanel: AssistantPanelState,
    val assistantChat: ChatPanelState,
    val host: PlatformHost,
    val modalBlur: ModalBlurState,
    val switcher: ServerSwitcher?,
    val serverUpdate: ServerUpdateState,
    val serverMismatch: String?,
    val changeConnection: (() -> Unit)?,
    val pairStore: com.superiorsoftllc.agenthq.ui.pair.SavedServers?,
    val searchFocus: FocusRequester,
    val root: FocusRequester,
    val pickFile: ((title: String) -> String?)?,
    val logs: LogsFolder?,
    val updates: UpdatesEntry?,
    val visible: List<AgentView>,
    val selected: AgentView?,
    val showTab: (String) -> Unit,
    val switchTo: (ServerChoice) -> Unit,
) {
    /** PLUGINS-1: what a tab may reach; [transcript] is the chat's, for the centre strip. */
    fun tabContext(transcript: @Composable () -> Unit, agentsList: @Composable () -> Unit = {}) = TabContext(
        state, controller,
        open = showTab,
        showChat = { ui.centerTab = homeTab; chat.spawnMode = false },
        insertIntoChat = { chat.insert(it) },
        diffTarget = ui.diffTarget,
        onOpenDiff = { ui.diffTarget = it },
        transcript = transcript,
        agentsList = agentsList,
    )
}

/** BLOCKS-SHELL-1: the [Shell] of the window being composed (not static: only the blocks that read it recompose). */
val LocalShell = compositionLocalOf<Shell> { error("LocalShell: no App() above this block") }
