// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.plugin.RowItem
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import com.superiorsoftllc.infantry.ui.net.ImageUploadOutcome
import com.superiorsoftllc.infantry.ui.protocol.ImageUploadWire
import com.superiorsoftllc.infantry.ui.pair.applyPairing
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.ServerChoice
import com.superiorsoftllc.infantry.ui.shell.ServerSwitcher
import com.superiorsoftllc.infantry.ui.shell.PickedImage
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.model.chatRows
import com.superiorsoftllc.infantry.ui.model.NOTE_LEVEL_FAIL
import com.superiorsoftllc.infantry.ui.model.NOTE_LEVEL_WARN
import com.superiorsoftllc.infantry.ui.model.NoticeKind
import com.superiorsoftllc.infantry.ui.model.noticesForSession
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.superiorsoftllc.infantry.ui.model.AgentGroup
import com.superiorsoftllc.infantry.ui.model.parseHexColor
import com.superiorsoftllc.infantry.ui.model.roleHue
import androidx.compose.foundation.horizontalScroll
import com.superiorsoftllc.infantry.ui.model.RightPanelWidth
import com.superiorsoftllc.infantry.ui.model.SidebarWidth
import com.superiorsoftllc.infantry.ui.model.CompactScreen
import com.superiorsoftllc.infantry.ui.model.PanelLayout
import com.superiorsoftllc.infantry.ui.model.PanelSide
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import com.superiorsoftllc.infantry.ui.model.isSafeChatUrl
import com.superiorsoftllc.infantry.ui.model.AgentScope
import com.superiorsoftllc.infantry.ui.model.SlashSuggestion
import com.superiorsoftllc.infantry.ui.model.isSlashCommand
import com.superiorsoftllc.infantry.ui.model.moveSlashSelection
import com.superiorsoftllc.infantry.ui.model.slashHeader
import com.superiorsoftllc.infantry.ui.model.HarnessSwitch
import com.superiorsoftllc.infantry.ui.model.switchChipText
import com.superiorsoftllc.infantry.ui.model.slashPick
import com.superiorsoftllc.infantry.ui.model.slashSubmitOnEnter
import com.superiorsoftllc.infantry.ui.model.slashSuggestions
import com.superiorsoftllc.infantry.ui.model.slashTyped
import com.superiorsoftllc.infantry.ui.model.branchLine
import com.superiorsoftllc.infantry.ui.model.changesView
import com.superiorsoftllc.infantry.ui.model.ticketBranchFor
import com.superiorsoftllc.infantry.ui.model.ticketTagFull
import com.superiorsoftllc.infantry.ui.model.ticketTagLabel
import com.superiorsoftllc.infantry.ui.model.reportFlagOf
import com.superiorsoftllc.infantry.ui.model.watchFlagOf
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.ChatItem
import com.superiorsoftllc.infantry.ui.model.TimelineItem
import com.superiorsoftllc.infantry.ui.model.withContributions
import com.superiorsoftllc.infantry.ui.model.wakeReasons
import com.superiorsoftllc.infantry.ui.model.ChatSpan
import com.superiorsoftllc.infantry.ui.model.ChatStatus
import com.superiorsoftllc.infantry.ui.model.OutputLine
import com.superiorsoftllc.infantry.ui.model.USER_AVATAR_ID
import com.superiorsoftllc.infantry.ui.model.UserPortrait
import com.superiorsoftllc.infantry.ui.model.contractAtLeast
import com.superiorsoftllc.infantry.ui.model.avatarFor
import com.superiorsoftllc.infantry.ui.model.chatItems
import com.superiorsoftllc.infantry.ui.model.chatLineBlocks
import com.superiorsoftllc.infantry.ui.model.parseChatInline
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.SessionExecutor
import com.superiorsoftllc.infantry.ui.model.COMPACTING_LABEL
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.DiffTarget
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.activityTimestamp
import com.superiorsoftllc.infantry.ui.model.isBusy
import com.superiorsoftllc.infantry.ui.model.QueuedPrompt
import com.superiorsoftllc.infantry.ui.model.interruptBlocker
import com.superiorsoftllc.infantry.ui.model.queuedFor
import com.superiorsoftllc.infantry.ui.model.isRunning
import com.superiorsoftllc.infantry.ui.model.needsInput
import com.superiorsoftllc.infantry.ui.model.insertIntoPrompt
import com.superiorsoftllc.infantry.ui.model.promptBlocker
import com.superiorsoftllc.infantry.ui.model.parseNodes
import com.superiorsoftllc.infantry.ui.model.stateLabel
import com.superiorsoftllc.infantry.ui.model.visibleAgents
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** CHAT-PAD-1: the one horizontal padding of every chat's content (empty state, transcript, notes, cards). */
val ChatSidePadding = 14.dp

@Composable
fun OutputPane(
    a: AgentView?,
    spawnMode: Boolean,
    roleColor: String? = null,
    onStartSession: (() -> Unit)?,
    /** GAP-006b: Start session / Resume for the session shown when it has nothing on screen (null = none to offer). */
    paneAction: com.superiorsoftllc.infantry.ui.model.SessionPaneAction? = null,
    onApplyStart: (com.superiorsoftllc.infantry.ui.model.StartPlan) -> Unit = {},
    onResume: (String) -> Unit = {},
    /** SCRIPT-VIS-1b: lines plugins contributed to this session's chat (automation runs); empty = none. */
    contributions: List<TimelineItem> = emptyList(),
    /** CHAT-MINIMIZE-5: the host's floating chips (Expand, Minimize / Restore), drawn in the bottom-right float row. */
    floaters: @Composable RowScope.() -> Unit = {},
    /** CHAT-SENDING-1: the messages sent to this session that the transcript does not show yet, and their retry / discard. */
    pending: PendingSendsView = PendingSendsView(),
    questionSlot: @Composable () -> Unit = {},
) {
    if (a == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Box(Modifier.align(Alignment.BottomEnd)) { FloatRow(floaters) }
            if (spawnMode) return@Box
            Column(Modifier.fillMaxWidth().padding(horizontal = ChatSidePadding), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandEmblem(72.dp)
                Spacer(Modifier.height(10.dp))
                Text("No live session. Start one to see its output here.", color = Palette.Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                if (onStartSession != null) OutlinedButton(onClick = onStartSession) { Text("Start a session") }
            }
        }
        return
    }
    if (com.superiorsoftllc.infantry.ui.model.showsResumeSplash(a.session, paneAction) || a.output.isEmpty() && pending.items.isEmpty() && contributions.none { !it.isStats } && a.chatStatus(Clock.System.now().toEpochMilliseconds()) == null) {
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Box(Modifier.align(Alignment.BottomEnd)) { FloatRow(floaters) }
            Column(Modifier.fillMaxWidth().padding(horizontal = ChatSidePadding), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandEmblem(72.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (a.session.global) "Ask the Assistant anything about Infantry AI: how a screen works, why something failed, or tell it what to do (create a ticket, start an agent, change a setting)."
                    else "Nothing on screen for this session yet.\nThe daemon replays a session's recent output only while the process that ran it is up; older history is not kept.",
                    color = Palette.Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                // GAP-006b: a session on screen starts or resumes ITSELF; the New agent box would cut a second instance.
                if (paneAction != null) SessionStartPane(paneAction, onApplyStart, onResume)
                else if (onStartSession != null) OutlinedButton(onClick = onStartSession) { Text("Start a session") }
            }
        }
        questionSlot()
      }
        return
    }
    AgentChat(a, roleColor = roleColor, questionSlot = questionSlot, contributions = contributions, floaters = floaters, pending = pending)
}

/** CHAT-SENDING-1: one session's pending sends ([PendingSendRow]) and what their actions do. */
data class PendingSendsView(
    val items: List<com.superiorsoftllc.infantry.ui.model.PendingSend> = emptyList(),
    val onRetry: (String) -> Unit = {},
    val onDiscard: (String) -> Unit = {},
)

/** Test tag on the transient status row under the transcript (or in place of the empty state). */
const val CHAT_STATUS_TAG = "chat-status"

/** UI-ACT-2: test tag on the collapsible Activity box above the chat input. */
const val ACTIVITY_BOX_TAG = "activity-box"

/** UI-ACT-2: test tag on each activity line's label inside the box. */
const val ACTIVITY_LINE_TAG = "activity-line"

/**
 * The chat transcript for one agent, plus [AgentView.chatStatus] when the latest turn has no answer yet.
 * The status row sits under the last line and is absent once [AgentView.chatStatus] is null.
 */
@Composable
fun AgentChat(agent: AgentView, nowProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() }, roleColor: String? = null, questionSlot: @Composable () -> Unit = {}, contributions: List<TimelineItem> = emptyList(), floaters: @Composable RowScope.() -> Unit = {}, pending: PendingSendsView = PendingSendsView()) {
    val list = rememberLazyListState()
    // The elapsed label must age on its own: no status frame may arrive for seconds while the agent
    // works, so a one-second ticker keeps "Thinking... 3s" counting with the transcript otherwise still.
    // [nowProvider] is the clock (injectable so a UI test can drive it with the Compose frame clock).
    var now by remember { mutableStateOf(nowProvider()) }
    LaunchedEffect(agent.id) { while (true) { delay(1_000); now = nowProvider() } }
    // CHAT-SCROLL-1: the rows are rebuilt when the transcript changes, never on the ticker's second (a long chat rebuilt
    // thousands of rows every second before).
    val placement = LocalReasoningPlacement.current
    val output = agent.output
    val rows = remember(output, contributions, placement) { withContributions(placement.chatRows(chatItems(output)), contributions) }
    val ownerRows = remember(rows) { rows.count { it is ChatItem.Line && it.line.kind == LineKind.USER } }
    val total = rows.size
    val pageSize = LocalShellContext.current?.config?.values("infantry.chatfeed")?.int(CHAT_PAGE_SIZE_KEY) ?: CHAT_PAGE_SIZE_DEFAULT
    // CHAT-SCROLL-1: only the newest page is composed on open; older pages join as the user scrolls up (ChatWindow).
    val window = remember(agent.id, pageSize) { ChatWindow(pageSize, total) }
    val start = window.startFor(total)
    val shown = total - start
    val status = agent.chatStatus(now)
    val hasStatus = status != null
    // CHAT-BG-1: the turn ended but background jobs still run: ONE quiet row, the list's last item (never a message).
    val bgWork = trailingBackgroundWork(agent.session, status)
    val hasBg = bgWork != null
    val footerRows = (if (hasStatus) 1 else 0) + (if (hasBg) 1 else 0)
    val totalNow by rememberUpdatedState(total)
    val sends = pending.items
    // A phone-width pane: the bubble's retry / discard become 48dp touch targets.
    val compact = com.superiorsoftllc.infantry.ui.model.PanelLayout.isCompact(com.superiorsoftllc.infantry.ui.block.layout.LocalBlockSize.current.width.value)
    // Follow = the last item is on screen. Read off the layout only when the item count did not change in it (a new row
    // lands below the view before the follow scroll reaches it, which is not the user scrolling away), and only once the
    // chat has reached its bottom after opening.
    LaunchedEffect(list, window) {
        var lastCount = -1
        var settled = false
        snapshotFlow { list.layoutInfo }.collect { info ->
            val count = info.totalItemsCount
            val atEnd = count == 0 || info.visibleItemsInfo.lastOrNull()?.index == count - 1
            if (atEnd) settled = true
            // A pending jump (a search hit, jump-to-latest) decides follow itself: the layout between widening the slice and
            // the jump still sits at the old bottom, and reading it would trim the hit's page away again.
            if (settled && count == lastCount && atEnd != window.follow && window.pendingJump == null) {
                window.follow = atEnd
                if (atEnd) window.onTotal(totalNow)
            }
            lastCount = count
        }
    }
    // Scrolled near the top of the slice: the next older page joins it. Rows are keyed, so the view does not move.
    LaunchedEffect(list, window) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { first ->
            if (!window.follow && first < CHAT_LOAD_OLDER_WITHIN) window.loadOlder()
        }
    }
    // The owner's own new message always lands in view, however far up the chat was.
    var ownerSeen by remember(agent.id) { mutableStateOf(ownerRows) }
    // CHAT-SENDING-1: a new pending send counts too, so the human sees it the moment Send is pressed.
    var sendsSeen by remember(agent.id) { mutableStateOf(sends.size) }
    LaunchedEffect(ownerRows, sends.size) {
        if (ownerRows > ownerSeen || sends.size > sendsSeen) window.jumpToLatest(totalNow)
        ownerSeen = ownerRows
        sendsSeen = sends.size
    }
    // Keyed on the status PRESENCE, not its value: the ticker changes it every second, and that must
    // not restart the scroll effect. Scrolled up, new rows do not move the view (they count as unread).
    LaunchedEffect(agent.id, total, output.lastOrNull()?.text?.length, hasStatus, hasBg, sends.size) {
        window.onTotal(total)
        if (window.follow && window.pendingJump == null) {
            val last = total - window.startFor(total) + sends.size + footerRows - 1
            if (last >= 0) list.requestScrollToItem(last)
        }
    }
    LaunchedEffect(window.pendingJump, start, total) {
        val jump = window.pendingJump ?: return@LaunchedEffect
        val index = if (jump == ChatWindow.JUMP_TO_END) total - start + sends.size + footerRows - 1 else jump - start
        // QA-FIX-B: requestScrollToItem, not scrollToItem: the latter forces a remeasure from this effect, which can subcompose
        // while a composition is still pending (Compose "pending composition has not been applied", SidebarDeleteUiTest).
        if (index >= 0) list.requestScrollToItem(index)
        window.pendingJump = null
    }
    // CHAT-SCROLL-1: search the whole transcript (text, ticket ids); a hit outside the slice loads its page.
    var searchOpen by remember(agent.id) { mutableStateOf(false) }
    var query by remember(agent.id) { mutableStateOf("") }
    val hits = remember(rows, query) { chatSearchHits(rows, query) }
    var hitPos by remember(agent.id, query) { mutableStateOf(-1) }
    fun goToHit(pos: Int) {
        if (hits.isEmpty()) return
        hitPos = pos.coerceIn(0, hits.lastIndex)
        window.reveal(hits[hitPos])
    }
    // A typed query jumps to its newest hit; the arrows (or Enter / Shift+Enter) walk to older and newer ones.
    LaunchedEffect(hits) { if (hitPos < 0 && hits.isNotEmpty()) goToHit(hits.lastIndex) }
    val currentHit = hits.getOrNull(hitPos)
    val name = agent.session.worktreeBranch ?: agent.id.take(8)
    val agentSide = agentSideOf(LocalTabFactory.current)
    val withSearch: @Composable RowScope.() -> Unit = {
        floaters()
        FloatingChip(AhqIcons.Search, CHAT_SEARCH_LABEL, { searchOpen = !searchOpen }, Modifier.testTag(CHAT_SEARCH_CHIP_TAG), shortcut = "Ctrl+F")
    }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.F) { searchOpen = true; true } else false
    }) {
        // CHAT-COMPACT-1: the Activity chip floats over the transcript's bottom edge: it takes no row of its own.
        Box(Modifier.weight(1f)) {
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = ChatSidePadding).testTag(CHAT_TRANSCRIPT_TAG), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = if (searchOpen) CHAT_SEARCH_BAR_ROOM_DP.dp else 10.dp, bottom = 10.dp)) {
                // CHAT-SCROLL-1: keyed by the row's absolute index, so rows joining above (an older page) or below (new
                // output) leave the rows on screen where they are.
                items(shown, key = { start + it }) { i ->
                    val abs = start + i
                    // M2-2: the agent's portrait opens each run of agent-side lines, like a chat. KINDS-1: the row is drawn by the renderer of its kind.
                    Box(if (abs == currentHit) Modifier.background(Palette.Accent.copy(alpha = 0.16f)).testTag(CHAT_SEARCH_HIT_TAG) else Modifier) {
                        FeedRow(RowItem(rows[abs], agent.id, name, agent.role, roleColor), startsAgentRun(rows, abs, agentSide))
                    }
                }
                // CHAT-SENDING-1: the human's just-sent messages, after the transcript, until the daemon's echo replaces each.
                items(sends.size, key = { "pending-" + sends[it].clientId }) { i ->
                    PendingSendRow(sends[i], pending.onRetry, pending.onDiscard, compact = compact)
                }
                // The live status row is agent-side too: it opens a run only when no agent line precedes it.
                if (status != null) item(key = CHAT_STATUS_TAG) {
                    AgentSideRow(sends.isNotEmpty() || startsAgentRun(rows, total, agentSide), agent.role, agent.id, roleColor) { ChatStatusRow(status) }
                }
                if (bgWork != null) item(key = CHAT_BG_ROW_TAG) {
                    AgentSideRow(status == null && (sends.isNotEmpty() || startsAgentRun(rows, total, agentSide)), agent.role, agent.id, roleColor) { BackgroundWorkRow(bgWork, now) }
                }
            }
        }
        if (searchOpen) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            ChatSearchBar(query, { query = it }, hits.size, hitPos,
                older = { goToHit(if (hitPos < 0) hits.lastIndex else hitPos - 1) },
                newer = { goToHit(if (hitPos < 0) hits.lastIndex else hitPos + 1) },
                close = { searchOpen = false; query = "" })
        }
        if (!window.follow) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
            JumpToLatest(window.unread(total)) { window.jumpToLatest(total) }
        }
        Box(Modifier.align(Alignment.BottomEnd)) { ActivityBox(agent, withSearch) }
        }
        questionSlot()
    }
}

/** CHAT-SCROLL-1: test tag on the transcript's lazy list. */
const val CHAT_TRANSCRIPT_TAG = "chat-transcript"

/** CHAT-SCROLL-1: test tag on the "jump to latest" button shown while scrolled up. */
const val JUMP_LATEST_TAG = "chat-jump-latest"

/** CHAT-SCROLL-1: test tag on the search chip, the search field and the row of the current hit. */
const val CHAT_SEARCH_CHIP_TAG = "chat-search-chip"
const val CHAT_SEARCH_FIELD_TAG = "chat-search-field"
const val CHAT_SEARCH_HIT_TAG = "chat-search-hit"
const val CHAT_SEARCH_OLDER_TAG = "chat-search-older"
const val CHAT_SEARCH_NEWER_TAG = "chat-search-newer"

/** CHAT-SCROLL-1: the transcript's top padding while the search bar is open, so a hit scrolled to the top shows below the bar. */
const val CHAT_SEARCH_BAR_ROOM_DP = 56

/** CHAT-SCROLL-1: the search chip's tooltip and screen-reader name. */
const val CHAT_SEARCH_LABEL = "Search this chat"

/** CHAT-SCROLL-1: "↓ Latest", with the number of rows that arrived since the user scrolled up (uncapped). */
@Composable
private fun JumpToLatest(unread: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Accent).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp).testTag(JUMP_LATEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AhqIcons.ChevronDown, contentDescription = null, tint = Palette.OnAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (unread > 0) "Latest · $unread new" else "Latest", color = Palette.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** CHAT-SCROLL-1: the search bar over the transcript: the query, "n of m", older/newer hit and close. Enter = older hit, Esc = close. */
@Composable
private fun ChatSearchBar(query: String, onQuery: (String) -> Unit, hitCount: Int, hitPos: Int, older: () -> Unit, newer: () -> Unit, close: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Panel).border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AhqIcons.Search, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search this chat: text or a ticket id", color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 12.sp), cursorBrush = SolidColor(Palette.Text),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(CHAT_SEARCH_FIELD_TAG).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { if (e.isShiftPressed) newer() else older(); true }
                        Key.Escape -> { close(); true }
                        else -> false
                    }
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when { query.isBlank() -> ""; hitCount == 0 -> "no match"; hitPos < 0 -> "$hitCount found"; else -> "${hitPos + 1} of $hitCount" },
            color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text("▲", color = Palette.Text, fontSize = 12.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = hitCount > 0, onClick = older).padding(4.dp).testTag(CHAT_SEARCH_OLDER_TAG))
        Text("▼", color = Palette.Text, fontSize = 12.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = hitCount > 0, onClick = newer).padding(4.dp).testTag(CHAT_SEARCH_NEWER_TAG))
        Icon(AhqIcons.Close, contentDescription = "Close search", tint = Palette.Muted, modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = close).padding(2.dp))
    }
}

/** The open/closed choice the user made on the Activity header, remembered for the window (ACTIVITY-BOX-1). */
private var activityBoxChoice by mutableStateOf<Boolean?>(null)

/** Forget the remembered open/closed choice (a fresh window; tests). */
fun resetActivityBoxChoice() { activityBoxChoice = null }

internal const val ACTIVITY_AUTO_OPEN_KEY = "activityBox.autoOpenWhileRunning"

/**
 * UI-ACT-2: the selected session's tool activity, newest first, above the chat input. Collapsible; it
 * starts collapsed (ACTIVITY-BOX-1). A click on the header is remembered for the window, across turns and session
 * switches. The `activityBox.autoOpenWhileRunning` setting (default off) restores opening while the session runs.
 */
@Composable
fun ActivityBox(agent: AgentView, floaters: @Composable RowScope.() -> Unit = {}) {
    val entries = agent.activityLog
    // ACTIVITY-BOX-1: collapsed unless the user chose otherwise (remembered per window) or the setting asks for
    // auto-open while the session runs.
    val autoOpen = LocalShellContext.current?.config?.values("infantry.chatfeed")?.boolean(ACTIVITY_AUTO_OPEN_KEY) ?: false
    val expanded = entries.isNotEmpty() && (activityBoxChoice ?: (autoOpen && isRunning(agent.session.state)))
    Column(horizontalAlignment = Alignment.End) {
        if (expanded) Box(Modifier.padding(horizontal = 8.dp)) { ActivityLines(entries) }
        // CHAT-MINIMIZE-5: one row of floating chips at the bottom-right: the host's floaters (Expand, Minimize / Restore),
        // then the Activity chip. No count on the chip (it capped and stopped meaning anything); the icon alone.
        FloatRow(floaters) {
            if (entries.isNotEmpty()) FloatingChip(AhqIcons.Activity, ACTIVITY_LABEL, { activityBoxChoice = !expanded }, Modifier.testTag(ACTIVITY_BOX_TAG))
        }
    }
}

/** CHAT-MINIMIZE-5: the Activity chip's tooltip and screen-reader name. */
const val ACTIVITY_LABEL = "Activity"

/** CHAT-MINIMIZE-5: the bottom-right row of floating chips: [floaters] from the host, then [trailing] (this feed's own). */
@Composable
fun FloatRow(floaters: @Composable RowScope.() -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.padding(end = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        floaters()
        trailing()
    }
}

/** The activity lines, newest first, capped at ~160dp, scrollable, monospace, on an opaque panel over the transcript. */
@Composable
private fun ActivityLines(entries: List<com.superiorsoftllc.infantry.ui.model.AgentActivity>) {
    run {
        Column(Modifier.fillMaxWidth().background(Palette.Panel).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            entries.asReversed().forEach { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = ChatSidePadding, vertical = 1.dp)) {
                    Text(activityTimestamp(e.at), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(e.label, color = Palette.Text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.testTag(ACTIVITY_LINE_TAG))
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

/** Renders parsed assistant markdown through the active [ChatMarkdownConfig]; re-parsed by the caller on every streamed update. */
@Composable
internal fun ChatBlocks(blocks: List<ChatBlock>, renderers: ChatBlockRenderers) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) renderers.rendererFor(b).Render(b)
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
            // CHAT-SCROLL-1: the header opens THIS block (ACTIVITY-BOX-1 had it toggling the Activity box by mistake).
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
internal fun SpeakerRow(avatar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(SPEAKER_ROW_TAG), verticalAlignment = Alignment.Top) {
        avatar()
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** Size of the agent's portrait on its chat lines, and of the indent its continuation lines keep. */
internal const val CHAT_AVATAR_SIZE = 36

/**
 * M2-2: whether the agent-side item at [index] opens a run and so carries the agent's portrait. A run
 * is a stretch of consecutive agent-side items ([agentSide], by the renderer of each item's kind); a user line ends it.
 * [index] == rows.size asks about the live status row that follows the transcript.
 */
internal fun startsAgentRun(rows: List<ChatItem>, index: Int, agentSide: (ChatItem) -> Boolean): Boolean {
    if (index < rows.size && !agentSide(rows[index])) return false
    return index == 0 || !agentSide(rows[index - 1])
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
 * One transcript line as the chat shows it, drawn by the renderer registered for its kind (an agent's markdown, the user's own
 * echo, a status / error / finished / log marker). [showAvatar]: the agent's portrait when the line opens a run, else its indent.
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
    FeedRow(RowItem(ChatItem.Line(line), agentId, agentName, agentRole, agentRoleColor), showAvatar)
}

/** KINDS-1: draws one row through the renderer registered for its kind; a kind nobody registered draws the generic row. */
@Composable
internal fun FeedRow(row: RowItem, showAvatar: Boolean) {
    val renderer = feedRendererOf(LocalTabFactory.current, row.kind)
    if (renderer.agentSide) AgentSideRow(showAvatar, row.agentRole, row.agentId, row.roleColor) { renderer.content(row) }
    else renderer.content(row)
}

/** Test tag on the quiet send/response time at the top-right of a chat card. */
const val CARD_TIMESTAMP_TAG = "card-timestamp"

/** Test tag on the card header's title. */
const val CARD_TITLE_TAG = "card-title"

/**
 * A chat card: a light header band (icon, bold title, muted time on one line) over the body, all tinted from
 * [style]'s tone. Compact: one text line of header plus small padding; the body has the normal padding.
 */
@Composable
fun CommsCard(style: CommsCardStyle, at: Long, trailing: (@Composable () -> Unit)? = null, body: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).background(style.tone.body)) {
        Row(
            Modifier.fillMaxWidth().background(style.tone.header).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (style.showIcon) {
                androidx.compose.material3.Icon(
                    AhqIcons.Radio, contentDescription = null, tint = Palette.Text,
                    modifier = Modifier.size(16.dp).testTag(CARD_COMMS_ICON_TAG),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                style.title, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(CARD_TITLE_TAG),
            )
            // CHAT-SENDING-1: [trailing] (a pending send's state marker) takes the time's place.
            if (trailing != null) trailing()
            else if (style.showTime) {
                Text(
                    activityTimestamp(at), color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp,
                    maxLines = 1, softWrap = false, textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 8.dp).testTag(CARD_TIMESTAMP_TAG),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(8.dp)) { body() }
    }
}

const val CARD_COMMS_ICON_TAG = "card-comms-icon"

/** SCRIPT-VIS-1b: test tag on a plugin-contributed (automation) chat row. */
const val CONTRIBUTION_ROW_TAG = "contribution-row"

/** CHAT-NOTE-WARN-1: test tag on a contributed row's mark (gear, or the warning sign for a warn note). */
const val CONTRIBUTION_MARK_TAG = "contribution-mark"

/** SCRIPT-VIS-1b: test tag on the expanded details of a contributed row. */
const val CONTRIBUTION_DETAILS_TAG = "contribution-details"

/**
 * SCRIPT-VIS-1b: a line a plugin put in the chat without a turn. Drawn as automation (a gear mark, its label, a muted panel), never as the
 * agent's speech; click to expand its [TimelineItem.details] (the run's actions and log lines). A failure is drawn in the error colour.
 * CHAT-NOTE-WARN-1: a warn note ([TimelineItem.noteLevel]) is drawn in the warning colour with a warning mark.
 */
@Composable
fun ContributionRow(item: TimelineItem) {
    var open by remember(item.id) { mutableStateOf(false) }
    // CHAT-NOTE-WARN-1: warn is the theme's warning colour with a warning mark, otherwise drawn like info (no error styling).
    val level = item.noteLevel
    val tint = when (level) { NOTE_LEVEL_FAIL -> Palette.Danger; NOTE_LEVEL_WARN -> Palette.Warn; else -> Palette.Muted }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.Panel).testTag(CONTRIBUTION_ROW_TAG)
        .clickable(enabled = item.details.isNotEmpty()) { open = !open }.padding(horizontal = 10.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (level == NOTE_LEVEL_WARN) "⚠" else "⚙", color = tint, fontSize = 13.sp, modifier = Modifier.testTag(CONTRIBUTION_MARK_TAG))
            Spacer(Modifier.width(8.dp))
            Text(item.text.ifBlank { item.label }, color = tint, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
            // SCRIPT-LINE-TS-1: the run's time, top right, same formatter/font as the message cards.
            if (item.at > 0) Text(
                activityTimestamp(item.at) + (item.meta["durationMs"]?.toLongOrNull()?.let { " · ${it / 1000}.${(it % 1000) / 100} s" } ?: ""),
                color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp,
                maxLines = 1, softWrap = false, textAlign = TextAlign.End,
                modifier = Modifier.padding(horizontal = 8.dp).testTag(CARD_TIMESTAMP_TAG),
            )
            if (item.details.isNotEmpty()) Text(if (open) "▴" else "▾", color = Palette.Muted, fontSize = 12.sp)
        }
        // CHAT-IMAGE-1: the same sent-image rendering as a ticket's images; remote clients use the inlined copy.
        item.meta["image"]?.let { path ->
            val data = remember(item.id) { item.meta["imageData"]?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() } }
            Column(Modifier.padding(start = 21.dp, top = 4.dp)) { ChatSentImages(listOf(path), data) }
        }
        if (open) Column(Modifier.padding(start = 21.dp, top = 3.dp).testTag(CONTRIBUTION_DETAILS_TAG)) {
            item.details.forEach { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp) }
        }
    }
}

