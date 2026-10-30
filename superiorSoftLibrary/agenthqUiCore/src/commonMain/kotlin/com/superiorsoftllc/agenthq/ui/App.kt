package com.superiorsoftllc.agenthq.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import com.superiorsoftllc.agenthq.ui.net.ImageUploadOutcome
import com.superiorsoftllc.agenthq.ui.protocol.ImageUploadWire
import com.superiorsoftllc.agenthq.ui.pair.applyPairing
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.ServerChoice
import com.superiorsoftllc.agenthq.ui.shell.ServerSwitcher
import com.superiorsoftllc.agenthq.ui.shell.PickedImage
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.model.NoticeKind
import com.superiorsoftllc.agenthq.ui.model.localNotices
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.superiorsoftllc.agenthq.ui.model.AgentGroup
import com.superiorsoftllc.agenthq.ui.model.parseHexColor
import com.superiorsoftllc.agenthq.ui.model.roleHue
import androidx.compose.foundation.horizontalScroll
import com.superiorsoftllc.agenthq.ui.model.RightPanelWidth
import com.superiorsoftllc.agenthq.ui.model.SidebarWidth
import com.superiorsoftllc.agenthq.ui.model.CompactScreen
import com.superiorsoftllc.agenthq.ui.model.PanelLayout
import com.superiorsoftllc.agenthq.ui.model.PanelSide
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import com.superiorsoftllc.agenthq.ui.model.isSafeChatUrl
import com.superiorsoftllc.agenthq.ui.model.AgentScope
import com.superiorsoftllc.agenthq.ui.model.SlashSuggestion
import com.superiorsoftllc.agenthq.ui.model.isSlashCommand
import com.superiorsoftllc.agenthq.ui.model.moveSlashSelection
import com.superiorsoftllc.agenthq.ui.model.slashHeader
import com.superiorsoftllc.agenthq.ui.model.HarnessSwitch
import com.superiorsoftllc.agenthq.ui.model.switchChipText
import com.superiorsoftllc.agenthq.ui.model.slashPick
import com.superiorsoftllc.agenthq.ui.model.slashSubmitOnEnter
import com.superiorsoftllc.agenthq.ui.model.slashSuggestions
import com.superiorsoftllc.agenthq.ui.model.slashTyped
import com.superiorsoftllc.agenthq.ui.model.branchLine
import com.superiorsoftllc.agenthq.ui.model.changesView
import com.superiorsoftllc.agenthq.ui.model.ticketBranchFor
import com.superiorsoftllc.agenthq.ui.model.ticketTagFull
import com.superiorsoftllc.agenthq.ui.model.ticketTagLabel
import com.superiorsoftllc.agenthq.ui.model.reportFlagOf
import com.superiorsoftllc.agenthq.ui.model.watchFlagOf
import com.superiorsoftllc.agenthq.ui.model.UiFlags
import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatItem
import com.superiorsoftllc.agenthq.ui.model.ChatSpan
import com.superiorsoftllc.agenthq.ui.model.ChatStatus
import com.superiorsoftllc.agenthq.ui.model.OutputLine
import com.superiorsoftllc.agenthq.ui.model.USER_AVATAR_ID
import com.superiorsoftllc.agenthq.ui.model.UserPortrait
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.model.avatarFor
import com.superiorsoftllc.agenthq.ui.model.chatItems
import com.superiorsoftllc.agenthq.ui.model.chatLineBlocks
import com.superiorsoftllc.agenthq.ui.model.parseChatInline
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.SessionExecutor
import com.superiorsoftllc.agenthq.ui.model.COMPACTING_LABEL
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.activityTimestamp
import com.superiorsoftllc.agenthq.ui.model.isBusy
import com.superiorsoftllc.agenthq.ui.model.QueuedPrompt
import com.superiorsoftllc.agenthq.ui.model.interruptBlocker
import com.superiorsoftllc.agenthq.ui.model.queuedFor
import com.superiorsoftllc.agenthq.ui.model.isRunning
import com.superiorsoftllc.agenthq.ui.model.needsInput
import com.superiorsoftllc.agenthq.ui.model.insertIntoPrompt
import com.superiorsoftllc.agenthq.ui.model.promptBlocker
import com.superiorsoftllc.agenthq.ui.model.parseNodes
import com.superiorsoftllc.agenthq.ui.model.stateLabel
import com.superiorsoftllc.agenthq.ui.model.visibleAgents
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.plugin.HostEvents
import com.superiorsoftllc.agenthq.ui.plugin.PanelSections
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.DaemonLink
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.agenthq.plugin.api.Events
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.ui.plugin.CompiledModules
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.LocalHostSurface
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * PLUGINS-1: the tabs come from modules through a [TabFactory]; the window never names one. The default is the compiled-in
 * modules (plugins/core); a host passes its own factory to [App] once it loads more.
 */
val LocalTabFactory = staticCompositionLocalOf { CompiledModules.factory }

/** The id of the Attention tab, opened by the title bar's attention chip (a no-op when no module provides it). */
const val ATTENTION_TAB_ID = "infantry.attention"

/**
 * The whole window. Layout follows the reference: agent list left, live output + prompt centre,
 * worktree/review right, status bar below. Everything shown is derived from [AppState]; the only
 * local state is presentation (query, tabs, draft text).
 *
 * Keyboard (every control also has a button):
 *  Ctrl+K search | Alt+Up/Down previous/next agent | Ctrl+J next agent needing input
 *  Ctrl+N spawn mode | Ctrl+Shift+N switch agent (SWAP-AGENT-1) | Ctrl+R resume | Ctrl+Shift+K stop background processes | Ctrl+Alt+K end session
 *  Ctrl+<digit> the tab whose provider claims that digit (core: 1/2/5/9/0 centre, 3/4/6/7 right)
 *  Ctrl+8 board modal (M2-8: the sidebar Board button)
 *  Ctrl+L connection | Ctrl+/ focus prompt | Ctrl+, configuration (the footer cog)
 */
@Composable
fun App(
    controller: Controller,
    initialUrl: String,
    initialToken: String?,
    /** CLIPATH-1: the platform's file picker for Settings > CLI locations; null = no Browse button. */
    pickFile: ((title: String) -> String?)? = null,
    /** PKG-3: the installed app's log folder for Settings > Logs; null = this launch writes no log file (no section). */
    logs: LogsFolder? = null,
    /** UPDATE-1: the installed app's "Check for updates" for Settings > Updates; null = no section. */
    updates: UpdatesEntry? = null,
    /** PLUGINS-1: where the tabs come from; the core modules unless the host loaded more. */
    tabs: TabFactory = CompiledModules.factory,
) {
    val state by controller.state.collectAsState()
    val actionScope = rememberCoroutineScope()
    val homeTab = tabs.home(TabContainers.CENTER).orEmpty()
    // MOBILE-CONNCHIP-1: a host may route the connection chip to its own connect screen (Android).
    val changeConnection = LocalPlatformHost.current.onChangeConnection
    val pairStore = LocalPlatformHost.current.savedServers
    // CHAT-PANEL-1: the main window's chat state (draft, attachments, New agent mode, Switch picker); one per window, as before.
    val chat = remember { ChatPanelState() }
    // ASSISTANT-1: the Assistant popup (open / expanded / width) and its chat state; both live in the window so a close keeps them.
    val assistantPanel = remember { AssistantPanelState() }
    val assistantChat = rememberChatPanelState(state.assistant?.id)
    // MODAL-BLUR: the one blur state every dialog reports into; the setting is remembered by the host.
    val host = LocalPlatformHost.current
    // BLOCKS-SHELL-1: the window's presentation state, owned here and read by the shell blocks.
    val ui = remember {
        ShellState(
            homeTab, tabs.home(TabContainers.RIGHT).orEmpty(),
            UiFlags(
                commsSound = com.superiorsoftllc.agenthq.ui.sound.IncomingCommsSoundSettings.load(host),
                blurBehindDialogs = host.uiPref(BLUR_PREF_KEY) != "off",
                chatMarkdownEngine = host.uiPref(com.superiorsoftllc.agenthq.ui.model.CHAT_MARKDOWN_PREF_KEY) ?: com.superiorsoftllc.agenthq.ui.model.BuiltInChatMarkdownEngine.ID,
                reasoningPlacement = com.superiorsoftllc.agenthq.ui.model.ReasoningPlacement.fromId(host.uiPref(com.superiorsoftllc.agenthq.ui.model.REASONING_PLACEMENT_PREF_KEY)),
                progressStaleMin = host.uiPref(com.superiorsoftllc.agenthq.ui.model.PROGRESS_STALE_PREF_KEY)?.toIntOrNull()?.takeIf { it > 0 } ?: com.superiorsoftllc.agenthq.ui.model.PROGRESS_STALE_DEFAULT_MIN,
            ),
            initialUrl, initialToken.orEmpty(),
        )
    }
    LaunchedEffect(state.activeProject?.id) { ui.diffTarget = null }
    val flags = ui.flags
    val modalBlur = remember { ModalBlurState(supported = host.supportsBlur) }
    val chatBlockRenderers = remember { ChatBlockRenderers().also { it.override("callout", CalloutBlockRenderer) } }
    // THEME-1: the stored theme, applied once before the first themed frame; Palette.theme is its only owner from here on.
    remember(host) { Palette.theme = com.superiorsoftllc.agenthq.ui.model.ThemeSettings.decode(host.uiPref(THEME_PREF_KEY)) }
    // CONFIG-4: device-scope config reads this host's prefs (and migrates the old pref keys once).
    remember(tabs, host) { tabs.config.attachDevice(com.superiorsoftllc.agenthq.ui.plugin.config.HostDevicePrefs(host)) }
    // CONFIG-3: set when the flag changes, not on every recomposition, so a plugin's blur config (Infantry_Appearance) can set it too.
    remember(flags.blurBehindDialogs) { modalBlur.enabled = flags.blurBehindDialogs }
    val url = ui.url
    val token = ui.token
    // SWITCH-1: "This computer" + saved servers; a host with neither a local daemon nor a store has no switcher.
    val platformHost = LocalPlatformHost.current
    val switcher = remember(platformHost) {
        if (platformHost.localServer == null && platformHost.savedServers == null) null
        else ServerSwitcher(platformHost.savedServers, platformHost.localServer, platformHost.activeServer(), platformHost::setActiveServer)
    }
    // SERVER-UPDATE-4: the connected daemon's version and updater, over the same host and token as the socket.
    val serverUpdate = remember(url, token) {
        com.superiorsoftllc.agenthq.ui.model.ServerUpdateState(com.superiorsoftllc.agenthq.ui.net.HttpServerUpdateApi(url, token.ifBlank { null }), actionScope)
    }
    val serverView by serverUpdate.view.collectAsState()
    // DEMO-FIX-1: a restricted (read-only) daemon policy refuses these two reads; the window does not make them.
    serverUpdate.available = !state.connection.policy.restricted
    LaunchedEffect(serverUpdate, state.connection.isLive) { if (state.connection.isLive) serverUpdate.onReconnected() }
    val serverMismatch = com.superiorsoftllc.agenthq.ui.model.ServerVersionCheck.mismatch(host.appVersion, serverView.status?.version, state.connection.contract)
    fun switchTo(c: ServerChoice) {
        ui.switchError = switcher?.select(c) { u, t -> ui.url = u; ui.token = t.orEmpty(); controller.connect(u, t) }
    }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(ui.focusSearchPending, state.panels.leftShown) {
        if (ui.focusSearchPending && state.panels.leftShown) {
            ui.focusSearchPending = false
            // MOBILE-LAYOUT-1: the panes sit in a BoxWithConstraints, which composes the sidebar during layout, after
            // this effect starts; the search box's requester is attached only once a frame has gone by.
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
        }
    }
    /** PANEL-TOGGLE-1 (plan rule 5): a right-panel tab chosen from outside the panel shows the panel first. */
    fun showRightTab(tab: String) {
        // BUG-COMPACT-ATTN-1: in a compact window reveal() would leave the list on screen (leftShown wins in
        // compactScreen()), so every right-panel entry point (attention, Ctrl+3/4/6/7) switches to the RIGHT screen.
        if (state.panels.compactWindow) controller.showCompactScreen(CompactScreen.RIGHT) else controller.revealPanel(PanelSide.RIGHT)
        ui.rightTab = tab
    }
    /** PLUGINS-1: shows a tab by id in its own container (the right one is revealed first, as above). */
    fun showTab(id: String) {
        val tab = tabs.find(id) ?: return
        // SETTINGS-TAB-1: a sub-tab shows inside its host tab (shortcuts and links reach it there).
        tabs.hostOf(id)?.let { host -> ui.subTabs[host] = id; return showTab(host) }
        when (tab.container) {
            TabContainers.CENTER -> ui.centerTab = tab.id
            TabContainers.RIGHT -> showRightTab(tab.id)
        }
    }
    val root = remember { FocusRequester() }
    // DEMO-MERGE-1: the write policy a module provides under the frame, mirrored here for the shortcuts below (default allow-all).
    val writePolicy = remember { androidx.compose.runtime.mutableStateOf(com.superiorsoftllc.agenthq.ui.shell.WritePolicy.AllowAll) }

    DisposableEffect(controller) {
        onDispose { controller.disconnect() }
    }

    LaunchedEffect(Unit) {
        controller.connect(url, token)
        root.requestFocus()
    }
    // PLUGINS-1: the plugins' gated daemon access goes to the daemon this window talks to; the bus hears what is shown.
    LaunchedEffect(url, token) { DaemonLink.window.connect(url, token) }
    // PLUGIN-ENDPOINTS-2: a module's endpoint credential comes over this window's (owner) connection, never to the plugin.
    LaunchedEffect(controller) { DaemonLink.window.pluginSecret = controller::pluginSecret }
    // P1 step 2 (6s B): once the link is live, the merged config tree seeds the daemon's node_config and the window takes its copy.
    val linkLive = state.connection.isLive
    tabs.config.daemonRowsClosed = state.connection.policy.restricted
    LaunchedEffect(tabs, linkLive) { if (linkLive && tabs.config.load != NodeConfigLoad.LOADED) tabs.config.load() }
    // P1 step 2 (6s E): a tab its strip's settings switch off while it is open hands the strip back to its home tab.
    LaunchedEffect(tabs.config.stored) {
        // SETTINGS-TAB-1: grouping switched on while a sub-tab is open moves into its host on that sub-tab; switched
        // off while the host is open shows the sub-tab that was picked in it, back in the strip.
        tabs.hostOf(ui.centerTab)?.let { host -> ui.subTabs[host] = ui.centerTab; ui.centerTab = host }
        if (tabs.isHost(ui.centerTab) && !tabs.grouped(ui.centerTab)) {
            ui.centerTab = ui.subTabs[ui.centerTab]?.takeIf { tabs.find(it) != null } ?: homeTab
        }
        if (tabs.isDisabled(ui.centerTab)) ui.centerTab = homeTab
        if (tabs.isDisabled(ui.rightTab)) tabs.home(TabContainers.RIGHT)?.let { ui.rightTab = it }
    }
    LaunchedEffect(ui.centerTab) { tabs.events.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to ui.centerTab, "container" to TabContainers.CENTER))) }
    LaunchedEffect(ui.rightTab) { tabs.events.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to ui.rightTab, "container" to TabContainers.RIGHT))) }
    LaunchedEffect(state.selectedId) { state.selectedId?.let { tabs.events.publish(PluginEvent(Events.SESSION_SELECTED, mapOf("id" to it))) } }
    // HOOKS-1: modules.loaded once the factory has bound its modules; ticket.changed for each ticket a board update changes.
    LaunchedEffect(tabs) { tabs.events.publish(HostEvents.modulesLoaded(tabs)) }
    val lastTickets = remember { arrayOf(state.tickets) }
    LaunchedEffect(state.tickets) {
        HostEvents.ticketChanges(lastTickets[0], state.tickets).forEach { tabs.events.publish(it) }
        lastTickets[0] = state.tickets
    }
    // A refused token is the one failure the human must act on: open the dialog instead of retrying.
    // MOBILE-REFUSED-1: a rejected token goes to the host's own connect screen when it has one (Android saves there;
    // the shared dialog only edits memory). Desktop has no hook and keeps the dialog.
    LaunchedEffect(state.connection.state) {
        if (state.connection.state == ConnState.REFUSED) changeConnection?.invoke() ?: run { ui.showConnect = true }
    }

    // M2-4: the list renders status groups, so keyboard navigation walks that same order.
    val visible = state.groupedAgents(visibleAgents(state, ui.query, ui.roleFilter, agentScope = flags.agentScope)).flatMap { it.second }
    val selected = state.selected

    fun move(delta: Int) {
        if (visible.isEmpty()) return
        val i = visible.indexOfFirst { it.id == state.selectedId }
        controller.select(visible[(if (i < 0) 0 else (i + delta).mod(visible.size))].id)
        chat.spawnMode = false
    }
    // POLICY-1: each write shortcut is a named action, decided like its control (the daemon's policy and the mirrored write policy).
    fun may(actionId: String): Boolean = com.superiorsoftllc.agenthq.ui.shell.ActionGate.of(
        actionId, com.superiorsoftllc.agenthq.plugin.api.ActionKind.WRITE, state.connection.isLive, state.connection.policy, writePolicy.value,
    ).enabled
    fun key(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when {
            e.isCtrlPressed && e.key == Key.K && e.isShiftPressed && may("host.processes.reap") -> { selected?.let { a -> actionScope.launch { controller.stopBackgroundProcesses(a.id) } }; true }
            e.isCtrlPressed && e.isAltPressed && e.key == Key.K && may("agents.kill") -> { selected?.let { a -> actionScope.launch { controller.endSession(a.id) } }; true }
            e.isCtrlPressed && e.key == Key.K -> {
                if (state.panels.leftShown) searchFocus.requestFocus()
                else { controller.revealPanel(PanelSide.LEFT); ui.focusSearchPending = true }
                true
            }
            e.isAltPressed && e.key == Key.DirectionDown -> { move(1); true }
            e.isAltPressed && e.key == Key.DirectionUp -> { move(-1); true }
            e.isCtrlPressed && e.key == Key.J -> {
                state.orderedAgents.firstOrNull { needsInput(it, state.waiting, state.openPrompts, state.openQuestionSessions) && it.id != state.selectedId }?.let { controller.select(it.id) }
                true
            }
            e.isCtrlPressed && e.isShiftPressed && e.key == Key.N -> { selected?.let { chat.openSwitch(it.id) }; true }
            e.isCtrlPressed && e.key == Key.N && may("agents.spawn") -> { chat.spawnMode = !chat.spawnMode; chat.focus.requestFocus(); true }
            e.isCtrlPressed && e.key == Key.R -> {
                selected?.let { a -> chat.resume(controller, actionScope, a) }
                true
            }
            e.isCtrlPressed && e.key == Key.Eight -> { ui.showBoard = true; true }
            e.isCtrlPressed && digitOf(e.key)?.let(tabs::byShortcut) != null -> { showTab(tabs.byShortcut(digitOf(e.key)!!)!!.id); true }
            e.isCtrlPressed && e.key == Key.L -> { ui.showConnect = true; true }
            e.isCtrlPressed && e.key == Key.Comma && (may("settings.save") || state.connection.policy.canSwitch) -> { ui.showConfig = true; true }
            e.isCtrlPressed && e.key == Key.Slash -> { chat.focus.requestFocus(); true }
            else -> false
        }
    }

    // TOAST-1: a module's Notifications.notify toasts in this window.
    remember(tabs, controller) { tabs.toasts = controller.toasts }
    val shell = Shell(
        state, controller, actionScope, tabs, homeTab, ui, chat, assistantPanel, assistantChat, host, modalBlur,
        switcher, serverUpdate, serverMismatch, changeConnection, pairStore, searchFocus, root, pickFile, logs, updates,
        visible, selected, showTab = ::showTab, switchTo = ::switchTo,
    )
    AgentHqTheme {
      // M2-8: the dock's overlay layer stacks the Board modal over the window.
      // IMG-VIEW-1: chat images open full size over the whole window (the Board keeps its own host, above it).
      androidx.compose.runtime.CompositionLocalProvider(
          LocalModalBlur provides modalBlur,
          LocalProgressStaleMin provides flags.progressStaleMin,
          LocalReasoningPlacement provides flags.reasoningPlacement,
          LocalTabFactory provides tabs,
          LocalChatMarkdown provides remember(flags.chatMarkdownEngine) { ChatMarkdownConfig(com.superiorsoftllc.agenthq.ui.model.ChatMarkdown.engines.resolve(flags.chatMarkdownEngine), chatBlockRenderers) },
          LocalShell provides shell,
          // S2: what the blocks read instead of [shell]'s App/Controller internals.
          com.superiorsoftllc.agenthq.ui.shell.LocalShellContext provides com.superiorsoftllc.agenthq.ui.shell.CoreShellContext(shell, LocalRunConsole.current),
          // TOAST-1: every block can toast; a subtree may provide its own (nearest wins).
          LocalToast provides controller.toasts,
          com.superiorsoftllc.agenthq.ui.shell.LocalWritePolicySink provides writePolicy,
      ) {
      // BLOCKS-LAYOUT L3: the window is one block tree (L5: with the modules' overrides in); the docked column (`infantry.shell.frame`) wears the surface.
      // BUG-PANEL-TOGGLE-1: the docked column is itself the focus target. A requester on a non-focusable node hands
      // focus to its first focusable child (the title bar's left panel toggle), which opened that toggle's tooltip
      // at launch. focusable() draws no indication, so focusing the root shows nothing.
      androidx.compose.runtime.CompositionLocalProvider(
          LocalHostSurface provides Modifier.fillMaxSize().modalBlur(modalBlur).background(Palette.Bg).focusRequester(root).focusable().onPreviewKeyEvent(::key),
      ) { tabs.shellRoot.Render(BlockScope(Modifier, null)) }
      }
    }
}

/** MODAL-BLUR: the host preference key for "Blur behind dialogs" ("on" / "off"; unset = on). */
const val BLUR_PREF_KEY = "blurBehindDialogs"

/** THEME-1: the host preference key for the Material theme (ThemeSettings.encode; unset = red on dark). */
const val THEME_PREF_KEY = "materialTheme"

@Composable private fun VLine() = Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))

/** MOBILE-LAYOUT-1: the centre column (CLI output, composer, centre tabs). */
const val CENTER_PANE_TAG = "center-pane"
/** MOBILE-TRANSCRIPT-1: the centre tab's content (the transcript on CLI), the space between the tabs and the composer. */
const val CENTER_TRANSCRIPT_TAG = "center-transcript"
/** MOBILE-TRANSCRIPT-1: the composer under it (prompt box and action row). */
const val COMPOSER_TAG = "composer"
/** MOBILE-LAYOUT-1: the compact window's back button (output -> agent list, right panel -> output). */
const val COMPACT_BACK_TAG = "compact-back"
/** MOBILE-DRAWERS-1: an open compact drawer and the scrim behind it. */
const val DRAWER_TAG = "compact-drawer"
const val DRAWER_SCRIM_TAG = "compact-drawer-scrim"

/** MOBILE-LAYOUT-1: the strip above a compact drawer: its close button. The title bar owns both panel toggles. */
@Composable
fun CompactNavRow(backLabel: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(AhqIcons.ArrowBack, backLabel, onBack, Modifier.testTag(COMPACT_BACK_TAG), style = IconActionStyle.TEXT)
    }
    HorizontalDivider(color = Palette.Border)
}

/** M2-5: the remove-confirm dialog and its two buttons. */
/** BUG-M2-15: the composer text field (a new agent's task, or a message to the selected one). */
const val PROMPT_BOX_TAG = "prompt-box"
const val SESSION_REMOVE_DIALOG_TAG = "session-remove-dialog"
const val SESSION_REMOVE_CONFIRM_TAG = "session-remove-confirm"
const val SESSION_REMOVE_CANCEL_TAG = "session-remove-cancel"

/**
 * M2-5: the "Remove <role name>?" confirm, shared by the card's "⋯" menu and (SESSION-RM-1) the composer's trash
 * button on an ended session. Its destructive Remove button is the only path to [onRemove].
 */
@Composable
fun RemoveAgentDialog(a: AgentView, state: AppState, onDismiss: () -> Unit, onRemove: () -> Unit) {
    // Focus starts on the safe action (Cancel), so Enter never deletes by accident and Esc reaches the dialog.
    val cancelFocus = remember { FocusRequester() }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SESSION_REMOVE_DIALOG_TAG).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
        },
        containerColor = Palette.Panel,
        title = { Text("Remove agent", color = Palette.Text) },
        text = { Text("Remove ${state.agentTitle(a)}? This can't be undone.", color = Palette.Text) },
        confirmButton = {
            Button(
                onClick = onRemove,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = Palette.Bg),
                modifier = Modifier.testTag(SESSION_REMOVE_CONFIRM_TAG),
            ) { Text("Remove") }
        },
        dismissButton = {
            // Requested from inside the dialog's own layer, once its content is attached.
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus).testTag(SESSION_REMOVE_CANCEL_TAG)) { Text("Cancel", color = Palette.Text) }
        },
    )
}

/** The digit a number-row key types, for the Ctrl+<digit> tab shortcuts; null for any other key. */
private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero -> 0; Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4
    Key.Five -> 5; Key.Six -> 6; Key.Seven -> 7; Key.Eight -> 8; Key.Nine -> 9
    else -> null
}



@Composable
internal fun ConnectDialog(
    url: String, token: String, state: AppState, onReconnect: () -> Unit,
    onChange: (String, String) -> Unit, onDismiss: () -> Unit, onConnect: () -> Unit,
) {
    var u by remember { mutableStateOf(url) }
    var t by remember { mutableStateOf(token) }
    AhqAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to a daemon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = rememberTickingNow(state.connection.state == ConnState.RECONNECTING)
                Text(connectionStatusLine(state.connection, now) ?: "connected to ${state.connection.url}", color = Palette.Muted, fontSize = 11.sp)
                OutlinedTextField(u, { u = it; onChange(u, t) }, label = { Text("ws:// or wss:// URL") }, singleLine = true)
                OutlinedTextField(
                    t, { t = it; onChange(u, t) }, label = { Text("Bearer token (remote daemons)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { Button(onClick = { onChange(u, t); onConnect() }) { Text("Connect") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReconnect, modifier = Modifier.testTag(RECONNECT_NOW_TAG)) { Text("Reconnect now") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

