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
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

enum class CenterTab { OUTPUT, PODS, ROLES, PROFILES, TRIGGERS, EPICS, MEMORY, RUN, GIT, STATS }
/**
 * The right-hand tabs, in TabBar order (the bar maps a click to `entries[index]`). UX-CHANGES-1: the Worktree tab is
 * gone; Changes took its first place and its Ctrl+3, and shows the selected session's worktree among the project's.
 */
enum class RightTab { CHANGES, REVIEW, ATTENTION, USAGE, PROMPTS, SKILLS }

/**
 * The whole window. Layout follows the reference: agent list left, live output + prompt centre,
 * worktree/review right, status bar below. Everything shown is derived from [AppState]; the only
 * local state is presentation (query, tabs, draft text).
 *
 * Keyboard (every control also has a button):
 *  Ctrl+K search | Alt+Up/Down previous/next agent | Ctrl+J next agent needing input
 *  Ctrl+N spawn mode | Ctrl+Shift+N switch agent (SWAP-AGENT-1) | Ctrl+R resume | Ctrl+Shift+K kill | Ctrl+Alt+K force kill
 *  Ctrl+1/2/5 centre tab (output, pods, roles) | Ctrl+3/4/6/7 right tab (changes, review, attention, usage)
 *  Ctrl+8 board modal (M2-8: the sidebar Board button) | Ctrl+9/0 centre tab (profiles, triggers)
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
) {
    val state by controller.state.collectAsState()
    val actionScope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var roleFilter by remember { mutableStateOf<String?>(null) }
    var centerTab by remember { mutableStateOf(CenterTab.OUTPUT) }
    var rightTab by remember { mutableStateOf(RightTab.CHANGES) }
    var rightPanelWidth by remember { mutableStateOf(RightPanelWidth.DEFAULT) } // in-memory only: no UI-prefs store exists yet
    var sidebarWidth by remember { mutableStateOf(SidebarWidth.DEFAULT) } // M2-9: in-memory only, not persisted (owner's call)
    var spawnMode by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    // IMG-2: images the daemon already stored for the next message (their chips), an upload in flight, a refusal.
    var attachments by remember { mutableStateOf(listOf<ChatAttachment>()) }
    val attachBusy = attachments.any { it.path == null }
    var attachError by remember { mutableStateOf<String?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    // MOBILE-CONNCHIP-1: a host may route the connection chip to its own connect screen (Android).
    val changeConnection = LocalPlatformHost.current.onChangeConnection
    val pairStore = LocalPlatformHost.current.savedServers
    var showConfig by remember { mutableStateOf(false) }
    var showNewAgent by remember { mutableStateOf(false) }
    // SWAP-AGENT-1: the Switch agent picker, open for one session (the composer button, the header chip, `/harness`).
    var switchAsk by remember { mutableStateOf<SwitchAgentAsk?>(null) }
    fun openSwitch(sessionId: String, harness: String? = null) {
        spawnMode = false
        switchAsk = SwitchAgentAsk(sessionId, harness, (switchAsk?.seq ?: 0) + 1)
    }
    LaunchedEffect(state.harnessPickerAsk) { state.harnessPickerAsk?.let { openSwitch(it.sessionId, it.harness) } }
    var showBoard by remember { mutableStateOf(false) }
    // DIFF-MODAL: the changed file whose diff is open over the window; another project closes it.
    var diffTarget by remember { mutableStateOf<DiffTarget?>(null) }
    LaunchedEffect(state.activeProject?.id) { diffTarget = null }
    // MODAL-BLUR: the one blur state every dialog reports into; the setting is remembered by the host.
    val host = LocalPlatformHost.current
    var flags by remember { mutableStateOf(UiFlags(blurBehindDialogs = host.uiPref(BLUR_PREF_KEY) != "off")) }
    val modalBlur = remember { ModalBlurState(supported = host.supportsBlur) }
    modalBlur.enabled = flags.blurBehindDialogs
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken.orEmpty()) }
    // SWITCH-1: "This computer" + saved servers; a host with neither a local daemon nor a store has no switcher.
    val platformHost = LocalPlatformHost.current
    val switcher = remember(platformHost) {
        if (platformHost.localServer == null && platformHost.savedServers == null) null
        else ServerSwitcher(platformHost.savedServers, platformHost.localServer, platformHost.activeServer(), platformHost::setActiveServer)
    }
    var showServers by remember { mutableStateOf(false) }
    var switchError by remember { mutableStateOf<String?>(null) }
    fun switchTo(c: ServerChoice) {
        switchError = switcher?.select(c) { u, t -> url = u; token = t.orEmpty(); controller.connect(u, t) }
    }
    val searchFocus = remember { FocusRequester() }
    // PANEL-TOGGLE-1 (plan rule 6): Ctrl+K on a hidden sidebar shows it first; its search box is focused only once the
    // sidebar is composed again, because focusing a requester whose node is not attached throws.
    var focusSearchPending by remember { mutableStateOf(false) }
    LaunchedEffect(focusSearchPending, state.panels.leftShown) {
        if (focusSearchPending && state.panels.leftShown) {
            focusSearchPending = false
            // MOBILE-LAYOUT-1: the panes sit in a BoxWithConstraints, which composes the sidebar during layout, after
            // this effect starts; the search box's requester is attached only once a frame has gone by.
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
        }
    }
    /** PANEL-TOGGLE-1 (plan rule 5): a right-panel tab chosen from outside the panel shows the panel first. */
    fun showRightTab(tab: RightTab) {
        // BUG-COMPACT-ATTN-1: in a compact window reveal() would leave the list on screen (leftShown wins in
        // compactScreen()), so every right-panel entry point (attention, Ctrl+3/4/6/7) switches to the RIGHT screen.
        if (state.panels.compactWindow) controller.showCompactScreen(CompactScreen.RIGHT) else controller.revealPanel(PanelSide.RIGHT)
        rightTab = tab
    }
    val promptFocus = remember { FocusRequester() }
    val root = remember { FocusRequester() }

    DisposableEffect(controller) {
        onDispose { controller.disconnect() }
    }

    LaunchedEffect(Unit) {
        controller.connect(url, token)
        root.requestFocus()
    }
    // A refused token is the one failure the human must act on: open the dialog instead of retrying.
    // MOBILE-REFUSED-1: a rejected token goes to the host's own connect screen when it has one (Android saves there;
    // the shared dialog only edits memory). Desktop has no hook and keeps the dialog.
    LaunchedEffect(state.connection.state) {
        if (state.connection.state == ConnState.REFUSED) changeConnection?.invoke() ?: run { showConnect = true }
    }

    // M2-4: the list renders status groups, so keyboard navigation walks that same order.
    val visible = state.groupedAgents(visibleAgents(state, query, roleFilter, agentScope = flags.agentScope)).flatMap { it.second }
    val selected = state.selected

    fun move(delta: Int) {
        if (visible.isEmpty()) return
        val i = visible.indexOfFirst { it.id == state.selectedId }
        controller.select(visible[(if (i < 0) 0 else (i + delta).mod(visible.size))].id)
        spawnMode = false
    }

    /** ATTACH-INLINE-1: the composer's text changed; a tag deleted from it releases its attachment. */
    fun editPrompt(text: String) {
        prompt = text
        attachments = ComposerTags.prune(text, attachments)
    }

    /**
     * IMG-2 / ATTACH-INLINE-1: give each picked file a tag NOW (the box inserts the returned tokens at its caret), then
     * upload it into the project the message goes to: an image as purpose `chat`, any other file as purpose `file`.
     * Over the limit (images 10 MB, other files 20 MB) or refused by the daemon: an inline error, and no tag.
     */
    fun attach(picked: List<PickedImage>): String {
        if (picked.isEmpty()) return ""
        val projectId = (if (spawnMode) null else selected?.session?.projectId?.takeIf { it.isNotBlank() }) ?: state.activeProjectId
        attachError = null
        val tagged = mutableListOf<Pair<ChatAttachment, PickedImage>>()
        for (file in picked) {
            val limit = if (file.isImage) ImageUploadWire.MAX_BYTES else CHAT_FILE_MAX_BYTES
            if (file.size > limit) { attachError = "${file.fileName}: the file is over the ${limit / (1024 * 1024)} MB limit"; continue }
            val token = ComposerTags.nextToken(prompt + tagged.joinToString("") { it.first.token.toString() }, attachments)
                ?: run { attachError = "${file.fileName}: too many attachments in one message"; null } ?: continue
            val a = ChatAttachment(token, file.fileName, file.isImage, file.bytes)
            attachments = attachments + a
            tagged += a to file
        }
        if (tagged.isNotEmpty()) actionScope.launch {
            for ((a, file) in tagged) {
                val r = if (file.isImage) controller.uploadChatImage(projectId, file.fileName, file.mimeType, file.bytes)
                else controller.uploadChatFile(projectId, file.fileName, file.mimeType, file.bytes)
                when (r) {
                    // A tag deleted while it uploaded stays released: its file is simply never referenced.
                    is ImageUploadOutcome.Stored -> attachments = attachments.map { if (it.token == a.token) it.stored(r.image.path) else it }
                    is ImageUploadOutcome.Refused -> {
                        attachError = "${file.fileName}: ${r.reason}"
                        editPrompt(ComposerTags.strip(prompt, a.token))
                    }
                }
            }
        }
        return tagged.joinToString("") { it.first.token.toString() }
    }

    fun submit() {
        val typed = prompt.trim()
        // ATTACH-INLINE-1: each tag becomes its `[image: …]` / `[file: …]` reference where it stands; a `/command` line
        // never carries them (it is not a prompt).
        val text = if (isSlashCommand(ComposerTags.strip(typed))) ComposerTags.strip(typed).trim() else ComposerTags.toPrompt(typed, attachments)
        actionScope.launch {
            val harness = if (spawnMode || selected == null) null else HarnessSwitch.parse(text)
            val r: CommandResult = when {
                // CMD-HARNESS-1: a client command, never a prompt: it swaps the agent's harness.
                harness != null -> controller.switchHarness(selected!!.id, harness)
                spawnMode || selected == null -> controller.spawn(text)
                selected.session.resumable && selected.session.state in ENDED_STATES ->
                    controller.resume(selected.id, text)
                isBusy(selected) -> controller.queueMessage(selected.id, text)
                else -> controller.prompt(selected.id, text)
            }
            if (r is CommandResult.Sent || r is CommandResult.Queued) {
                editPrompt("")
                attachError = null
                // A `/model` or `/effort` in the New agent box only sets the next spawn's selection: stay in the spawn box.
                if (!(spawnMode && isSlashCommand(text))) spawnMode = false
            }
        }
    }

    fun key(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when {
            e.isCtrlPressed && e.key == Key.K && e.isShiftPressed -> { selected?.let { a -> actionScope.launch { controller.kill(a.id) } }; true }
            e.isCtrlPressed && e.isAltPressed && e.key == Key.K -> { selected?.let { a -> actionScope.launch { controller.kill(a.id, force = true) } }; true }
            e.isCtrlPressed && e.key == Key.K -> {
                if (state.panels.leftShown) searchFocus.requestFocus()
                else { controller.revealPanel(PanelSide.LEFT); focusSearchPending = true }
                true
            }
            e.isAltPressed && e.key == Key.DirectionDown -> { move(1); true }
            e.isAltPressed && e.key == Key.DirectionUp -> { move(-1); true }
            e.isCtrlPressed && e.key == Key.J -> {
                state.orderedAgents.firstOrNull { needsInput(it, state.waiting, state.openPrompts, state.openQuestionSessions) && it.id != state.selectedId }?.let { controller.select(it.id) }
                true
            }
            e.isCtrlPressed && e.isShiftPressed && e.key == Key.N -> { selected?.let { openSwitch(it.id) }; true }
            e.isCtrlPressed && e.key == Key.N -> { spawnMode = !spawnMode; promptFocus.requestFocus(); true }
            e.isCtrlPressed && e.key == Key.R -> {
                selected?.let { a -> actionScope.launch {
                    if (controller.resume(a.id, ComposerTags.toPrompt(prompt, attachments).trim()) is CommandResult.Sent) editPrompt("")
                } }
                true
            }
            e.isCtrlPressed && e.key == Key.One -> { centerTab = CenterTab.OUTPUT; true }
            e.isCtrlPressed && e.key == Key.Two -> { centerTab = CenterTab.PODS; true }
            e.isCtrlPressed && e.key == Key.Three -> { showRightTab(RightTab.CHANGES); true }
            e.isCtrlPressed && e.key == Key.Four -> { showRightTab(RightTab.REVIEW); true }
            e.isCtrlPressed && e.key == Key.Five -> { centerTab = CenterTab.ROLES; true }
            e.isCtrlPressed && e.key == Key.Eight -> { showBoard = true; true }
            e.isCtrlPressed && e.key == Key.Nine -> { centerTab = CenterTab.PROFILES; true }
            e.isCtrlPressed && e.key == Key.Zero -> { centerTab = CenterTab.TRIGGERS; true }
            e.isCtrlPressed && e.key == Key.Six -> { showRightTab(RightTab.ATTENTION); true }
            e.isCtrlPressed && e.key == Key.Seven -> { showRightTab(RightTab.USAGE); true }
            e.isCtrlPressed && e.key == Key.L -> { showConnect = true; true }
            e.isCtrlPressed && e.key == Key.Comma -> { showConfig = true; true }
            e.isCtrlPressed && e.key == Key.Slash -> { promptFocus.requestFocus(); true }
            else -> false
        }
    }

    AgentHqTheme {
      // M2-8: a Box so the Board modal overlay stacks over the window.
      // IMG-VIEW-1: chat images open full size over the whole window (the Board keeps its own host, above it).
      androidx.compose.runtime.CompositionLocalProvider(LocalModalBlur provides modalBlur) {
      ImageViewerHost {
      Box(Modifier.fillMaxSize()) {
        Column(
            // BUG-PANEL-TOGGLE-1: the root Column is itself the focus target. A requester on a non-focusable node hands
            // focus to its first focusable child (the title bar's left panel toggle), which opened that toggle's tooltip
            // at launch. focusable() draws no indication, so focusing the root shows nothing.
            Modifier.fillMaxSize().modalBlur(modalBlur).background(Palette.Bg).focusRequester(root).focusable().onPreviewKeyEvent(::key),
        ) {
            TitleBar(
                state, flags.agentScope, onConnect = { changeConnection?.invoke() ?: run { showConnect = true } }, onAttention = { showRightTab(RightTab.ATTENTION) },
                projectSlot = { ProjectPicker(state, controller) },
                onTogglePanel = controller::togglePanel,
                serverSlot = {
                    if (switcher != null) ServerSwitcherChip(switcher, onSelect = ::switchTo, onManage = { showServers = true })
                },
            )
            // MOBILE-LAYOUT-1: below PanelLayout.COMPACT_BELOW_DP (600dp) the window shows ONE pane at full width, picked
            // by PanelLayout.compactScreen() from the same two panel flags; at or above it this is the 3-pane Row as before.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val windowWidthDp = maxWidth.value
            val compact = PanelLayout.isCompact(windowWidthDp)
            // MOBILE-LAYOUT-2: crossing 600dp (a resize, a rotation) parks this window class's panel flags and brings
            // back the other class's, so compact navigation never rewrites the wide layout. The Row renders from
            // forWindow(compact) straight away; the effect then stores the same swap, so no frame shows stale flags.
            LaunchedEffect(compact) { controller.setCompactWindow(compact) }
            val panels = state.panels.forWindow(compact)
            val screen = panels.compactScreen()
            // MOBILE-DRAWERS-1: the sidebar and the right panel are the same content docked (wide) or as a drawer (compact).
            val sidebar: @Composable (Float) -> Unit = { sidebarWidthDp ->
                Sidebar(
                    state, visible, query, { query = it }, roleFilter, { roleFilter = it },
                    searchFocus,
                    onSelect = { controller.select(it); spawnMode = false; if (compact) controller.showCompactScreen(CompactScreen.OUTPUT) },
                    onNew = { spawnMode = false; showNewAgent = true; if (compact) controller.showCompactScreen(CompactScreen.OUTPUT) },
                    onRemove = { id -> actionScope.launch { controller.removeSession(id) } },
                    onAssign = { id, projectId -> actionScope.launch { controller.assignSessionProject(id, projectId) } },
                    projects = { ProjectsSidebar(state) },
                    onOpenBoard = { showBoard = true },
                    width = sidebarWidthDp,
                )
            }
            val rightPanel: @Composable (Modifier, (() -> Unit)?) -> Unit = { mod, onClose ->
                Column(mod.fillMaxHeight().testTag("right-panel")) {
                    if (onClose != null) CompactNavRow(backLabel = "Close", onBack = onClose)
                    TabBar(
                        hPad = 4.dp,
                        fontSize = 11.sp,
                        tabs = listOf(
                            "Changes ^3" to (rightTab == RightTab.CHANGES),
                            "Review ${state.reviewQueue.size.takeIf { it > 0 }?.let { "($it)" } ?: ""} ^4" to (rightTab == RightTab.REVIEW),
                            "Attention ${state.attentionOpenCount.takeIf { it > 0 }?.let { "($it)" } ?: ""} ^6" to (rightTab == RightTab.ATTENTION),
                            "Usage ^7" to (rightTab == RightTab.USAGE),
                            "Prompts" to (rightTab == RightTab.PROMPTS),
                            "Skills" to (rightTab == RightTab.SKILLS),
                        ),
                    ) { rightTab = RightTab.entries[it] }
                    when (rightTab) {
                        RightTab.CHANGES -> ProjectChangesPanel(state, controller, openDiff = diffTarget, onOpenDiff = { diffTarget = it })
                        RightTab.REVIEW -> ReviewPane(state, controller)
                        RightTab.ATTENTION -> AttentionPane(state, controller)
                        RightTab.USAGE -> UsagePane(state, selected, controller)
                        RightTab.PROMPTS -> PromptsPane(state, controller, onInsert = { prompt = insertIntoPrompt(prompt, it); promptFocus.requestFocus() })
                        RightTab.SKILLS -> SkillsPane(state, controller, onInsert = { prompt = insertIntoPrompt(prompt, it); promptFocus.requestFocus() })
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
              // PANEL-TOGGLE-1: a hidden side panel and its resize handle leave the composition; the centre column's
              // weight then takes the whole width.
              if (!compact && panels.leftShown) {
                sidebar(sidebarWidth)
                // M2-9: the left edge drags like the right one (drag right = wider).
                ResizeHandle("sidebar-resize-handle") { sidebarWidth = SidebarWidth.dragged(sidebarWidth, it) }
              }
              run {
                Column(Modifier.weight(1f).fillMaxHeight().testTag(CENTER_PANE_TAG)) {
                    if (compact) {
                        CompactNavRow(
                            onOpenLeft = { controller.showCompactScreen(CompactScreen.LIST) },
                            onOpenRight = { controller.showCompactScreen(CompactScreen.RIGHT) },
                        )
                    }
                    CenterHeader(selected, spawnMode, state, onOpenSwitch = { selected?.let { openSwitch(it.id) } }, onOpenTicket = { showBoard = true })
                    CenterTabBar(state, centerTab) { centerTab = it }
                    Box(Modifier.weight(1f).fillMaxWidth().testTag(CENTER_TRANSCRIPT_TAG)) {
                        when (centerTab) {
                            CenterTab.OUTPUT -> androidx.compose.runtime.CompositionLocalProvider(LocalUserPortrait provides state.userPortrait) {
                                OutputPane(selected, spawnMode, roleColor = state.role(selected?.role)?.color, onStartSession = { spawnMode = true; promptFocus.requestFocus() })
                            }
                            CenterTab.PODS -> PodsPane(state, controller)
                            CenterTab.ROLES -> RolesPane(
                                state, controller,
                                onSessionStarted = { centerTab = CenterTab.OUTPUT; spawnMode = false },
                                onOpenAgent = { controller.select(it); centerTab = CenterTab.OUTPUT; spawnMode = false },
                            )
                            CenterTab.PROFILES -> ProfilesPane(state, controller, onSessionStarted = { centerTab = CenterTab.OUTPUT; spawnMode = false })
                            CenterTab.TRIGGERS -> TriggersPane(state, controller)
                            CenterTab.EPICS -> EpicsPane(state, controller)
                            CenterTab.MEMORY -> MemoryPane(state, controller)
                            CenterTab.RUN -> RunPane(state, controller)
                            CenterTab.GIT -> ProjectGitPane(state, controller)
                            CenterTab.STATS -> StatsPane(state, controller)
                        }
                    }
                    NoticeStrip(state)
                    // CP-1b: every time New agent opens (and on reconnect while open), re-read the model catalog.
                    LaunchedEffect(spawnMode || showNewAgent || switchAsk != null, state.connection.isLive) {
                        if ((spawnMode || showNewAgent || switchAsk != null) && state.connection.isLive) controller.refreshModelCatalog()
                    }
                    if (showNewAgent) {
                        NewAgentDialog(
                            state,
                            onAdd = { role, picked, task ->
                                controller.pickSpawnRole(role)
                                controller.pickModel(picked)
                                val r = controller.spawn(task)
                                if (r is CommandResult.Refused) r.reason else { showNewAgent = false; null }
                            },
                            onDismiss = { showNewAgent = false },
                        )
                    }
                    if (spawnMode && state.roles.isNotEmpty()) {
                        // ROLE-1c: the role a new agent spawns as. Its binding is the DEFAULT below; the picker still overrides it for this spawn.
                        SpawnRoleRow(state, controller)
                    }
                    if (spawnMode && state.modelCatalog.isNotEmpty()) {
                        // PICKER 2/2: which provider+model the new agent runs on (from the `models` frame; none picked = daemon defaults).
                        // PICKER-ALL-2: the shared Harness -> Provider -> Model picker; untouched, the stored pick is left as is.
                        // The picker bounds its own lists; no outer scroll, so a row is never clipped under a second scroller.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            SeededExecutorPicker(
                                state.providers, state.modelCatalog, state.pickedModel, key = spawnMode, tags = CONFIG_EXECUTOR_TAGS,
                                onPick = { controller.pickModel(it) },
                            )
                        }
                    }
                    // SWAP-AGENT-1: the one Switch agent picker, inline above the composer like the New agent rows.
                    switchAsk?.takeIf { !spawnMode && selected?.id == it.sessionId }?.let { ask ->
                        val target = selected!!
                        SwitchAgentPicker(
                            state, target, ask,
                            onApply = { role, picked -> controller.switchAgent(target.id, role, picked) },
                            onClose = { switchAsk = null },
                        )
                    }
                    PromptBox(
                        prompt, ::editPrompt, ::submit, promptFocus, spawnMode, selected, controller,
                        onSpawnToggle = { spawnMode = !spawnMode },
                        onSwitchAgent = { selected?.let { if (switchAsk?.sessionId == it.id) switchAsk = null else openSwitch(it.id) } },
                        attachments = attachments, attachBusy = attachBusy, attachError = attachError,
                        onAttach = ::attach,
                        onRemoveAttachment = { path -> attachments.firstOrNull { it.path == path }?.let { a -> editPrompt(ComposerTags.strip(prompt, a.token)) } },
                        wire = { ComposerTags.toPrompt(it, attachments) },
                    )
                }
              }
              if (!compact && panels.rightShown) {
                // M2-9: the ↔ cursor and a grip, not the hand (drag left = wider).
                ResizeHandle("right-panel-resize-handle") { rightPanelWidth = RightPanelWidth.dragged(rightPanelWidth, it) }
                rightPanel(Modifier.width(rightPanelWidth.dp), null)
              }
            }
            if (compact && screen != CompactScreen.OUTPUT) {
                val drawerWidth = minOf(windowWidthDp * 0.88f, 420f).dp
                val close = { controller.showCompactScreen(CompactScreen.OUTPUT) }
                // Scrim: a tap outside closes the drawer.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { close() } }.testTag(DRAWER_SCRIM_TAG))
                // A swipe toward the screen edge it came from closes it too.
                val toEdge = if (screen == CompactScreen.LIST) -1f else 1f
                Box(
                    Modifier.align(if (screen == CompactScreen.LIST) Alignment.CenterStart else Alignment.CenterEnd).width(drawerWidth).fillMaxHeight()
                        .background(Palette.Bg)
                        .pointerInput(screen) {
                            var dx = 0f
                            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx * toEdge > 80f) close() }) { _, d -> dx += d }
                        }
                        .testTag(DRAWER_TAG),
                ) {
                    if (screen == CompactScreen.LIST) sidebar(drawerWidth.value) else rightPanel(Modifier.fillMaxWidth(), close)
                }
            }
            }
            }
            StatusBar(state, onConfig = { showConfig = true })
        }
        ToastHost(
            state,
            onOpen = { n -> n.projectId?.let { id -> actionScope.launch { controller.selectProject(id) } }; showBoard = true },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        if (showBoard) {
            // M2-8: the board is a near-full-screen modal opened from the sidebar; "Agent" on a card closes it on the chat.
            BoardModal(
                state, controller,
                onOpenAgent = {
                    controller.select(it); centerTab = CenterTab.OUTPUT; spawnMode = false; showBoard = false
                    // MOBILE-LAYOUT-2: a compact window opens the Board from the list; "Agent" lands on the output.
                    if (state.panels.compactWindow) controller.showCompactScreen(CompactScreen.OUTPUT)
                },
                onDismiss = { showBoard = false; root.requestFocus() },
            )
        }
        diffTarget?.let { target ->
            ProjectDiffModal(state, controller, target, onTarget = { next -> diffTarget = next; if (next == null) root.requestFocus() })
        }
        // VOICE-1: spoken attention alerts (and their toast) over every pane.
        com.superiorsoftllc.agenthq.ui.voice.VoiceAlerts(controller.spoken, flags.voice, Modifier.align(Alignment.BottomStart))
        // ASK-1: an agent's question to the owner shows over every pane (and over the Board / diff modals).
        QuestionWindow(
            shown = state.shownQuestions,
            waiting = state.openQuestions.size,
            onAnswer = { answer -> actionScope.launch { controller.answerQuestion(answer) } },
            onCancel = { id -> actionScope.launch { controller.cancelQuestion(id) } },
            onLater = { controller.dismissQuestions(); root.requestFocus() },
            onShow = controller::showQuestions,
        )
      }
      }
        if (showConfig) {
            ConfigDialog(
                flags,
                onFlags = { next ->
                    if (next.blurBehindDialogs != flags.blurBehindDialogs) host.saveUiPref(BLUR_PREF_KEY, if (next.blurBehindDialogs) "on" else "off")
                    flags = next
                }, onDismiss = { showConfig = false },
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
                onBrowse = pickFile,
                logs = logs,
                updates = updates,
                secrets = state.secrets,
                pair = PairEntry(url, token.ifBlank { null }, null) { link ->
                    // PAIR-1: save the server + token through the host's store, then connect with the bearer.
                    applyPairing(link, pairStore) { u, t ->
                        url = u; token = t.orEmpty(); showConfig = false
                        controller.connect(u, t.orEmpty())
                    }
                },
                secretActions = SecretActions(
                    set = { n, v -> actionScope.launch { controller.setSecret(n, v) } },
                    clear = { n -> actionScope.launch { controller.clearSecret(n) } },
                    import = { n -> actionScope.launch { controller.importSecret(n) } },
                ),
            )
        }
        if (showServers && switcher != null) {
            ServersDialog(
                switcher,
                onRemoved = { wasActive -> if (wasActive) switcher.active()?.let(::switchTo) },
                onEdited = { c -> if (c.url == switcher.active()?.url) switchTo(c) },
                onDismiss = { showServers = false },
            )
        }
        switchError?.let { msg ->
            AlertDialog(
                onDismissRequest = { switchError = null }, title = { Text("Could not switch server") }, text = { Text(msg) },
                confirmButton = { TextButton({ switchError = null }) { Text("OK") } },
            )
        }
        if (showConnect) {
            ConnectDialog(url, token, state, controller::reconnectNow, { u, t -> url = u; token = t }, onDismiss = { showConnect = false }) {
                showConnect = false
                controller.connect(url, token)
                root.requestFocus()
            }
        }
      }
    }
}

/** MODAL-BLUR: the host preference key for "Blur behind dialogs" ("on" / "off"; unset = on). */
const val BLUR_PREF_KEY = "blurBehindDialogs"

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
/** MOBILE-LAYOUT-1: the compact output screen's button that opens the right panel. */
const val COMPACT_OPEN_RIGHT_TAG = "compact-open-right"
/** MOBILE-DRAWERS-1: the compact output screen's button that opens the agent list drawer. */
const val COMPACT_OPEN_LEFT_TAG = "compact-open-left"
/** MOBILE-DRAWERS-1: an open compact drawer and the scrim behind it. */
const val DRAWER_TAG = "compact-drawer"
const val DRAWER_SCRIM_TAG = "compact-drawer-scrim"

/**
 * MOBILE-LAYOUT-1: the strip above a compact screen: a back button and, on the output screen, the side-panel button
 * ([onOpenRight] null = no such button). IconAction only, so the hand cursor guard holds.
 */
@Composable
private fun CompactNavRow(backLabel: String = "", onBack: () -> Unit = {}, onOpenLeft: (() -> Unit)? = null, onOpenRight: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onOpenLeft != null) {
            IconAction(AhqIcons.PanelLeft, "Agents", onOpenLeft, Modifier.testTag(COMPACT_OPEN_LEFT_TAG), style = IconActionStyle.TEXT)
        } else {
            IconAction(AhqIcons.ArrowBack, backLabel, onBack, Modifier.testTag(COMPACT_BACK_TAG), style = IconActionStyle.TEXT)
        }
        Spacer(Modifier.weight(1f))
        if (onOpenRight != null) {
            IconAction(AhqIcons.PanelRight, "Side panel", onOpenRight, Modifier.testTag(COMPACT_OPEN_RIGHT_TAG), style = IconActionStyle.TEXT)
        }
    }
    HorizontalDivider(color = Palette.Border)
}

@Composable
private fun Sidebar(
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
    width: Float = SidebarWidth.DEFAULT,
) {
    Column(Modifier.width(width.dp).fillMaxHeight().background(Palette.Panel).testTag("left-sidebar")) {
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
        AgentsList(state, visible, onSelect, onRemove, Modifier.weight(1f), onAssign, onOpenTicket = { onOpenBoard() })
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
fun AgentsList(state: AppState, visible: List<AgentView>, onSelect: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier, onAssign: (String, String) -> Unit = { _, _ -> }, onOpenTicket: (String) -> Unit = {}) {
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
                AgentRow(a, state, a.id == state.selectedId, { onSelect(a.id) }, onRemove, Modifier.padding(bottom = 6.dp), onAssign, onOpenTicket)
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
fun AgentRow(a: AgentView, state: AppState, selected: Boolean, onClick: () -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier, onAssign: (String, String) -> Unit = { _, _ -> }, onOpenTicket: (String) -> Unit = {}) {
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
            TicketTag(a, state, onOpenTicket, Modifier.testTag("agent-row-ticket-${a.id}"))
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
        ) { Text("⋯", color = if (live) Palette.Muted else Palette.Border, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
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
private fun RemoveAgentDialog(a: AgentView, state: AppState, onDismiss: () -> Unit, onRemove: () -> Unit) {
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

/** M2-3: the session header's title (the role's display name) and its secondary id line. */
const val AGENT_HEADER_TITLE_TAG = "agent-header-title"
const val AGENT_HEADER_ID_TAG = "agent-header-id"

/** UX-HEADER-WRAP-1: the width the header's title column always keeps, however many chips sit to its right. */
val HEADER_TITLE_MIN_WIDTH = 160.dp

@Composable
fun CenterHeader(
    a: AgentView?,
    spawnMode: Boolean,
    state: AppState,
    /** SWAP-AGENT-1: the executor chip opens the Switch agent picker; null (tests of the header alone) hides the chip. */
    onOpenSwitch: (() -> Unit)? = null,
    onOpenTicket: (String) -> Unit = {},
) {
    // UX-HEADER-WRAP-1: the right-hand chips are measured before the weighted title column, so at a narrow window
    // they used to take every pixel and the title wrapped one letter per line. They are capped so the title column
    // always keeps HEADER_TITLE_MIN_WIDTH; anything that does not fit is ellipsized or clipped, never wrapped.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val chipsMax = (maxWidth - 24.dp - 44.dp - HEADER_TITLE_MIN_WIDTH).coerceAtLeast(0.dp)
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (a == null || spawnMode) {
            Text(if (spawnMode) "New agent: type its task below and press Enter" else "No agent selected", color = Palette.Muted)
            return@Row
        }
        AgentAvatar(a, state, 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.agentTitle(a), fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(AGENT_HEADER_TITLE_TAG))
                state.agentSecondaryId(a)?.let { Spacer(Modifier.width(6.dp)); Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, modifier = Modifier.testTag(AGENT_HEADER_ID_TAG)) }
                a.role?.let { Spacer(Modifier.width(8.dp)); Chip(it, Palette.Accent) }
                Spacer(Modifier.width(8.dp))
                val waiting = needsInput(a, state.waiting, state.openPrompts, state.openQuestionSessions)
                Chip(if (waiting) "needs input" else a.stateText, stateColor(a.session.state, waiting))
                // TRAITS-T9: the REPORT BACK flag, in the session view header.
                reportFlagOf(a.session.report)?.let { Spacer(Modifier.width(8.dp)); ReportFlagChip(it, Modifier.testTag("agent-report-flag")) }
            }
            TicketTag(a, state, onOpenTicket, Modifier.testTag(AGENT_HEADER_TICKET_TAG))
            Text(a.lastActivity, color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
            // BRANCH-PER-TASK 2/3: the branch of the started ticket this agent runs; nothing when it holds none.
            state.ticketBranchFor(a.id)?.branchLine()?.let { Text(it, color = Palette.Info, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, modifier = Modifier.testTag("agent-ticket-branch")) }
        }
        Row(Modifier.widthIn(max = chipsMax), verticalAlignment = Alignment.CenterVertically) {
        ExecutorChips(state.sessionExecutor(a))
        if (onOpenSwitch != null) {
            Chip(state.sessionExecutor(a).switchChipText(), Palette.Info, modifier = Modifier.testTag(EXECUTOR_SWITCH_CHIP_TAG).clickable { onOpenSwitch() })
            Spacer(Modifier.width(6.dp))
        }
        Chip("runs ${a.session.runCount}")
        // Contract 1.13.0: what this session's runs reported using; nothing at all when none reported.
        a.usageTotals?.let { t -> Spacer(Modifier.width(6.dp)); Chip("${compact(t.input)} in / ${compact(t.output)} out", Palette.Info) }
        }
    }
    }
    HorizontalDivider(color = Palette.Border)
}

/** M2-3b: the session header's harness / provider / model chips; tags for UI tests. */
const val AGENT_HEADER_HARNESS_TAG = "agent-header-harness"
const val AGENT_HEADER_PROVIDER_TAG = "agent-header-provider"
const val AGENT_HEADER_MODEL_TAG = "agent-header-model"
const val AGENT_HEADER_MODEL_CHIP_TAG = "agent-header-model-chip"

/**
 * M2-3b: one chip per known part of what the session runs on; an unknown part has no chip at all. The model chip
 * shows a shortened id and gives the full id in a hover tooltip and to screen readers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExecutorChips(e: SessionExecutor) {
    e.harness?.let { Chip(it, textModifier = Modifier.testTag(AGENT_HEADER_HARNESS_TAG)); Spacer(Modifier.width(6.dp)) }
    e.provider?.let { Chip(it, Palette.Accent, textModifier = Modifier.testTag(AGENT_HEADER_PROVIDER_TAG)); Spacer(Modifier.width(6.dp)) }
    val short = e.shortModel
    val full = e.fullModel
    if (short != null && full != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(full, fontFamily = Mono, fontSize = 11.sp) } },
            state = rememberTooltipState(),
        ) {
            Chip(
                short, Palette.Ok,
                modifier = Modifier.testTag(AGENT_HEADER_MODEL_CHIP_TAG).semantics { contentDescription = full },
                textModifier = Modifier.testTag(AGENT_HEADER_MODEL_TAG),
            )
        }
        Spacer(Modifier.width(6.dp))
    }
}

const val AGENT_HEADER_TICKET_TAG = "agent-header-ticket"

/**
 * A worker's ticket tag: short ticket id + truncated title (full title on hover), click opens the board. Nothing for
 * a session with no ticket. The title is resolved from the board; a ticket not (yet) on the board shows its id alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketTag(a: AgentView, state: AppState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val id = a.session.ticketId?.takeIf { it.isNotBlank() } ?: return
    val title = state.ticket(id)?.title
    val full = ticketTagFull(id, title)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(full, fontSize = 11.sp) } },
        state = rememberTooltipState(),
    ) {
        Text(
            ticketTagLabel(id, title), color = Palette.Info, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = modifier.clickable { onOpen(id) }.semantics { contentDescription = full },
        )
    }
}

const val CENTER_TAB_BAR_TAG = "center-tab-bar"

/** The center pane's tab strip, one label per [CenterTab] in order. Public so UI tests can read the labels. */
@Composable
fun CenterTabBar(state: AppState, current: CenterTab, onPick: (CenterTab) -> Unit) {
    TabBar(
        listOf(
            "CLI  ^1" to (current == CenterTab.OUTPUT),
            "Pods  ^2" to (current == CenterTab.PODS),
            "Roles  ^5" to (current == CenterTab.ROLES),
            "Profiles  ^9" to (current == CenterTab.PROFILES),
            "Triggers ${state.trippedTriggerCount.takeIf { it > 0 }?.let { "($it tripped)" } ?: ""} ^0" to (current == CenterTab.TRIGGERS),
            "Epics" to (current == CenterTab.EPICS),
            "Notes" to (current == CenterTab.MEMORY),
            "Run" to (current == CenterTab.RUN),
            "Git" to (current == CenterTab.GIT),
            "Stats" to (current == CenterTab.STATS),
        ),
        tagOf = { "center-tab-${CenterTab.entries[it].name}" },
        barTag = CENTER_TAB_BAR_TAG,
    ) { onPick(CenterTab.entries[it]) }
}

@Composable
private fun TabBar(tabs: List<Pair<String, Boolean>>, hPad: androidx.compose.ui.unit.Dp = 14.dp, fontSize: androidx.compose.ui.unit.TextUnit = 12.sp, tagOf: ((Int) -> String)? = null, barTag: String? = null, onPick: (Int) -> Unit) {
    // Scrolls sideways when the labels outgrow the width, so no tab is ever squeezed or hidden.
    Row(Modifier.fillMaxWidth().let { m -> barTag?.let { m.testTag(it) } ?: m }.background(Palette.Panel).horizontalScroll(rememberScrollState())) {
        tabs.forEachIndexed { i, (label, on) ->
            Column(Modifier.let { m -> tagOf?.let { m.testTag(it(i)) } ?: m }.clickable { onPick(i) }.padding(horizontal = hPad, vertical = 7.dp)) {
                Text(label, maxLines = 1, softWrap = false, color = if (on) Palette.Accent else Palette.Muted, fontSize = fontSize, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    HorizontalDivider(color = Palette.Border)
}

@Composable
private fun OutputPane(a: AgentView?, spawnMode: Boolean, roleColor: String? = null, onStartSession: () -> Unit) {
    if (a == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (spawnMode) return@Box
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No live session. Start one to see its output here.", color = Palette.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onStartSession) { Text("Start a session") }
            }
        }
        return
    }
    if (a.output.isEmpty() && a.chatStatus(Clock.System.now().toEpochMilliseconds()) == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Nothing on screen for this session yet.\nThe daemon replays a session's recent output only while the process that ran it is up; older history is not kept.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onStartSession) { Text("Start a session") }
            }
        }
        return
    }
    AgentChat(a, roleColor = roleColor)
}

/** Test tag on the transient status row under the transcript (or in place of the empty state). */
const val CHAT_STATUS_TAG = "chat-status"

/** Test tag on the Interrupt control beside the prompt box. */
const val INTERRUPT_BUTTON_TAG = "interrupt-button"
/** UX-ICON-1: the sidebar's New agent link (icon plus label). */
const val SIDEBAR_NEW_AGENT_TAG = "sidebar-new-agent"
/** The New agent action's name and shortcut, shared by the composer icon and the sidebar link. */
const val NEW_AGENT_LABEL = "New agent"
const val NEW_AGENT_SHORTCUT = "Ctrl+N"

/** SESSION-RM-1: the composer trash button on an ended session (it removes the agent from the list). */
const val REMOVE_AGENT_LABEL = "Remove agent"

/** A session whose run is over for good: nothing left to kill, only to resume or remove. */
val ENDED_STATES = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)

/** Test tag on the list of messages waiting for a busy agent. */
const val QUEUED_MESSAGES_TAG = "queued-messages"

/** UI-ACT-2: test tag on the collapsible Activity box above the chat input. */
const val ACTIVITY_BOX_TAG = "activity-box"

/** UI-ACT-2: test tag on each activity line's label inside the box. */
const val ACTIVITY_LINE_TAG = "activity-line"

/**
 * The chat transcript for one agent, plus [AgentView.chatStatus] when the latest turn has no answer yet.
 * The status row sits under the last line and is absent once [AgentView.chatStatus] is null.
 */
@Composable
fun AgentChat(agent: AgentView, nowProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() }, roleColor: String? = null) {
    val list = rememberLazyListState()
    // The elapsed label must age on its own: no status frame may arrive for seconds while the agent
    // works, so a one-second ticker keeps "Thinking... 3s" counting with the transcript otherwise still.
    // [nowProvider] is the clock (injectable so a UI test can drive it with the Compose frame clock).
    var now by remember { mutableStateOf(nowProvider()) }
    LaunchedEffect(agent.id) { while (true) { delay(1_000); now = nowProvider() } }
    val rows = chatItems(agent.output)
    val status = agent.chatStatus(now)
    // Keyed on the status PRESENCE, not its value: the ticker changes it every second, and that must
    // not restart the scroll effect.
    LaunchedEffect(agent.id, rows.size, agent.output.lastOrNull()?.text?.length, status != null) {
        val last = rows.size + (if (status != null) 1 else 0) - 1
        if (last >= 0) list.requestScrollToItem(last)
    }
    val name = agent.session.worktreeBranch ?: agent.id.take(8)
    Column(Modifier.fillMaxSize()) {
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
                items(rows.size) { i ->
                    // M2-2: the agent's portrait opens each run of agent-side lines, like a chat.
                    val showAvatar = startsAgentRun(rows, i)
                    when (val row = rows[i]) {
                        is ChatItem.Line -> ChatLine(row.line, agent.id, name, agent.role, roleColor, showAvatar)
                        is ChatItem.Reasoning -> AgentSideRow(showAvatar, agent.role, agent.id, roleColor) { ReasoningBlock(row.lines) }
                    }
                }
                // The live status row is agent-side too: it opens a run only when no agent line precedes it.
                if (status != null) item {
                    AgentSideRow(startsAgentRun(rows, rows.size), agent.role, agent.id, roleColor) { ChatStatusRow(status) }
                }
            }
        }
        ActivityBox(agent)
    }
}

/**
 * UI-ACT-2: the selected session's tool activity, newest first, above the chat input. Collapsible; it
 * starts expanded while the session is running and collapsed once it is idle.
 */
@Composable
fun ActivityBox(agent: AgentView) {
    val entries = agent.activityLog
    if (entries.isEmpty()) return
    // UI-ACT-2: starts expanded while the session is running and collapsed once it is idle; the user
    // can still toggle it. Keyed on the agent so switching sessions re-reads that session's run state.
    var expanded by remember(agent.id) { mutableStateOf(isRunning(agent.session.state)) }
    Column(Modifier.fillMaxWidth().background(Palette.Panel).testTag(ACTIVITY_BOX_TAG)) {
        Text(
            "Activity (${entries.size})",
            color = Palette.Muted,
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        if (expanded) {
            // Newest first, capped at ~160dp, scrollable, monospace.
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                entries.asReversed().forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 1.dp)) {
                        Text(activityTimestamp(e.at), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(e.label, color = Palette.Text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.testTag(ACTIVITY_LINE_TAG))
                    }
                }
            }
        }
    }
}

/** Pulsing dot plus the status label. Cleared by the caller when [status] is no longer due. */
@Composable
fun ChatStatusRow(status: ChatStatus) {
    val pulse = rememberInfiniteTransition(label = "chatStatus")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "chatStatusPulse",
    )
    Row(Modifier.testTag(CHAT_STATUS_TAG).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(Palette.Accent))
        Spacer(Modifier.width(8.dp))
        Text(chatStatusLabel(status), color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
        status.elapsedSuffix()?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp) }
        // AGENT-UX-12: a run past the stale threshold is shown as STALLED, in a distinct colour, so a
        // silent run can never be mistaken for one that is producing activity.
        (status as? ChatStatus.Thinking)?.stalledFor?.let {
            Text("  no new activity for $it", color = Palette.Danger, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

private fun chatStatusLabel(status: ChatStatus): String = when (status) {
    // AGENT-UX-12: when a tool is running, say WHAT it is doing ("Running bash: ls -la src"), not just
    // "Thinking...". Falls back to the plain label before any tool call.
    is ChatStatus.Thinking -> if (status.compacting) COMPACTING_LABEL else status.activity?.let { "Running $it" } ?: "Thinking..."
    ChatStatus.Failure -> "Failed"
    ChatStatus.Stopped -> "Stopped"
}

/** How long the agent has been working, shown beside the label: " 3s", or null when not yet known. */
private fun ChatStatus.elapsedSuffix(): String? = (this as? ChatStatus.Thinking)?.elapsed?.takeIf { it.isNotBlank() }?.let { " $it" }

/** Renders parsed assistant markdown; re-parsed by the caller on every streamed update. */
@Composable
private fun ChatBlocks(blocks: List<ChatBlock>) {
    val clip = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is ChatBlock.Paragraph -> Text(spansText(b.spans), color = Palette.Text, fontFamily = Mono, fontSize = 12.sp)
            is ChatBlock.BulletList -> Column {
                for (item in b.items) Text(spansText(parseChatInline(item)), color = Palette.Text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            is ChatBlock.Heading -> Text(
                b.text,
                color = Palette.Text,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = when (b.level) {
                    1 -> 18.sp
                    2 -> 16.sp
                    else -> 14.sp
                },
            )
            is ChatBlock.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).border(1.dp, Palette.Border, RoundedCornerShape(6.dp)).padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.language, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clip.setText(AnnotatedString(b.body)) }) { Text("Copy", fontSize = 11.sp) }
                }
                Text(b.body, color = Palette.Text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}

/** Test tag on the collapsible "Thinking" header of a reasoning block. */
const val REASONING_HEADER_TAG = "reasoning-header"

/**
 * A run of consecutive reasoning lines as one block, collapsed by default. The header reads
 * "Thinking (n)" where n is how many lines the block holds; clicking it toggles the block. Expanded,
 * the lines are muted plain text -- no markdown, unlike the answer, because reasoning is the model's
 * own working, not the reply.
 */
@Composable
fun ReasoningBlock(lines: List<OutputLine>) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "Thinking (${lines.size})",
            color = Palette.Muted,
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(REASONING_HEADER_TAG).clickable { expanded = !expanded },
        )
        if (expanded) {
            Text(
                lines.joinToString("\n") { it.text },
                color = Palette.Muted,
                fontFamily = Mono,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }
}

/** Test tag on the row of every chat message that carries a speaker avatar. */
const val SPEAKER_ROW_TAG = "speaker-row"

/** One chat message: its speaker's avatar on the left, the message body filling the rest of the row. */
@Composable
private fun SpeakerRow(avatar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(SPEAKER_ROW_TAG), verticalAlignment = Alignment.Top) {
        avatar()
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** Size of the agent's portrait on its chat lines, and of the indent its continuation lines keep. */
private const val CHAT_AVATAR_SIZE = 22

/** True for every line kind the agent produces; only the human's own [LineKind.USER] echo is not. */
private fun ChatItem.isAgentSide(): Boolean = this !is ChatItem.Line || line.kind != LineKind.USER

/**
 * M2-2: whether the agent-side item at [index] opens a run and so carries the agent's portrait. A run
 * is a stretch of consecutive agent-side items; a USER line ends it. [index] == rows.size asks about
 * the live status row that follows the transcript.
 */
internal fun startsAgentRun(rows: List<ChatItem>, index: Int): Boolean {
    if (index < rows.size && !rows[index].isAgentSide()) return false
    return index == 0 || !rows[index - 1].isAgentSide()
}

/**
 * One agent-side chat row: the agent's [AgentAvatar] on the left when [showAvatar] (the row opens a
 * run), otherwise an empty gap of the same width so the whole run keeps one left indent.
 */
@Composable
fun AgentSideRow(showAvatar: Boolean, agentRole: String?, agentId: String, agentRoleColor: String?, content: @Composable () -> Unit) {
    SpeakerRow({
        if (showAvatar) AgentAvatar(agentRole, agentId, CHAT_AVATAR_SIZE, agentRoleColor)
        else Spacer(Modifier.width(CHAT_AVATAR_SIZE.dp))
    }, content)
}

/**
 * One transcript line as the chat shows it: an agent's markdown, the user's own echo, or a plain
 * status/error/finished/log marker. The user's echo carries the human's disc from [USER_AVATAR_ID];
 * every other kind is agent-side and sits in an [AgentSideRow] -- with the agent's [AgentAvatar] (its
 * role's portrait, the same one the agents list shows) when [showAvatar], or just its indent when the
 * line continues a run. [agentName] labels the speaker; it never picks the avatar.
 */
@Composable
fun ChatLine(
    line: OutputLine,
    agentId: String,
    agentName: String,
    agentRole: String? = null,
    agentRoleColor: String? = null,
    showAvatar: Boolean = true,
) {
    if (line.kind == LineKind.USER) {
        // IMG-2: the turn's `[image: ...]` lines show as thumbnails, not as text.
        val (said, images) = ChatImageLines.split(line.text)
        SpeakerRow({ UserAvatar() }) {
            Column {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.Accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                    CardTimestamp(line.at)
                    if (said.isNotBlank() || images.isEmpty()) {
                        Text("> $said", color = Palette.Accent, fontFamily = Mono, fontSize = 12.sp)
                    }
                    ChatSentImages(images)
                }
            }
        }
        return
    }
    AgentSideRow(showAvatar, agentRole, agentId, agentRoleColor) {
        when (line.kind) {
            LineKind.TEXT -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.Raised, RoundedCornerShape(8.dp)).padding(8.dp)) {
                CardTimestamp(line.at)
                ChatBlocks(chatLineBlocks(line.kind, line.text).orEmpty())
            }
            LineKind.REASONING -> ReasoningBlock(listOf(line))
            LineKind.STATUS -> Text("· ${line.text}", color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
            LineKind.ERROR -> Text("! ${line.text}", color = Palette.Danger, fontFamily = Mono, fontSize = 12.sp)
            LineKind.FINISHED -> Text("— ${line.text}", color = Palette.Info, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
            // UI-LOG-1: stderr and process-exit log lines -- muted monospace.
            LineKind.LOG -> Text(line.text, color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
            // UX-WRITE-1: a file write is a change box, inline in the turn where it happened.
            LineKind.WRITE -> ChangeBoxLine(line.text)
            LineKind.USER -> Unit
        }
    }
}

/** Test tag on the quiet send/response time at the top-right of a chat card. */
const val CARD_TIMESTAMP_TAG = "card-timestamp"

/** Small muted time, right-aligned at the top of a chat card, so it never competes with the message. */
@Composable
private fun CardTimestamp(at: Long) {
    Text(
        activityTimestamp(at),
        color = Palette.Muted.copy(alpha = 0.7f),
        fontFamily = Mono,
        fontSize = 9.sp,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().testTag(CARD_TIMESTAMP_TAG),
    )
}

private fun spansText(spans: List<ChatSpan>): AnnotatedString = buildAnnotatedString {
    for (s in spans) when (s) {
        is ChatSpan.Text -> append(s.text)
        is ChatSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) }
        is ChatSpan.InlineCode -> withStyle(SpanStyle(background = Palette.Raised, color = Palette.Accent)) { append(s.text) }
        is ChatSpan.Link -> withStyle(SpanStyle(color = Palette.Info, textDecoration = TextDecoration.Underline)) { append(s.label) }
    }
}

@Composable
fun NoticeStrip(state: AppState) {
    val n = state.localNotices().lastOrNull() ?: return
    var hidden by remember(n.at) { mutableStateOf(false) }
    LaunchedEffect(n.at) { delay(8_000); hidden = true }
    if (hidden) return
    // NOTICE-COLOUR-1: the notice's own kind picks the colour; red only for a real failure.
    val colour = noticeColour(n.kind)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colour.copy(alpha = 0.12f).compositeOver(Palette.Panel)).testTag(NOTICE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colour))
        Text(n.text, color = colour, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(12.dp, 6.dp))
    }
}

/** NOTICE-COLOUR-1: routine is the theme's success green, a heads-up its amber, and only an error is red. */
fun noticeColour(kind: NoticeKind): Color = when (kind) {
    NoticeKind.OK -> Palette.Ok
    NoticeKind.HEADS_UP -> Palette.Accent
    NoticeKind.ERROR -> Palette.Danger
}

/**
 * The Interrupt control: stops the CURRENT turn of [agent] and leaves the session at rest with the same id and
 * conversation. Enabled only while the agent is busy and the link is live; the UI does not assume the outcome,
 * the busy-to-idle change appears when the daemon's session frame reports it.
 */
@Composable
fun InterruptButton(agent: AgentView, live: Boolean, controller: Controller) {
    val actionScope = rememberCoroutineScope()
    val busy = isBusy(agent)
    IconAction(
        AhqIcons.Stop, "Interrupt",
        onClick = { actionScope.launch { controller.interrupt(agent.id) } },
        enabled = live && busy,
        modifier = Modifier.testTag(INTERRUPT_BUTTON_TAG),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Danger),
    )
}

/** The messages held for a busy agent, in send order, each marked as waiting. Empty queue renders nothing. */
@Composable
fun QueuedMessages(queue: List<QueuedPrompt>) {
    if (queue.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag(QUEUED_MESSAGES_TAG).padding(bottom = 6.dp)) {
        Text("Queued (${queue.size}), waiting for the current turn:", color = Palette.Muted, fontSize = 11.sp)
        queue.forEachIndexed { i, q ->
            Text("${i + 1}. ${q.text}", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
private fun PromptBox(
    value: String,
    onValue: (String) -> Unit,
    onSubmit: () -> Unit,
    focus: FocusRequester,
    spawnMode: Boolean,
    a: AgentView?,
    controller: Controller,
    onSpawnToggle: () -> Unit,
    /** SWAP-AGENT-1: in a session, the composer's agent button opens the Switch agent picker instead of New agent. */
    onSwitchAgent: () -> Unit = {},
    attachments: List<ChatAttachment> = emptyList(),
    attachBusy: Boolean = false,
    attachError: String? = null,
    /** ATTACH-INLINE-1: tag and upload these files; answers the tokens to insert at the caret ("" = none taken). */
    onAttach: (List<PickedImage>) -> String = { "" },
    onRemoveAttachment: (String) -> Unit = {},
    /** ATTACH-INLINE-1: the box's text as a PROMPT (its tags turned into references), for Resume. */
    wire: (String) -> String = { it },
) {
    val actionScope = rememberCoroutineScope()
    val host = LocalPlatformHost.current
    val snapshot = controller.state.collectAsState().value
    val live = snapshot.connection.isLive
    // COMPOSER-NEWLINE-1: the box keeps its own caret so Shift+Enter can break the line exactly there. A value set
    // from outside (cleared after a send, a slash row picked) wins and puts the caret at its end.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    // ATTACH-INLINE-1: picked, pasted or dropped files become tags at the caret (replacing any selection).
    val insertPicked by rememberUpdatedState { picked: List<PickedImage> ->
        val tokens = onAttach(picked)
        if (tokens.isNotEmpty()) {
            val text = shown.text.replaceRange(shown.selection.min, shown.selection.max, tokens)
            field = TextFieldValue(text, TextRange(shown.selection.min + tokens.length))
            onValue(text)
        }
    }
    val pick = {
        if (live && host.canPickFiles) host.pickFile(ATTACH_FILE_LABEL)?.let { path ->
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            // An oversize file is refused by its size, never read into memory.
            val size = host.fileSize(path)
            val bytes = if (size != null && size > CHAT_FILE_MAX_BYTES) ByteArray(0) else host.readBytes(path)
            if (bytes != null) insertPicked(listOf(PickedImage(name, PickedImage.mimeOf(name), bytes, size ?: bytes.size.toLong())))
        }
    }
    // IMG-2: a file dropped anywhere on the composer is attached like a picked one (ATTACH-INLINE-1: any type).
    val drop = remember(host) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val picked = host.droppedFiles(event)
                if (picked.isEmpty()) return false
                insertPicked(picked)
                return true
            }
        }
    }
    // Contract 1.11.0 chat: the same rule the daemon applies, shown before the human hits Send. A BUSY agent is not
    // a blocker: the message is queued for its next turn instead (see Controller.queueMessage).
    val blocker = if (spawnMode || a == null || isBusy(a)) null else promptBlocker(a)
    val queue = if (spawnMode || a == null) emptyList() else snapshot.queuedFor(a.id)
    // CP-4(c): a leading "/" in the box asks the daemon for that harness's commands and lists them. A live agent's box
    // uses its own provider; the New agent box (no session) uses the provider picked for the new agent.
    val slashSession = if (spawnMode || a == null) null else a.id
    val slashProvider = if (slashSession == null) snapshot.pickedModel?.provider else null
    val typedCommand = slashTyped(value)
    LaunchedEffect(typedCommand, slashSession, slashProvider, live) {
        if (typedCommand != null && live) controller.requestCommands(slashSession, typedCommand, slashProvider)
    }
    // The /model list comes from the `models` frame: fetch it the first time a command is typed.
    val wantsCatalog = typedCommand != null && live && snapshot.modelCatalog.isEmpty()
    LaunchedEffect(wantsCatalog) { if (wantsCatalog) controller.refreshModelCatalog() }
    // CMD-HARNESS-1: `/harness` is the window's own command (an agent's box only): its values are the `providers` frame.
    val harnessRows = if (slashSession == null) null else HarnessSwitch.suggestions(snapshot, value, a?.session?.provider)
    val offersHarness = slashSession != null && typedCommand != null && typedCommand.none { it.isWhitespace() } &&
        HarnessSwitch.COMMAND.startsWith(typedCommand, ignoreCase = true)
    val answerRows = harnessRows
        ?: (slashSuggestions(snapshot, slashSession, value, slashProvider) + if (offersHarness) listOf(HarnessSwitch.commandRow()) else emptyList())
    // Esc closes the list until the text changes; Up/Down move the highlight, which restarts at the top for new rows.
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val slashRows = if (dismissedFor == value) emptyList() else answerRows
    val slashTitle = if (harnessRows != null) HarnessSwitch.HEADER else slashHeader(snapshot, slashSession, value, slashProvider)
    var slashIndex by remember { mutableStateOf(0) }
    LaunchedEffect(slashRows.map { it.replacement }) { slashIndex = 0 }
    val pickRow = { r: SlashSuggestion -> onValue(r.replacement) }
    Column(Modifier.fillMaxWidth().background(Palette.Panel).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = drop).padding(10.dp).testTag(COMPOSER_TAG)) {
        // PROMPT-2: the open session's prompt card, docked above the input until the daemon resolves it.
        if (!spawnMode && a != null) {
            PromptCard(
                snapshot.promptsFor(a.id), com.superiorsoftllc.agenthq.ui.model.PromptCards.offersAllowAlways(snapshot.connection.contract),
                onAnswer = { r -> actionScope.launch { controller.answerPrompt(r) } },
                onDismiss = controller::dismissPrompt,
            )
        }
        QueuedMessages(queue)
        ChatAttachmentChips(attachments, attachBusy, attachError, onRemoveAttachment)
        SlashSuggestions(slashTitle, slashRows, slashIndex, onPick = { onValue(it) })
        // ATTACH-INLINE-1: each tag's one character is drawn as its pill "📎 name".
        val tags = remember(attachments) { ComposerTags.transformation(attachments, SpanStyle(color = Palette.Accent, background = Palette.Raised)) }
        OutlinedTextField(
            value = shown,
            onValueChange = { next -> field = next; if (next.text != value) onValue(next.text) },
            visualTransformation = tags,
            // ATTACH-INLINE-1: the paperclip sits in the box, next to the text; paste (Ctrl+V) and drop attach too.
            trailingIcon = {
                IconAction(
                    AhqIcons.AttachFile, ATTACH_FILE_LABEL, onClick = pick, shortcut = ATTACH_FILE_SHORTCUT,
                    enabled = live && host.canPickFiles, style = IconActionStyle.TEXT,
                    modifier = Modifier.testTag(CHAT_IMAGE_ATTACH_TAG),
                )
            },
            placeholder = {
                Text(
                    when {
                        spawnMode || a == null -> "Task for a NEW agent. Enter to spawn, Shift+Enter for a new line"
                        isBusy(a) -> "Agent is busy. Enter to queue: sent as its next turn when this one ends"
                        blocker != null -> "Waiting: $blocker"
                        else -> "Message ${a.session.worktreeBranch ?: a.id.take(8)}. Enter to send"
                    },
                    color = Palette.Muted,
                )
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(PROMPT_BOX_TAG).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val listOpen = slashRows.isNotEmpty()
                when {
                    listOpen && e.key == Key.DirectionDown -> { slashIndex = moveSlashSelection(slashIndex, 1, slashRows.size); true }
                    listOpen && e.key == Key.DirectionUp -> { slashIndex = moveSlashSelection(slashIndex, -1, slashRows.size); true }
                    listOpen && e.key == Key.Escape -> { dismissedFor = value; true }
                    // Tab always belongs to the list while it is open; Enter picks unless the row is already in the box.
                    listOpen && e.key == Key.Tab -> { slashPick(slashRows, slashIndex, value)?.let(pickRow); true }
                    // CMD-MODEL-1: a filter narrowed to one value: Enter picks it and sends the line in one go.
                    listOpen && e.key == Key.Enter && !e.isShiftPressed && !e.isCtrlPressed && slashSubmitOnEnter(slashRows, value) != null ->
                        { onValue(slashSubmitOnEnter(slashRows, value)!!.replacement); onSubmit(); true }
                    listOpen && e.key == Key.Enter && !e.isShiftPressed && !e.isCtrlPressed && slashPick(slashRows, slashIndex, value) != null ->
                        { pickRow(slashPick(slashRows, slashIndex, value)!!); true }
                    // COMPOSER-NEWLINE-1: Shift+Enter is a new line at the caret (replacing any selection), never a send.
                    e.key == Key.Enter && e.isShiftPressed && !e.isCtrlPressed -> {
                        val text = shown.text.replaceRange(shown.selection.min, shown.selection.max, "\n")
                        field = TextFieldValue(text, TextRange(shown.selection.min + 1))
                        onValue(text)
                        true
                    }
                    e.key == Key.Enter && !e.isShiftPressed && !e.isCtrlPressed -> { onSubmit(); true }
                    // ATTACH-INLINE-1: Ctrl+Shift+A is the paperclip.
                    e.isCtrlPressed && e.isShiftPressed && e.key == Key.A -> { pick(); true }
                    // IMG-2: Ctrl+V with a file or an image on the clipboard attaches it (a tag at the caret);
                    // anything else pastes as text.
                    e.isCtrlPressed && !e.isShiftPressed && e.key == Key.V -> host.clipboardFile()?.let { insertPicked(listOf(it)); true } ?: false
                    else -> false
                }
            },
            // Grows with its content up to 8 lines, then scrolls.
            minLines = 2, maxLines = 8, textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp),
        )
        Spacer(Modifier.height(6.dp))
        // MOBILE-TRANSCRIPT-1: a FlowRow, not a Row. On a phone the buttons fill the width, and in a Row the Resume hint
        // after them was measured a few pixels wide, wrapped a character per line and grew the composer over the whole
        // column, leaving the transcript 0dp tall. Here a button or the hint that does not fit starts a new line, and the
        // hint keeps to two lines whatever width it gets.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // UX-ICON-1: the action row is icons; each tooltip names the action and its shortcut.
            IconAction(
                AhqIcons.Send,
                when {
                    spawnMode || a == null -> "Spawn"
                    isBusy(a) -> "Queue"
                    else -> "Send"
                },
                onSubmit, shortcut = "Enter", enabled = live && (value.isNotBlank() || attachments.isNotEmpty()) && !attachBusy && blocker == null,
                style = IconActionStyle.FILLED,
            )
            if (a != null && !spawnMode) {
                IconAction(AhqIcons.SwapHoriz, SWITCH_AGENT_LABEL, onSwitchAgent, shortcut = SWITCH_AGENT_SHORTCUT)
            } else {
                IconAction(
                    if (spawnMode) AhqIcons.Close else AhqIcons.PersonAdd,
                    if (spawnMode) "Cancel new agent" else NEW_AGENT_LABEL,
                    onSpawnToggle, shortcut = NEW_AGENT_SHORTCUT,
                )
            }
            if (a != null && !spawnMode) {
                val canResume = live && a.session.resumable
                IconAction(AhqIcons.PlayArrow, "Resume", onClick = {
                    actionScope.launch {
                        if (controller.resume(a.id, wire(value).trim()) is CommandResult.Sent) onValue("")
                    }
                }, shortcut = "Ctrl+R", enabled = canResume)
                val running = a.session.state in setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)
                IconAction(
                    AhqIcons.Cancel, "Kill",
                    onClick = { actionScope.launch { controller.kill(a.id) } }, shortcut = "Ctrl+Shift+K", enabled = live && running,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Danger),
                )
                // SESSION-RM-1: on an ended session there is nothing left to force-kill, so the same trash button
                // removes it from the list (after the card menu's confirm). A running or idle one keeps Force kill.
                if (a.session.state in ENDED_STATES) {
                    var confirmingRemove by remember(a.id) { mutableStateOf(false) }
                    IconAction(
                        AhqIcons.DeleteForever, REMOVE_AGENT_LABEL,
                        onClick = { confirmingRemove = true }, enabled = live,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Danger),
                    )
                    if (confirmingRemove) {
                        RemoveAgentDialog(
                            a, snapshot, onDismiss = { confirmingRemove = false },
                            onRemove = { confirmingRemove = false; actionScope.launch { controller.removeSession(a.id) } },
                        )
                    }
                } else {
                    IconAction(
                        AhqIcons.DeleteForever, "Force kill",
                        onClick = { actionScope.launch { controller.kill(a.id, force = true) } }, shortcut = "Ctrl+Alt+K", enabled = live && running,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Danger),
                    )
                }
                InterruptButton(a, live, controller)
                if (!a.session.resumable && !running) {
                    Text(Controller.resumeBlocker(a.session), color = Palette.Muted, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically),
                    )
                } else if (a.session.resumable && value.isBlank()) {
                    Text("Resume with empty box sends: \"${Controller.RESUME_DEFAULT_PROMPT}\"", color = Palette.Muted, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
            if (!live) Text("Not connected: commands are disabled.", color = Palette.Danger, fontSize = 11.sp)
        }
    }
}

/** The one-line strip that shows the latest notice: a refused `/command`, or what a `/command` did. */
const val NOTICE_TAG = "notice-strip"
const val SLASH_LIST_TAG = "slash-suggestions"
const val SLASH_ITEM_TAG_PREFIX = "slash-item:"
const val SLASH_HEADER_TAG = "slash-header"
const val SLASH_NO_MATCH_TAG = "slash-no-match"

/** CP-4(c): the commands (or the values of one) matching what the human typed after "/"; a click puts the pick in the box. */
@Composable
private fun SlashSuggestions(header: String?, rows: List<SlashSuggestion>, highlighted: Int, onPick: (String) -> Unit) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag(SLASH_LIST_TAG).padding(bottom = 6.dp).background(Palette.Raised)) {
        // CMD-MODEL-1: whose models these are, e.g. "claude models".
        if (header != null) {
            Text(
                header, color = Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.testTag(SLASH_HEADER_TAG).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().testTag(if (r.selectable) SLASH_ITEM_TAG_PREFIX + r.replacement else SLASH_NO_MATCH_TAG)
                    .background(if (i == highlighted && r.selectable) Palette.Border else Palette.Raised)
                    .semantics { selected = i == highlighted && r.selectable }
                    .then(if (r.selectable) Modifier.clickable { onPick(r.replacement) } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.label, color = Palette.Text, fontFamily = Mono, fontSize = 12.sp)
                Text(r.detail, color = Palette.Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ReviewPane(state: AppState, controller: Controller) {
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
private fun PodsPane(state: AppState, controller: Controller) {
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
private fun StatusBar(state: AppState, onConfig: () -> Unit) {
    val c = state.connection
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
    HorizontalDivider(color = Palette.Border)
    Row(Modifier.fillMaxWidth().height(24.dp).background(Palette.Panel).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = if (c.isLive) Palette.Ok else if (c.state == ConnState.RECONNECTING || c.state == ConnState.CONNECTING) Palette.Accent else Palette.Danger
        Text(
            if (c.isLive) "● daemon ${c.daemonProvider ?: "?"}  contract ${c.contract ?: "?"}" else "● ${c.state.name.lowercase()}: ${c.detail}",
            color = color, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false),
        )
        c.lastFrameAt?.let { Text("   last frame ${ago(it, now)} ago", color = Palette.Muted, fontSize = 11.sp) }
        if (c.isLive && c.contract?.substringBefore('.') != "1") Text("   unexpected contract version", color = Palette.Danger, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("^K search  Alt+↑↓ agents  ^J needs input  ^N new  ^R resume  ^5 roles  ^6 attention  ^L connection  ^, config", color = Palette.Muted, fontSize = 10.sp)
        // The footer cog, bottom-right. It opens the configuration modal (flags).
        Box(
            Modifier.padding(start = 10.dp).size(24.dp)
                .clickable(role = Role.Button, onClick = onConfig)
                .semantics { contentDescription = "Configuration" },
            contentAlignment = Alignment.Center,
        ) { Text("\u2699", color = Palette.Muted, fontSize = 13.sp) }
    }
}

@Composable
private fun ConnectDialog(
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

const val SPAWN_ROLE_TAG_PREFIX = "spawn-role:"
const val SPAWN_ROLE_DEFAULT_TAG = "spawn-role-default"

/** The New agent box's role row: pick the role to spawn as and see what its binding makes the default (pick another model to override). */
@Composable
private fun SpawnRoleRow(state: AppState, controller: Controller) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Role", color = Palette.Muted, fontSize = 11.sp)
            Chip("none", if (state.spawnRole == null) Palette.Accent else Palette.Muted, Modifier.clickable { controller.pickSpawnRole(null) })
            state.roles.forEach { r ->
                Chip(r.id, if (state.spawnRole == r.id) Palette.Accent else Palette.Muted, Modifier.testTag(SPAWN_ROLE_TAG_PREFIX + r.id).clickable { controller.pickSpawnRole(r.id) })
            }
        }
        state.spawnRole?.let { role ->
            val chosen = state.pickedModel
            val bound = state.roleBindings[role]
            val overridden = bound != null && chosen != PickedModel.fromBinding(bound)
            Text(
                "Default for $role: ${bindingSummary(bound)}" + if (overridden) "  (overridden for this spawn: ${chosen?.let { it.provider + " / " + (it.model ?: "default") } ?: "daemon default"})" else "",
                color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(SPAWN_ROLE_DEFAULT_TAG),
            )
        }
    }
}
