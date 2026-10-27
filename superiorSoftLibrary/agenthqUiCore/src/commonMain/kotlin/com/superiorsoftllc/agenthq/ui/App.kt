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
import com.superiorsoftllc.agenthq.ui.plugin.PluginDaemon
import com.superiorsoftllc.agenthq.plugin.api.Events
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.LocalHostSurface
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * PLUGINS-1: the tabs come from modules through a [TabFactory]; the window never names one. The default is the core
 * modules (plugins/core); a host passes its own factory to [App] once it loads more.
 */
val LocalTabFactory = staticCompositionLocalOf { CoreModules.factory }

/** The id of the Attention tab, opened by the title bar's attention chip (a no-op when no module provides it). */
const val ATTENTION_TAB_ID = "infantry.attention"

/**
 * The whole window. Layout follows the reference: agent list left, live output + prompt centre,
 * worktree/review right, status bar below. Everything shown is derived from [AppState]; the only
 * local state is presentation (query, tabs, draft text).
 *
 * Keyboard (every control also has a button):
 *  Ctrl+K search | Alt+Up/Down previous/next agent | Ctrl+J next agent needing input
 *  Ctrl+N spawn mode | Ctrl+Shift+N switch agent (SWAP-AGENT-1) | Ctrl+R resume | Ctrl+Shift+K kill | Ctrl+Alt+K force kill
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
    tabs: TabFactory = CoreModules.factory,
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
    modalBlur.enabled = flags.blurBehindDialogs
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
        when (tab.container) {
            TabContainers.CENTER -> ui.centerTab = tab.id
            TabContainers.RIGHT -> showRightTab(tab.id)
        }
    }
    val root = remember { FocusRequester() }

    DisposableEffect(controller) {
        onDispose { controller.disconnect() }
    }

    LaunchedEffect(Unit) {
        controller.connect(url, token)
        root.requestFocus()
    }
    // PLUGINS-1: the plugins' gated daemon access goes to the daemon this window talks to; the bus hears what is shown.
    LaunchedEffect(url, token) { PluginDaemon.connect(url, token) }
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
    fun key(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when {
            e.isCtrlPressed && e.key == Key.K && e.isShiftPressed -> { selected?.let { a -> actionScope.launch { controller.kill(a.id) } }; true }
            e.isCtrlPressed && e.isAltPressed && e.key == Key.K -> { selected?.let { a -> actionScope.launch { controller.kill(a.id, force = true) } }; true }
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
            e.isCtrlPressed && e.key == Key.N -> { chat.spawnMode = !chat.spawnMode; chat.focus.requestFocus(); true }
            e.isCtrlPressed && e.key == Key.R -> {
                selected?.let { a -> chat.resume(controller, actionScope, a) }
                true
            }
            e.isCtrlPressed && e.key == Key.Eight -> { ui.showBoard = true; true }
            e.isCtrlPressed && digitOf(e.key)?.let(tabs::byShortcut) != null -> { showTab(tabs.byShortcut(digitOf(e.key)!!)!!.id); true }
            e.isCtrlPressed && e.key == Key.L -> { ui.showConnect = true; true }
            e.isCtrlPressed && e.key == Key.Comma -> { ui.showConfig = true; true }
            e.isCtrlPressed && e.key == Key.Slash -> { chat.focus.requestFocus(); true }
            else -> false
        }
    }

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

/** THEME-1: the host preference key for the Material theme (ThemeSettings.encode; unset = amber on dark). */
const val THEME_PREF_KEY = "materialTheme"

@Composable private fun VLine() = Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Border))

/**
 * UX-POLISH-1: the sidebar's spacing token, the 10dp every sidebar block is inset by. Sections (Board, search, the
 * role chips) are this far apart vertically, so they read as separate blocks.
 */
val SidebarSpacing = 10.dp

/** UX-POLISH-1: the sidebar's agent search field. */
const val SIDEBAR_SEARCH_TAG = "sidebar-search"

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
internal fun CompactNavRow(backLabel: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(AhqIcons.ArrowBack, backLabel, onBack, Modifier.testTag(COMPACT_BACK_TAG), style = IconActionStyle.TEXT)
    }
    HorizontalDivider(color = Palette.Border)
}

@Composable
internal fun Sidebar(
    state: AppState,
    visible: List<AgentView>,
    query: String,
    onQuery: (String) -> Unit,
    roleFilter: String?,
    onRoleFilter: (String?) -> Unit,
    searchFocus: FocusRequester,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRemove: (String) -> Unit,
    onAssign: (String, String) -> Unit,
    projects: @Composable () -> Unit,
    onOpenBoard: () -> Unit,
) {
    // PLUGINS-3: the frame (width, background, tag) is the left panel's; this is Infantry_Agents' section in it.
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Counter("Needs input", state.needsInputCount, if (state.needsInputCount > 0) Palette.Accent else Palette.Muted)
            Counter("To review", state.reviewQueue.size, Palette.Info)
            Counter("Working", state.workingCount, Palette.Ok)
        }
        // M2-8: the Board button sits where the projects list was (M2-7 moved projects to the title bar).
        SidebarBoardButton(state, onOpenBoard)
        projects()
        // UX-POLISH-1: the Board row and the search are separate sections, a spacing token either side of the divider
        // (the Board row already carries 4dp of outer padding below it).
        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(top = SidebarSpacing - 4.dp, bottom = SidebarSpacing))
        // The tag sits on a wrapper: BasicTextField reports its semantics bounds inside its own 8dp padding.
        Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().testTag(SIDEBAR_SEARCH_TAG)) {
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 12.sp), cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                    .clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(8.dp),
                decorationBox = { inner -> if (query.isEmpty()) Text("Search agents...  ^K", color = Palette.Muted, fontSize = 12.sp); inner() },
            )
        }
        val roles = state.agents.values.mapNotNull { it.role }.distinct().sorted()
        if (roles.isNotEmpty()) {
            Row(Modifier.padding(start = 10.dp, end = 10.dp, top = SidebarSpacing, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("all", if (roleFilter == null) Palette.Accent else Palette.Muted, Modifier.clickable { onRoleFilter(null) })
                // ROLE-ICON-1: each role filter carries the role's icon ("all" names no role, so it has none).
                roles.forEach { r ->
                    RoleChip(r, r, state.role(r)?.icon, RoleIconSite.FILTER, if (roleFilter == r) Palette.Accent else Palette.Muted, Modifier.clickable { onRoleFilter(if (roleFilter == r) null else r) })
                }
            }
        }
        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(top = 6.dp))
        if (visible.isEmpty()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (state.agents.isEmpty()) "No agents yet." else "No agent matches the filter.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                if (state.agents.isEmpty() && state.connection.isLive) {
                    TextButton(onClick = onNew) { Text("Spawn one (Ctrl+N)") }
                }
            }
        }
        AgentsList(state, visible, onSelect, onRemove, Modifier.weight(1f), onAssign)
        HorizontalDivider(color = Palette.Border)
        // UX-ICON-1: the same New agent icon as the composer, with its label (the sidebar has the room).
        IconAction(
            AhqIcons.PersonAdd, NEW_AGENT_LABEL, onNew, Modifier.fillMaxWidth().testTag(SIDEBAR_NEW_AGENT_TAG),
            shortcut = NEW_AGENT_SHORTCUT, style = IconActionStyle.TEXT,
            colors = ButtonDefaults.textButtonColors(contentColor = Palette.Accent), text = NEW_AGENT_LABEL, textColor = Palette.Accent,
        )
    }
}

/**
 * M2-4: the agents list, grouped by status ([AgentGroup]: Waiting on you, Ready to check, Running, Quiet),
 * each group under a header with its count; empty groups are not shown. Public so UI tests render the real list.
 */
@Composable
fun AgentsList(state: AppState, visible: List<AgentView>, onSelect: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier, onAssign: (String, String) -> Unit = { _, _ -> }) {
    // BUG-UX-SHIFT-1: while any pointer is down on the list, keep the rows where they were when it went down. A session
    // changing group mid-click (BUSY -> IDLE takes the WORKING header with it) would move the row under the pointer, the
    // release would land off the 18dp "⋯" button and the click would be dropped. The fresh grouping lands on release.
    var pointerDown by remember { mutableStateOf(false) }
    val lastShown = remember { arrayOfNulls<List<Pair<AgentGroup, List<AgentView>>>>(1) }
    val fresh = state.groupedAgents(visible)
    val groups = lastShown[0]?.takeIf { pointerDown } ?: fresh.also { lastShown[0] = it }
    LazyColumn(
        modifier.pointerInput(Unit) {
            // Initial pass, consumes nothing: clicks, drag-scroll and the wheel behave exactly as before.
            awaitPointerEventScope { while (true) pointerDown = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed } }
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        groups.forEachIndexed { i, (group, rows) ->
            item(key = "h-${group.name}") {
                // UX-POLISH-1: a divider where one status section ends and the next begins (e.g. above INACTIVE).
                Column {
                    if (i > 0) HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(top = 4.dp).testTag(AGENT_GROUP_DIVIDER_TAG_PREFIX + group.name))
                    AgentGroupHeader(group, rows.size)
                }
            }
            items(rows, key = { it.id }) { a ->
                AgentRow(a, state, a.id == state.selectedId, { onSelect(a.id) }, onRemove, Modifier.padding(bottom = 6.dp), onAssign)
            }
        }
    }
}

/** UX-POLISH-1: the divider above every status group but the first, suffixed with the group's name. */
const val AGENT_GROUP_DIVIDER_TAG_PREFIX = "agent-group-divider-"

@Composable
private fun AgentGroupHeader(group: AgentGroup, count: Int) {
    val color = when (group) {
        AgentGroup.NEEDS_YOU -> Palette.Accent
        AgentGroup.TO_REVIEW -> Palette.Info
        AgentGroup.WORKING -> Palette.Ok
        AgentGroup.INACTIVE -> Palette.Muted
    }
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(group.label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.testTag("agent-group-${group.name}"))
        Spacer(Modifier.width(6.dp))
        Text(
            "$count", color = Palette.Muted, fontSize = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Raised).padding(horizontal = 5.dp).testTag("agent-group-count-${group.name}"),
        )
    }
}

@Composable
private fun Counter(label: String, n: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$n", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, color = Palette.Muted, fontSize = 10.sp)
    }
}

/**
 * One agent session's card in the agents list (M2-4): the portrait with the role badge on its
 * corner; the role name in bold; the role in its colour + a short status/last-activity snippet; the elapsed time
 * and a small "⋯" row menu (M2-5) on the right. The selected card gets an orange border. Public so UI tests render it.
 */
@Composable
fun AgentRow(a: AgentView, state: AppState, selected: Boolean, onClick: () -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier, onAssign: (String, String) -> Unit = { _, _ -> }) {
    val waiting = needsInput(a, state.waiting, state.openPrompts, state.openQuestionSessions)
    val now = Clock.System.now().toEpochMilliseconds()
    val roleColor = a.role?.let { r -> parseHexColor(state.role(r)?.color)?.let { Color(it) } ?: Color.hsv(roleHue(r), 0.45f, 0.72f) } ?: Palette.Muted
    val shape = RoundedCornerShape(8.dp)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().hoverable(hoverSource).clip(shape)
            .background(if (selected) Palette.Raised else Palette.Bg)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.Accent else Palette.Border, shape)
            .clickable(onClick = onClick).testTag("agent-row-${a.id}")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp)) {
            AgentAvatar(a, state, 40)
            // The role badge on the portrait's corner: the role's first letter on its colour, ringed by the card.
            Box(
                Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(if (selected) Palette.Raised else Palette.Bg)
                    .padding(2.dp).clip(CircleShape).background(if (a.role == null) Palette.Raised else roleColor).testTag("agent-row-badge-${a.id}"),
                contentAlignment = Alignment.Center,
            ) { Text((a.role ?: "?").take(1).uppercase(), color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            // State dot on the opposite corner: orange when it needs you, blue working, green idle, red failed.
            Box(
                Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(if (selected) Palette.Raised else Palette.Bg)
                    .padding(2.dp).clip(CircleShape).background(stateColor(a.session.state, waiting)),
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.agentTitle(a), color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("agent-row-title-${a.id}"))
                // TRAITS-T9: the REPORT BACK flag, on the sidebar session row.
                reportFlagOf(a.session.report)?.let { Spacer(Modifier.width(4.dp)); ReportFlagChip(it, Modifier.testTag("session-report-${a.id}")) }
                // PIPELINE-WATCHDOG: stalled / quiet / unreported / waiting, from the daemon's own derivation.
                watchFlagOf(a.session.watch)?.let { Spacer(Modifier.width(4.dp)); WatchFlagChip(it, Modifier.testTag("session-watch-${a.id}")) }
                // PROMPT-2: a prompt card waits on this session.
                if (state.hasOpenPrompt(a.id)) NeedsYouBadge(a.id)
                // BUG-M2-15b: a session with no project is listed under every project; say so on the row.
                if (a.session.projectId.isNullOrBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        NO_PROJECT_LABEL, color = Palette.Muted, fontSize = 9.sp, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, Palette.Border, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp).testTag(SESSION_NO_PROJECT_TAG_PREFIX + a.id),
                    )
                }
            }
            // Display-only in the row: a click anywhere on the row (tag included) selects the agent.
            TicketTag(a, state, null, Modifier.testTag("agent-row-ticket-${a.id}"))
            // Contract 1.117.0: the agent's own progress report on its long job, as a slim live bar.
            a.session.progress?.let { ProgressMeter(it, "row-${a.id}", Modifier.padding(top = 3.dp), compact = true) }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                a.role?.let { Text(it, color = roleColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.testTag("agent-row-role-${a.id}")); Spacer(Modifier.width(5.dp)) }
                // M2-3: the session id stays visible as small secondary text.
                state.agentSecondaryId(a)?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 9.sp, maxLines = 1, modifier = Modifier.testTag("agent-row-id-${a.id}")); Spacer(Modifier.width(5.dp)) }
                Text(
                    if (waiting) "waiting for you" else a.lastActivity,
                    color = if (waiting) Palette.Accent else Palette.Muted, fontSize = 11.sp, maxLines = 1,
                    softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("agent-row-snippet-${a.id}"),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(ago(a.session.lastHeartbeat, now), color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.testTag("agent-row-time-${a.id}"))
            // M2-5: the row's actions live behind a small "⋯" button, shown on hover/selection (kept in the tree
            // so keyboard and screen readers still reach it). Remove goes through a confirm dialog.
            AgentRowMenu(a, state, revealed = selected || hovered, onOpen = onClick, onRemove = onRemove, onAssign = onAssign)
        }
    }
}

/** M2-5: the remove-confirm dialog and its two buttons. */
/** BUG-M2-15: the composer text field (a new agent's task, or a message to the selected one). */
const val PROMPT_BOX_TAG = "prompt-box"
const val SESSION_REMOVE_DIALOG_TAG = "session-remove-dialog"
const val SESSION_REMOVE_CONFIRM_TAG = "session-remove-confirm"
const val SESSION_REMOVE_CANCEL_TAG = "session-remove-cancel"

/** BUG-M2-15b: the "no project" marker on an unbound session's row, and its row-menu "Assign to <project>" item. */
const val NO_PROJECT_LABEL = "no project"
const val SESSION_NO_PROJECT_TAG_PREFIX = "session-no-project-"
const val SESSION_ASSIGN_TAG_PREFIX = "session-assign-"

/**
 * M2-5: an agent card's "⋯" row menu (Open, Remove). Remove never deletes directly: it opens a confirm dialog
 * ("Remove <role name>? This can't be undone.") whose destructive Remove button is the only path to [onRemove];
 * Cancel, Esc or a click outside leave the agent alone. Disabled while disconnected, like every command; a running
 * session is killed first by Controller.removeSession.
 */
@Composable
private fun AgentRowMenu(a: AgentView, state: AppState, revealed: Boolean, onOpen: () -> Unit, onRemove: (String) -> Unit, onAssign: (String, String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val live = state.connection.isLive
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    Box {
        Box(
            Modifier.padding(top = 4.dp).size(width = 22.dp, height = 18.dp).clip(RoundedCornerShape(4.dp))
                .alpha(if (revealed || menuOpen || focused) 1f else 0f)
                .clickable(interactionSource = focusSource, indication = null, enabled = live, role = Role.Button, onClickLabel = "Agent actions") { menuOpen = true }
                .semantics { contentDescription = "Agent actions" }
                .testTag("session-menu-${a.id}"),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(AhqIcons.MoreHoriz, contentDescription = null, tint = if (live) Palette.Muted else Palette.Border, modifier = Modifier.size(14.dp)) }
        DropdownMenu(
            expanded = menuOpen, onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(Palette.Raised).testTag("session-menu-popup-${a.id}"),
        ) {
            DropdownMenuItem(
                text = { Text("Open", color = Palette.Text, fontSize = 12.sp) },
                onClick = { menuOpen = false; onOpen() },
                modifier = Modifier.testTag("session-menu-open-${a.id}"),
            )
            // BUG-M2-15b: only an unbound session, and only while a project is selected: bind it to that project.
            val target = state.activeProject
            if (a.session.projectId.isNullOrBlank() && target != null) {
                DropdownMenuItem(
                    text = { Text("Assign to ${target.name}", color = Palette.Text, fontSize = 12.sp) },
                    onClick = { menuOpen = false; onAssign(a.id, target.id) },
                    modifier = Modifier.testTag(SESSION_ASSIGN_TAG_PREFIX + a.id),
                )
            }
            HorizontalDivider(color = Palette.Border)
            DropdownMenuItem(
                text = { Text("Remove", color = Palette.Danger, fontSize = 12.sp) },
                onClick = { menuOpen = false; confirming = true },
                modifier = Modifier.testTag("session-remove-${a.id}"),
            )
        }
    }
    if (confirming) RemoveAgentDialog(a, state, onDismiss = { confirming = false }, onRemove = { confirming = false; onRemove(a.id) })
}

/**
 * M2-5: the "Remove <role name>?" confirm, shared by the card's "⋯" menu and (SESSION-RM-1) the composer's trash
 * button on an ended session. Its destructive Remove button is the only path to [onRemove].
 */
@Composable
internal fun RemoveAgentDialog(a: AgentView, state: AppState, onDismiss: () -> Unit, onRemove: () -> Unit) {
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


const val CENTER_TAB_BAR_TAG = "center-tab-bar"

/**
 * The center pane's tab strip: the [LocalTabFactory]'s centre tabs in order; [current] is a tab id. Public so UI
 * tests can read the labels.
 */
@Composable
fun CenterTabBar(state: AppState, current: String, onPick: (String) -> Unit) {
    val factory = LocalTabFactory.current
    val strip = factory.tabs(TabContainers.CENTER)
    TabBar(
        strip.map { factory.title(it, state) to (current == it.id) },
        tagOf = { strip[it].testTag },
        barTag = CENTER_TAB_BAR_TAG,
    ) { onPick(strip[it].id) }
}

/** The digit a number-row key types, for the Ctrl+<digit> tab shortcuts; null for any other key. */
private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero -> 0; Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4
    Key.Five -> 5; Key.Six -> 6; Key.Seven -> 7; Key.Eight -> 8; Key.Nine -> 9
    else -> null
}


@Composable
internal fun ReviewPane(state: AppState, controller: Controller) {
    val queue = state.reviewQueue
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (queue.isEmpty()) {
            Text("Nothing is waiting for review.", color = Palette.Text)
            Text(
                "Converged pods queue their nodes here. The client only sees pods it submitted or queried by id " +
                    "(the daemon has no pod-list frame).",
                color = Palette.Muted, fontSize = 11.sp,
            )
            return
        }
        queue.forEach { e ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp)) {
                Text(e.nodeName, color = Palette.Text, fontWeight = FontWeight.SemiBold)
                Text(e.podGoal, color = Palette.Muted, fontSize = 11.sp, maxLines = 2)
                e.branch?.let { Text(it, color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp) }
                e.workingDir?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp) }
                Row {
                    e.sessionId?.takeIf { it in state.agents }?.let { sid ->
                        TextButton(onClick = { controller.select(sid) }) { Text("Open agent") }
                    }
                }
            }
        }
        Text("Approve/reject is not on the daemon protocol yet, so a verdict cannot be recorded from here.", color = Palette.Muted, fontSize = 11.sp)
    }
}

@Composable
internal fun PodsPane(state: AppState, controller: Controller) {
    val actionScope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var nodesText by remember { mutableStateOf("") }
    var podId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.pods.isEmpty()) Text("No pods yet. Submit one below, or load one by id.", color = Palette.Muted)
        state.pods.values.sortedByDescending { it.pod.createdAt }.forEach { p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.pod.goal, color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                    Chip(p.pod.state, podColor(p.pod.state))
                    if (p.pod.endedAt == null) {
                        TextButton(onClick = { actionScope.launch { controller.cancelPod(p.pod.id) } }) { Text("Cancel", color = Palette.Danger) }
                    }
                }
                Text(p.pod.id, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                p.nodes.sortedBy { it.specIndex }.forEach { n ->
                    val blocked = n.state == "PENDING" && p.pod.endedAt == null
                    val runs = p.runs[n.id].orEmpty()
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(nodeColor(n.state)))
                        Spacer(Modifier.width(8.dp))
                        Text(n.name, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.width(110.dp), maxLines = 1)
                        Chip(if (blocked) "blocked / queued" else n.state.lowercase(), nodeColor(n.state))
                        Spacer(Modifier.width(6.dp))
                        Text("try ${n.attempts}/${n.maxAttempts}", color = Palette.Muted, fontSize = 10.sp)
                        runs.lastOrNull()?.sessionId?.takeIf { it in state.agents }?.let { sid ->
                            TextButton(onClick = { controller.select(sid) }) { Text("agent", fontSize = 11.sp) }
                        }
                    }
                    n.detail?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 16.dp)) }
                }
                Text("Which node blocks which is not on the wire (no dependency edges), so a pending node is shown as queued.", color = Palette.Muted, fontSize = 10.sp)
            }
        }
        HorizontalDivider(color = Palette.Border)
        Text("Submit a pod", color = Palette.Muted, fontSize = 10.sp)
        OutlinedTextField(goal, { goal = it }, label = { Text("Goal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            nodesText, { nodesText = it }, label = { Text("Nodes: name [after a,b] [x3]: prompt   (one per line)") },
            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
        )
        error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
        Button(
            enabled = state.connection.isLive && goal.isNotBlank(),
            onClick = {
                parseNodes(nodesText).fold(
                    { nodes -> actionScope.launch { controller.submitPod(goal.trim(), nodes) }; error = null },
                    { error = it.message },
                )
            },
        ) { Text("Submit pod") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(podId, { podId = it }, label = { Text("Load pod by id") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { actionScope.launch { controller.queryPod(podId) } }, enabled = state.connection.isLive && podId.isNotBlank()) { Text("Load") }
        }
    }
}

private fun podColor(s: String) = when (s) {
    "RUNNING", "CANCELLING" -> Palette.Info
    "CONVERGED" -> Palette.Ok
    "FAILED" -> Palette.Danger
    else -> Palette.Muted
}

private fun nodeColor(s: String) = when (s) {
    "RUNNING" -> Palette.Info
    "SUCCEEDED" -> Palette.Ok
    "FAILED" -> Palette.Danger
    "PENDING" -> Palette.Accent
    else -> Palette.Muted
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

