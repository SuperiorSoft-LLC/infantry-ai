package com.superiorsoftllc.agenthq.plugins.official.chat

import com.superiorsoftllc.agenthq.ui.*

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
import com.superiorsoftllc.agenthq.ui.model.chatRows
import com.superiorsoftllc.agenthq.ui.model.NoticeKind
import com.superiorsoftllc.agenthq.ui.model.noticesForSession
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

// CHAT-PANEL-1: the chat surface (header, transcript, activity, composer) moved verbatim out of App.kt.

/** M2-3: the session header's title (the role's display name) and its secondary id line. */
const val AGENT_HEADER_TITLE_TAG = "agent-header-title"
const val AGENT_HEADER_ID_TAG = "agent-header-id"

/** UX-HEADER-WRAP-1: the width the header's title column always keeps, however many chips sit to its right. */
val HEADER_TITLE_MIN_WIDTH = 160.dp

/** Below this header width the executor chips move to their own scrolling row. */
val NARROW_HEADER_WIDTH = 600.dp
const val AGENT_HEADER_CHIPS_TAG = "agent-header-chips"

@OptIn(ExperimentalLayoutApi::class)
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
    val narrow = maxWidth < NARROW_HEADER_WIDTH
    val chips: @Composable () -> Unit = {
        if (a != null) {
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
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(12.dp, if (narrow) 8.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (a == null || spawnMode) {
            Text(if (spawnMode) "New agent: type its task below and press Enter" else "No agent selected", color = Palette.Muted)
            return@Row
        }
        AgentAvatar(a, state, 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Non-weighted siblings are measured first, so the title takes only what is left and ellipsizes;
                // the id is ellipsized too, so it can never be clipped down to a stray glyph.
                Text(state.agentTitle(a), fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag(AGENT_HEADER_TITLE_TAG))
                state.agentSecondaryId(a)?.let { Spacer(Modifier.width(6.dp)); Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(AGENT_HEADER_ID_TAG)) }
                a.role?.let { Spacer(Modifier.width(8.dp)); Chip(it, Palette.Accent) }
                Spacer(Modifier.width(8.dp))
                val waiting = needsInput(a, state.waiting, state.openPrompts, state.openQuestionSessions)
                Chip(if (waiting) "needs input" else a.stateText, stateColor(a.session.state, waiting))
                // TRAITS-T9: the REPORT BACK flag, in the session view header.
                reportFlagOf(a.session.report)?.let { Spacer(Modifier.width(8.dp)); ReportFlagChip(it, Modifier.testTag("agent-report-flag")) }
            }
            TicketTag(a, state, onOpenTicket, Modifier.testTag(AGENT_HEADER_TICKET_TAG))
            // Contract 1.117.0: the agent's own progress report on its long job.
            a.session.progress?.let { ProgressMeter(it, "header-${a.id}", Modifier.padding(vertical = 2.dp).widthIn(max = 480.dp)) }
            Text(a.lastActivity, color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
            // BRANCH-PER-TASK 2/3: the branch of the started ticket this agent runs; nothing when it holds none.
            state.ticketBranchFor(a.id)?.branchLine()?.let { Text(it, color = Palette.Info, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, modifier = Modifier.testTag("agent-ticket-branch")) }
        }
        if (!narrow) FlowRow(Modifier.widthIn(max = chipsMax), itemVerticalAlignment = Alignment.CenterVertically) { chips() } // wraps, never clipped to an empty box (QA F3)
    }
    // A phone-width header: the chips get their own row under the title and wrap onto more lines, never clipped.
    if (narrow && a != null && !spawnMode) {
        FlowRow(Modifier.fillMaxWidth().background(Palette.Panel).padding(12.dp, 0.dp, 12.dp, 8.dp).testTag(AGENT_HEADER_CHIPS_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) { chips() }
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

@Composable
fun OutputPane(
    a: AgentView?,
    spawnMode: Boolean,
    roleColor: String? = null,
    onStartSession: (() -> Unit)?,
    /** GAP-006b: Start session / Resume for the session shown when it has nothing on screen (null = none to offer). */
    paneAction: com.superiorsoftllc.agenthq.ui.model.SessionPaneAction? = null,
    onApplyStart: (com.superiorsoftllc.agenthq.ui.model.StartPlan) -> Unit = {},
    onResume: (String) -> Unit = {},
    questionSlot: @Composable () -> Unit = {},
) {
    if (a == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (spawnMode) return@Box
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BrandEmblem(72.dp)
                Spacer(Modifier.height(10.dp))
                Text("No live session. Start one to see its output here.", color = Palette.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                if (onStartSession != null) OutlinedButton(onClick = onStartSession) { Text("Start a session") }
            }
        }
        return
    }
    if (a.output.isEmpty() && a.chatStatus(Clock.System.now().toEpochMilliseconds()) == null) {
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BrandEmblem(72.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (a.session.global) "Ask the Assistant anything about Infantry AI: how a screen works, why something failed, or tell it what to do (create a ticket, start an agent, change a setting)."
                    else "Nothing on screen for this session yet.\nThe daemon replays a session's recent output only while the process that ran it is up; older history is not kept.",
                    color = Palette.Muted, fontSize = 12.sp,
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
    AgentChat(a, roleColor = roleColor, questionSlot = questionSlot)
}

/** Test tag on the transient status row under the transcript (or in place of the empty state). */
const val CHAT_STATUS_TAG = "chat-status"

/** Test tag on the Interrupt control beside the prompt box. */
const val INTERRUPT_BUTTON_TAG = "interrupt-button"

/** SESSION-RM-1: the composer trash button on an ended session (it removes the agent from the list). */
const val REMOVE_AGENT_LABEL = "Remove agent"

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
fun AgentChat(agent: AgentView, nowProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() }, roleColor: String? = null, questionSlot: @Composable () -> Unit = {}) {
    val list = rememberLazyListState()
    // The elapsed label must age on its own: no status frame may arrive for seconds while the agent
    // works, so a one-second ticker keeps "Thinking... 3s" counting with the transcript otherwise still.
    // [nowProvider] is the clock (injectable so a UI test can drive it with the Compose frame clock).
    var now by remember { mutableStateOf(nowProvider()) }
    LaunchedEffect(agent.id) { while (true) { delay(1_000); now = nowProvider() } }
    val rows = LocalReasoningPlacement.current.chatRows(chatItems(agent.output))
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
        questionSlot()
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

/** Renders parsed assistant markdown through the active [ChatMarkdownConfig]; re-parsed by the caller on every streamed update. */
@Composable
private fun ChatBlocks(blocks: List<ChatBlock>, renderers: ChatBlockRenderers) {
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
private const val CHAT_AVATAR_SIZE = 36

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
        SpeakerRow({ OriginAvatar(line.origin, CHAT_AVATAR_SIZE) }) {
            Column {
                OriginLabel(line.origin)
                CommsCard(commsCardStyle(line.origin), line.at) {
                    if (said.isNotBlank() || images.isEmpty()) {
                        Text(said, color = Palette.Accent, fontFamily = Mono, fontSize = 12.sp)
                    }
                    ChatSentImages(images)
                }
            }
        }
        return
    }
    AgentSideRow(showAvatar, agentRole, agentId, agentRoleColor) {
        when (line.kind) {
            LineKind.TEXT -> CommsCard(AgentReplyCardStyle, line.at) {
                val md = LocalChatMarkdown.current
                ChatBlocks(chatLineBlocks(line.kind, line.text, md.engine).orEmpty(), md.renderers)
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

/** Test tag on the card header's title. */
const val CARD_TITLE_TAG = "card-title"

/**
 * A chat card: a light header band (icon, bold title, muted time on one line) over the body, all tinted from
 * [style]'s tone. Compact: one text line of header plus small padding; the body has the normal padding.
 */
@Composable
fun CommsCard(style: CommsCardStyle, at: Long, body: @Composable () -> Unit) {
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
            if (style.showTime) {
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

@Composable
fun NoticeStrip(state: AppState, sessionId: String? = null) {
    val n = state.noticesForSession(sessionId).lastOrNull() ?: return
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

/** NOTICE-COLOUR-1: routine is the theme's success green, a heads-up amber, and only an error is red. */
fun noticeColour(kind: NoticeKind): Color = when (kind) {
    NoticeKind.OK -> Palette.Ok
    NoticeKind.HEADS_UP -> Palette.Warn
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
        AhqIcons.Stop, STOP_TURN_LABEL,
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
internal fun PromptBox(
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
                // Ticket a7f33ba2: the old Kill slot reaps this session's background jobs and leaves the session alive.
                IconAction(
                    AhqIcons.Cancel, STOP_BACKGROUND_LABEL,
                    onClick = { actionScope.launch { controller.stopBackgroundProcesses(a.id) } }, shortcut = STOP_BACKGROUND_SHORTCUT,
                    enabled = live && backgroundProcessPids(a.session).isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Danger),
                )
                // SESSION-RM-1: on an ended session there is nothing left to end, so the same trash button removes it
                // from the list (after the card menu's confirm). A session that has not ended keeps End session, enabled
                // whatever its state (it used to need a running turn, so an IDLE session could not be ended).
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
                        AhqIcons.DeleteForever, END_SESSION_LABEL,
                        onClick = { actionScope.launch { controller.endSession(a.id) } }, shortcut = END_SESSION_SHORTCUT, enabled = live,
                        style = IconActionStyle.FILLED,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = androidx.compose.ui.graphics.Color.White),
                    )
                }
                var confirmingRestart by remember(a.id) { mutableStateOf(false) }
                IconAction(AhqIcons.Refresh, RESTART_SESSION_LABEL, onClick = { confirmingRestart = true }, enabled = live)
                if (confirmingRestart) {
                    RestartSessionDialog(
                        a, snapshot, onDismiss = { confirmingRestart = false },
                        onRestart = { confirmingRestart = false; actionScope.launch { controller.restartSession(a.id, confirmed = true) } },
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

const val SPAWN_ROLE_TAG_PREFIX = "spawn-role:"
const val SPAWN_ROLE_DEFAULT_TAG = "spawn-role-default"

/** The New agent box's role row: pick the role to spawn as and see what its binding makes the default (pick another model to override). */
@Composable
internal fun SpawnRoleRow(state: AppState, controller: Controller) {
    // PICKER-UX: room above the role tags (they sat flat against the banner) and a bold section label.
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Role", Palette.Text, Modifier.padding(end = 4.dp), topGap = false)
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
