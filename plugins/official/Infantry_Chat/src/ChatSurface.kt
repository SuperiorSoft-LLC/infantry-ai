// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat

import com.superiorsoftllc.infantry.ui.*

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Icon
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
import com.superiorsoftllc.infantry.ui.shell.ServerChoice
import com.superiorsoftllc.infantry.ui.shell.ServerSwitcher
import com.superiorsoftllc.infantry.ui.shell.PickedImage
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.layout.onSizeChanged
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.model.chatRows
import com.superiorsoftllc.infantry.ui.model.NoticeKind
import com.superiorsoftllc.infantry.ui.model.noticesForSession
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
import com.superiorsoftllc.infantry.ui.model.forcesMidTurn
import com.superiorsoftllc.infantry.ui.block.layout.LocalBlockSize
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
        // CONTEXT-LIMIT-2: the session's context limit; nothing when none is set.
        state.contextLimitChip(a)?.let { Spacer(Modifier.width(6.dp)); ContextLimitChipView(it) }
        // EFFORT-1: the session's reasoning effort; nothing when none is set.
        state.effortChip(a)?.let { Spacer(Modifier.width(6.dp)); EffortChipView(it) }
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
    val gate = com.superiorsoftllc.infantry.ui.shell.rememberGate("chat.interrupt")
    val busy = isBusy(agent)
    ComposerRound(
        AhqIcons.Stop, gate.label(STOP_TURN_LABEL),
        onClick = { actionScope.launch { controller.interrupt(agent.id) } },
        enabled = gate.allowed && live && busy,
        modifier = Modifier.testTag(INTERRUPT_BUTTON_TAG), content = Palette.Danger,
    )
}

/**
 * The messages held for a busy agent, in send order. Empty queue renders nothing. CHAT-QUEUE-1: one line per message, each with its
 * actions at the right end: X ([onRemove], "Remove from queue": never delivered) and Force ([onForce], "Send now": into the running
 * turn, which is not stopped). Force is disabled, with [forceWhy] as its tooltip, when [canForce] is false (the harness cannot take a
 * message mid-turn); the message then stays queued. Null actions draw no button (an older daemon's local queue offers X only).
 * [compact] (a phone-width pane) makes each action a 48dp touch target.
 */
@Composable
fun QueuedMessages(
    queue: List<QueuedPrompt>,
    onRemove: ((QueuedPrompt) -> Unit)? = null,
    onForce: ((QueuedPrompt) -> Unit)? = null,
    canForce: Boolean = false,
    forceWhy: String = QUEUE_FORCE_UNSUPPORTED,
    enabled: Boolean = true,
    compact: Boolean = PanelLayout.isCompact(LocalBlockSize.current.width.value),
) {
    if (queue.isEmpty()) return
    val touch = if (compact) QUEUE_TOUCH_PHONE_DP else QUEUE_TOUCH_DESKTOP_DP
    Column(Modifier.fillMaxWidth().testTag(QUEUED_MESSAGES_TAG).padding(bottom = 4.dp)) {
        queue.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth().testTag(QUEUED_ROW_TAG), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (i == 0) "Queued (${queue.size}): " else "") + item.text.lineSequence().first(),
                    color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 8.dp),
                )
                // A message just sent to the daemon has no id yet: its actions wait for the daemon to list it.
                val settled = item.requestId == null
                if (onForce != null) {
                    QueueAction(
                        AhqIcons.Send, if (canForce) QUEUE_FORCE_LABEL else forceWhy, touch,
                        enabled = enabled && canForce && settled && item.id != null, tag = QUEUED_FORCE_TAG,
                    ) { onForce(item) }
                }
                if (onRemove != null) {
                    QueueAction(AhqIcons.Close, QUEUE_REMOVE_LABEL, touch, enabled = enabled && settled, tag = QUEUED_REMOVE_TAG) { onRemove(item) }
                }
            }
        }
    }
}

/** CHAT-QUEUE-1: one small icon action of a queued row; [touch] is its hit box (48dp on a phone), the drawn icon stays small. */
@Composable
private fun QueueAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, touch: Int, enabled: Boolean, tag: String, onClick: () -> Unit,
) {
    IconActionTooltipBox(label, null, Modifier.size(touch.dp).testTag(tag)) { m ->
        Box(m.clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (enabled) Palette.Text else Palette.Muted.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
fun PromptBox(
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
    /** CHAT-COMPACT-1: the composer's plugin slot. Draws inline in the one row (a chip, icon or menu item), never a new row. */
    extras: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    /** CHAT-MINIMIZE-1: the tall editor on/off. CHAT-MINIMIZE-5: its toggle and Minimize are floaters ([ComposerFloaters]), outside the box. */
    expanded: Boolean = false,
) {
    val actionScope = rememberCoroutineScope()
    val host = LocalPlatformHost.current
    val snapshot = controller.state.collectAsState().value
    val live = snapshot.connection.isLive
    // DEMO-MERGE-1 / POLICY-1: each write control is a named action gated by the daemon's policy (and any write policy); the
    // generic gate covers what has no action of its own (a slash command typed into the box).
    val policy = com.superiorsoftllc.infantry.ui.shell.rememberGate("")
    val swap = com.superiorsoftllc.infantry.ui.shell.rememberGate("agents.swap")
    val spawn = com.superiorsoftllc.infantry.ui.shell.rememberGate("agents.spawn", com.superiorsoftllc.infantry.plugin.api.ActionKind.SPAWN)
    val reap = com.superiorsoftllc.infantry.ui.shell.rememberGate("host.processes.reap", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE)
    val remove = com.superiorsoftllc.infantry.ui.shell.rememberGate("agents.delete")
    val kill = com.superiorsoftllc.infantry.ui.shell.rememberGate("agents.kill")
    val restart = com.superiorsoftllc.infantry.ui.shell.rememberGate("agents.restart")
    // GATE-PANES-1: a message to an existing agent is `chat.prompt`; off, Send is dimmed and says why.
    val prompt = com.superiorsoftllc.infantry.ui.shell.rememberGate("chat.prompt")
    val promptLocked = !spawnMode && a != null && !prompt.allowed
    val writeLocked = !policy.allowed
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
        // ANDROID-ATTACH-1: content, not a path; the answer may arrive later (Android's system picker).
        if (live && host.canPickAttachment) host.pickAttachment(ATTACH_FILE_LABEL, false, CHAT_FILE_MAX_BYTES.toLong()) { p ->
            if (p != null) insertPicked(listOf(p))
            // QA-FIX-B: the paperclip (a focusable composer button since CHAT-MINIMIZE-3) took the focus; give it back
            // whatever the pick gave (a tag, a refusal, a cancel), so typing, Backspace and Ctrl+Shift+A go on at the caret.
            runCatching { focus.requestFocus() }
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
                snapshot.promptsFor(a.id), com.superiorsoftllc.infantry.ui.model.PromptCards.offersAllowAlways(snapshot.connection.contract),
                onAnswer = { r -> actionScope.launch { controller.answerPrompt(r) } },
                onDismiss = controller::dismissPrompt,
                enabled = com.superiorsoftllc.infantry.ui.shell.rememberGate("approvals.answer").allowed,
            )
        }
        if (a != null) {
            QueuedMessages(
                queue,
                onRemove = { item -> actionScope.launch { controller.removeQueued(a.id, item) } },
                // Force needs the daemon to hold the queue (contract 1.150.0); an older daemon's local queue offers X only.
                onForce = if (snapshot.connection.servesQueue) { item -> actionScope.launch { controller.forceQueued(a.id, item) } } else null,
                canForce = snapshot.forcesMidTurn(a.id),
                enabled = live && prompt.allowed,
            )
        }
        // CLI-PTY-5: after a restart the history is on screen and nothing runs: one click attaches the CLI, no message sent.
        if (!spawnMode && a != null && com.superiorsoftllc.infantry.ui.showsResumeSession(a.session)) {
            val resumeGate = com.superiorsoftllc.infantry.ui.shell.rememberGate("chat.resume")
            com.superiorsoftllc.infantry.ui.ResumeSessionBar(live && resumeGate.allowed, onResume = { actionScope.launch { controller.resumeSession(a.id) } })
        }
        ChatAttachmentChips(attachments, attachBusy, attachError, onRemoveAttachment)
        SlashSuggestions(slashTitle, slashRows, slashIndex, onPick = { onValue(it) })
        // ATTACH-INLINE-1: each tag's one character is drawn as its pill "📎 name".
        val tags = remember(attachments) { ComposerTags.transformation(attachments, SpanStyle(color = Palette.Accent, background = Palette.Raised)) }
        // CHAT-MINIMIZE-1: one large rounded box; its actions hang below it in two tab groups (tools left, send right).
        var confirmingRemove by remember(a?.id) { mutableStateOf(false) }
        var confirmingRestart by remember(a?.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
        // CHAT-MINIMIZE-2: the mode/status line, ONE small line just above the box.
        Text(
            composerStatusLine(snapshot.toolApproval?.effective, snapshot.agents.size, a?.stateText),
            color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp).testTag(COMPOSER_STATUS_TAG),
        )
        var boxFocused by remember { mutableStateOf(false) }
        // CHAT-MINIMIZE-4: ONE outline for box + groups: the theme's neutral border at rest, the accent (focused or hovered) for the whole unit.
        val boxSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val boxHovered by boxSource.collectIsHoveredAsState()
        // PROMPT-BORDER-1: hover and focus share ONE outline (colour + thickness); the box draws it itself, so the
        // text field's own state-dependent border (1dp unfocused, 2dp focused) can no longer split it from the tabs.
        val outline = composerOutline(boxFocused, boxHovered)
        val ringColor = outline.color
        val borderDp = outline.dp
        val boxShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        Box(Modifier.fillMaxWidth().hoverable(boxSource)) {
            OutlinedTextField(
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent, errorBorderColor = Color.Transparent,
                ),
                value = shown,
                onValueChange = { next -> field = next; if (next.text != value) onValue(next.text) },
                visualTransformation = tags,
                shape = boxShape,
                placeholder = {
                    Text(
                        when {
                            // DEMO-MERGE-1: no new agents while writes are refused; with none selected the box says what to do.
                            !spawn.allowed && (spawnMode || a == null) -> "Pick an agent to message it"
                            spawnMode || a == null -> "Task for a NEW agent. Enter to spawn, Shift+Enter for a new line"
                            isBusy(a) -> "Agent is busy. Enter to queue: sent as its next turn when this one ends"
                            blocker != null -> "Waiting: $blocker"
                            else -> "Message ${a.session.worktreeBranch ?: a.id.take(8)}. Enter to send"
                        },
                        color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.fillMaxWidth().border(borderDp.dp, ringColor, boxShape).focusRequester(focus).onFocusChanged { boxFocused = it.isFocused }.testTag(PROMPT_BOX_TAG).onPreviewKeyEvent { e ->
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
                        e.key == Key.Enter && !e.isShiftPressed && !e.isCtrlPressed -> { if (!promptLocked) onSubmit(); true }
                        // ATTACH-INLINE-1: Ctrl+Shift+A is the paperclip.
                        e.isCtrlPressed && e.isShiftPressed && e.key == Key.A -> { pick(); true }
                        // IMG-2: Ctrl+V with a file or an image on the clipboard attaches it (a tag at the caret);
                        // anything else pastes as text.
                        e.isCtrlPressed && !e.isShiftPressed && e.key == Key.V -> host.clipboardFile()?.let { insertPicked(listOf(it)); true } ?: false
                        else -> false
                    }
                },
                minLines = if (expanded) COMPOSER_EXPANDED_LINES else COMPOSER_MIN_LINES,
                maxLines = if (expanded) COMPOSER_EXPANDED_LINES * 2 else COMPOSER_MAX_LINES,
                textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp),
            )
        }
        // CHAT-MINIMIZE-2: tab-shaped groups hanging from the box's bottom edge, tools left, Stop/Send right.
        // CHAT-MINIMIZE-3/4: solid fill, the box's own outline continued (same colour and thickness), open top.
        // CHAT-MINIMIZE-5: each side meets the box's bottom line in a small concave fillet, so the outline turns down cleanly.
        val tabModifier = Modifier.offset(y = (-borderDp).dp).composerTab(ringColor, borderDp, Palette.Panel)
        val resume = com.superiorsoftllc.infantry.ui.shell.rememberGate("chat.resume")
        val tools = buildList {
            add(ComposerAct(AhqIcons.AttachFile, ATTACH_FILE_LABEL, ATTACH_FILE_SHORTCUT, live && host.canPickAttachment, tag = CHAT_IMAGE_ATTACH_TAG, run = pick))
            if (a != null && !spawnMode) {
                val dormant = com.superiorsoftllc.infantry.ui.showsResumeSession(a.session)
                val canResume = live && resume.allowed && (resumeEnabled(a.session) || dormant)
                val ended = a.session.state in ENDED_STATES
                add(ComposerAct(AhqIcons.SwapHoriz, swap.label(SWITCH_AGENT_LABEL), SWITCH_AGENT_SHORTCUT, swap.allowed, run = onSwitchAgent))
                add(ComposerAct(AhqIcons.PlayArrow, resume.label(if (dormant) com.superiorsoftllc.infantry.ui.RESUME_SESSION_LABEL else "Resume"), "Ctrl+R", canResume, run = {
                    // CLI-PTY-5: a dormant session with an empty box resumes with NO turn; typed text is still sent as the next turn.
                    actionScope.launch {
                        if (dormant && value.isBlank()) controller.resumeSession(a.id)
                        else if (controller.resume(a.id, wire(value).trim()) is CommandResult.Sent) onValue("")
                    }
                }))
                // Ticket a7f33ba2: the old Kill slot reaps this session's background jobs and leaves the session alive.
                add(ComposerAct(AhqIcons.Cancel, reap.label(STOP_BACKGROUND_LABEL), STOP_BACKGROUND_SHORTCUT, live && reap.allowed && backgroundProcessPids(a.session).isNotEmpty(), danger = true, run = {
                    actionScope.launch { controller.stopBackgroundProcesses(a.id) }
                }))
                // SESSION-RM-1: an ended session's trash removes it from the list (after confirm); otherwise End session.
                add(
                    if (ended) ComposerAct(AhqIcons.DeleteForever, remove.label(REMOVE_AGENT_LABEL), null, live && remove.allowed, danger = true, run = { confirmingRemove = true })
                    else ComposerAct(AhqIcons.DeleteForever, kill.label(END_SESSION_LABEL), END_SESSION_SHORTCUT, live && kill.allowed, danger = true, filled = true, run = {
                        actionScope.launch { controller.endSession(a.id) }
                    }),
                )
                add(ComposerAct(AhqIcons.Refresh, restart.label(RESTART_SESSION_LABEL), null, live && restart.allowed, run = { confirmingRestart = true }))
            } else {
                // DEMO-MERGE-1: a new session is a write; the button is dimmed with the reason as its tooltip.
                add(ComposerAct(
                    if (spawnMode) AhqIcons.Close else AhqIcons.PersonAdd,
                    if (spawnMode) "Cancel new agent" else spawn.label(NEW_AGENT_LABEL), NEW_AGENT_SHORTCUT, spawnMode || spawn.allowed, run = onSpawnToggle,
                ))
            }
        }
        val sendCount = if (a != null && !spawnMode) 2 else 1
        // CHAT-MINIMIZE-5: the plugin slot's width, measured, so the overflow rule counts it.
        var extrasPx by remember { mutableStateOf(0) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
          val extrasDp = with(density) { extrasPx.toDp() }.value.toInt()
          val fit = composerToolsThatFit(maxWidth.value.toInt(), sendCount, extrasDp)
          val (inline, overflow) = splitComposerTools(tools.size, fit)
          Row(Modifier.fillMaxWidth().padding(horizontal = COMPOSER_GROUP_INSET_DP.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Row(tabModifier.testTag(COMPOSER_TOOLS_TAB_TAG), verticalAlignment = Alignment.CenterVertically) {
                tools.take(inline).forEach { it.Button() }
                Row(Modifier.onSizeChanged { extrasPx = it.width }, verticalAlignment = Alignment.CenterVertically) { extras() }
                if (overflow > 0) ComposerMore(tools.takeLast(overflow))
            }
            Spacer(Modifier.width(COMPOSER_GROUP_GAP_DP.dp))
            Row(tabModifier.testTag(COMPOSER_SEND_TAB_TAG), verticalAlignment = Alignment.CenterVertically) {
                if (a != null && !spawnMode) InterruptButton(a, live, controller)
                ComposerRound(
                    AhqIcons.Send,
                    when {
                        // DEMO-MERGE-1: while writes are refused a `/` command is not a chat message; Send is dimmed and says why.
                        writeLocked && value.trimStart().startsWith("/") -> "Slash commands: ${policy.reason.orEmpty()}"
                        promptLocked -> prompt.label("Send")
                        spawnMode || a == null -> "Spawn"
                        isBusy(a) -> "Queue"
                        else -> "Send"
                    },
                    onSubmit, shortcut = "Enter", enabled = live && (value.isNotBlank() || attachments.isNotEmpty()) && !attachBusy && blocker == null &&
                        !(!spawn.allowed && (spawnMode || a == null)) && !(writeLocked && value.trimStart().startsWith("/")) && !promptLocked,
                    content = Palette.Accent,
                )
            }
          }
        }
        }
        if (a != null && !spawnMode) {
                if (confirmingRemove) {
                    RemoveAgentDialog(
                        a, snapshot, onDismiss = { confirmingRemove = false },
                        onRemove = { confirmingRemove = false; actionScope.launch { controller.removeSession(a.id) } },
                    )
                }
                if (confirmingRestart) {
                    RestartSessionDialog(
                        a, snapshot, onDismiss = { confirmingRestart = false },
                        onRestart = { confirmingRestart = false; actionScope.launch { controller.restartSession(a.id, confirmed = true) } },
                    )
                }
        }
        // A hint shows as one short line under the row only when there is something to say; it never stacks.
        val busyNow = a != null && a.session.state in setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)
        val hint = when {
            !live -> "Not connected: commands are disabled."
            a != null && !spawnMode && !a.session.resumable && !busyNow -> Controller.resumeBlocker(a.session)
            else -> null
        }
        if (hint != null) Text(hint, color = if (!live) Palette.Danger else Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** CHAT-COMPACT-1: one composer action, drawn as a round button in the tools group or (CHAT-MINIMIZE-5) a row of its "More" menu. */
private class ComposerAct(
    val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val shortcut: String?, val enabled: Boolean,
    val danger: Boolean = false, val filled: Boolean = false, val tag: String? = null, val run: () -> Unit,
) {
    @Composable
    fun Button() {
        ComposerRound(
            icon, label, run, shortcut = shortcut, enabled = enabled,
            container = if (filled) Palette.Danger else Palette.Raised,
            content = when { filled -> Color.White; danger -> Palette.Danger; else -> Palette.Text },
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }

    /** CHAT-MINIMIZE-5: the same action as a row of the "More" menu (icon, then its label; dimmed when disabled). */
    @Composable
    fun MenuItem(close: () -> Unit) {
        val tint = when { !enabled -> Palette.Muted; danger -> Palette.Danger; else -> Palette.Text }
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = tint, fontSize = 12.sp)
                }
            },
            onClick = { close(); run() }, enabled = enabled,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}

/** CHAT-MINIMIZE-5: the last button of a narrow tools group: "More actions" opens the actions that did not fit. */
@Composable
private fun ComposerMore(rest: List<ComposerAct>) {
    var open by remember { mutableStateOf(false) }
    Box {
        ComposerRound(AhqIcons.MoreHoriz, COMPOSER_MORE_LABEL, { open = true }, modifier = Modifier.testTag(COMPOSER_MORE_TAG))
        com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Palette.Raised)) {
            rest.forEach { it.MenuItem { open = false } }
        }
    }
}

/**
 * CHAT-MINIMIZE-5: how many 48dp tool buttons fit in the tools group of a composer [widthDp] wide, next to a send group of
 * [sendCount] buttons and the plugin slot's [extrasDp]. Never negative.
 */
fun composerToolsThatFit(widthDp: Int, sendCount: Int, extrasDp: Int = 0): Int {
    val free = widthDp - 2 * COMPOSER_GROUP_INSET_DP - COMPOSER_GROUP_GAP_DP - sendCount * COMPOSER_TOUCH_DP - extrasDp
    return (free / COMPOSER_TOUCH_DP).coerceAtLeast(0)
}

/**
 * CHAT-MINIMIZE-5: the narrow rule. [count] tools in [fit] slots: all inline when they fit; otherwise the first `fit - 1` stay
 * inline (in their order, so the everyday ones stay put) and the rest go behind the "More" button, which takes the last slot.
 * Answers (inline, overflow); a button is never clipped, squeezed or hidden under another.
 */
fun splitComposerTools(count: Int, fit: Int): Pair<Int, Int> =
    if (count <= fit) count to 0 else (fit - 1).coerceAtLeast(0).let { it to count - it }

/**
 * CHAT-MINIMIZE-5: the hanging group's outline: the box's bottom line continues into a concave fillet ([COMPOSER_JOIN_RADIUS_DP])
 * down each side, then rounded bottom corners ([COMPOSER_TAB_RADIUS_DP]); open at the top. The fill covers the box's line
 * under the opening, so box and group read as ONE outline of [borderDp] in [ring].
 */
private fun Modifier.composerTab(ring: Color, borderDp: Int, fill: Color): Modifier = drawBehind {
    val b = borderDp.dp.toPx()
    val c = b / 2
    val r = COMPOSER_JOIN_RADIUS_DP.dp.toPx()
    val corner = COMPOSER_TAB_RADIUS_DP.dp.toPx()
    val w = size.width
    val h = size.height
    fun circle(cx: Float, cy: Float, rad: Float) = androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(cx, cy), rad)
    fun androidx.compose.ui.graphics.Path.outline() {
        moveTo(c - r, c)
        arcTo(circle(c - r, c + r, r), -90f, 90f, false)
        lineTo(c, h - c - corner)
        arcTo(circle(c + corner, h - c - corner, corner), 180f, -90f, false)
        lineTo(w - c - corner, h - c)
        arcTo(circle(w - c - corner, h - c - corner, corner), 90f, -90f, false)
        lineTo(w - c, c + r)
        arcTo(circle(w - c + r, c + r, r), 180f, 90f, false)
    }
    val body = androidx.compose.ui.graphics.Path().apply { outline(); lineTo(w - c + r, 0f); lineTo(c - r, 0f); close() }
    drawPath(body, fill)
    drawPath(androidx.compose.ui.graphics.Path().apply { outline() }, ring, style = androidx.compose.ui.graphics.drawscope.Stroke(b))
}

/**
 * CHAT-MINIMIZE-5: the composer's floating chips, drawn by the host in the feed's bottom-right float row (beside Activity), never
 * over the typed text: Expand/Collapse and Minimize; minimized, Restore alone.
 */
@Composable
fun ComposerFloaters(minimized: Boolean, expanded: Boolean, onToggleExpand: () -> Unit, onMinimize: () -> Unit, onRestore: () -> Unit) {
    if (minimized) {
        FloatingChip(AhqIcons.Restore, COMPOSER_RESTORE_LABEL, onRestore, Modifier.testTag(COMPOSER_RESTORE_TAG))
        return
    }
    FloatingChip(
        if (expanded) AhqIcons.CloseFullscreen else AhqIcons.OpenInFull, if (expanded) COMPOSER_COLLAPSE_LABEL else COMPOSER_EXPAND_LABEL,
        onToggleExpand, Modifier.testTag(COMPOSER_EXPAND_TAG),
    )
    FloatingChip(AhqIcons.Minimize, COMPOSER_MINIMIZE_LABEL, onMinimize, Modifier.testTag(COMPOSER_MINIMIZE_TAG))
}

/**
 * CHAT-MINIMIZE-3: one round composer button. A 36dp disc in a secondary surface tone (stands out from the accent fill of
 * its group) inside a 48dp hit area; hand cursor and the usual tooltip. [modifier] carries the test tag.
 */
@Composable
fun ComposerRound(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    shortcut: String? = null, enabled: Boolean = true, container: Color = Palette.Raised, content: Color = Palette.Text,
) {
    IconActionTooltipBox(label, shortcut, modifier.size(COMPOSER_TOUCH_DP.dp)) { m ->
        Box(m.clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(COMPOSER_BUTTON_DP.dp).clip(CircleShape).background(if (enabled) container else container.copy(alpha = 0.5f))
                    // A red End button on a red accent needs a ring to be seen.
                    .then(if (container == Palette.Danger) Modifier.border(2.dp, Palette.Raised, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (enabled) content else content.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) }
        }
    }
}

/** CHAT-COMPACT-1: the most lines the input grows to before it scrolls. */
const val COMPOSER_MAX_LINES = 8
const val COMPOSER_MIN_LINES = 3
const val COMPOSER_EXPANDED_LINES = 14
const val COMPOSER_MINIMIZE_LABEL = "Minimize"
const val COMPOSER_EXPAND_LABEL = "Expand"
const val COMPOSER_COLLAPSE_LABEL = "Collapse"
const val COMPOSER_MINIMIZE_TAG = "composer-minimize"
const val COMPOSER_EXPAND_TAG = "composer-expand"
const val COMPOSER_RESTORE_TAG = "composer-restore"
const val COMPOSER_RESTORE_LABEL = "Show composer"
const val COMPOSER_MORE_LABEL = "More actions"
const val COMPOSER_MORE_TAG = "composer-more"
/** CHAT-MINIMIZE-5: the hanging groups' inset from the box's sides (dp): clear of its 16dp corner plus the join fillet. */
const val COMPOSER_GROUP_INSET_DP = 26
/** CHAT-MINIMIZE-5: the gap between the two groups (dp): both fillets plus a run of the box's line. */
const val COMPOSER_GROUP_GAP_DP = 24
/** CHAT-MINIMIZE-5: the concave fillet where a group's side meets the box's bottom line (dp). */
const val COMPOSER_JOIN_RADIUS_DP = 6
/** CHAT-MINIMIZE-5: a group's bottom corners (dp). */
const val COMPOSER_TAB_RADIUS_DP = 12
const val COMPOSER_STATUS_TAG = "composer-status"
const val COMPOSER_TOOLS_TAB_TAG = "composer-tools-tab"
const val COMPOSER_SEND_TAB_TAG = "composer-send-tab"
/** CHAT-MINIMIZE-2: the smallest touch target (dp) of every composer button. */
const val COMPOSER_TOUCH_DP = 48
/** CHAT-MINIMIZE-3: the drawn diameter (dp) of a composer button, and the group border thickness (the text box's focused border). */
const val COMPOSER_BUTTON_DP = 36
const val COMPOSER_BORDER_DP = 2

/** PROMPT-BORDER-1: the composer outline: the same geometry (thickness) for hover and focus, only idle differs. */
data class ComposerOutline(val color: Color, val dp: Int)

fun composerOutline(focused: Boolean, hovered: Boolean): ComposerOutline =
    if (focused || hovered) ComposerOutline(Palette.Accent, COMPOSER_BORDER_DP) else ComposerOutline(Palette.Border, 1)

/** CHAT-MINIMIZE-2: the one status line above the box: permission mode, agent count, session state (unknown parts left out). */
fun composerStatusLine(mode: String?, agents: Int, state: String?): String =
    listOfNotNull(mode?.let { "permissions: $it" }, "$agents agent${if (agents == 1) "" else "s"}", state).joinToString(" · ")


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
            com.superiorsoftllc.infantry.ui.model.AllowedRoles.filter(state.allowedRoles, state.roles).forEach { r ->
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

/** SCRIPT-VIS-1c: test tag on the automation stats panel at the top of a session's chat. */
const val AUTOMATION_STATS_TAG = "automation-stats"

/** SCRIPT-VIS-1c: turns saved and wakes by reason for this session (and the project's totals); shown only once the plugin reported stats. */
@Composable
fun AutomationStatsPanel(item: TimelineItem) {
    val reasons = item.wakeReasons()
    val project = item.meta["projectTurnsSaved"]?.let { " · project: $it saved, ${item.meta["projectWakes"] ?: "0"} wakes" }.orEmpty()
    Text(
        "AUTOMATION · turns saved ${item.meta["turnsSaved"] ?: "0"} · wakes ${item.meta["wakes"] ?: "0"}${if (reasons.isNotBlank()) " ($reasons)" else ""}$project",
        color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, maxLines = 2,
        modifier = Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 14.dp, vertical = 4.dp).testTag(AUTOMATION_STATS_TAG),
    )
}

/** Test tag on the Interrupt control beside the prompt box. */
const val INTERRUPT_BUTTON_TAG = "interrupt-button"

/** SESSION-RM-1: the composer trash button on an ended session (it removes the agent from the list). */
const val REMOVE_AGENT_LABEL = "Remove agent"

/** Test tag on the list of messages waiting for a busy agent. */
const val QUEUED_MESSAGES_TAG = "queued-messages"
/** CHAT-QUEUE-1: one queued message's row, and its two actions. */
const val QUEUED_ROW_TAG = "queued-row"
const val QUEUED_REMOVE_TAG = "queued-remove"
const val QUEUED_FORCE_TAG = "queued-force"
const val QUEUE_REMOVE_LABEL = "Remove from queue"
const val QUEUE_FORCE_LABEL = "Send now (into the running turn)"
const val QUEUE_FORCE_UNSUPPORTED = "Send now is not available: this agent's harness cannot take a message mid-turn; it stays queued"
/** CHAT-QUEUE-1: a queued row's action hit box: 48dp on a phone-width pane (Android touch target), small on a desktop. */
const val QUEUE_TOUCH_PHONE_DP = 48
const val QUEUE_TOUCH_DESKTOP_DP = 24
