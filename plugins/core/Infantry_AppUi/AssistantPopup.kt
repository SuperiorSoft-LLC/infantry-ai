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
import com.superiorsoftllc.agenthq.ui.model.AssistantEntryStyle
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
import com.superiorsoftllc.agenthq.ui.model.AssistantPanelWidth

/** ASSISTANT-1: tags for the top-bar entry and the popup, for UI tests. */
const val TITLE_BAR_ASSISTANT_TAG = "title-bar-assistant"
const val ASSISTANT_POPUP_TAG = "assistant-popup"
const val ASSISTANT_TRANSCRIPT_TAG = "assistant-transcript"
const val ASSISTANT_RESIZE_TAG = "assistant-resize-handle"
const val ASSISTANT_EXPAND_TAG = "assistant-expand"
const val ASSISTANT_CLOSE_TAG = "assistant-close"
const val ASSISTANT_STARTING_TAG = "assistant-starting"
const val ASSISTANT_NAME = "Assistant"

/**
 * ASSISTANT-1: the global Assistant, hosted in a docked popup under the top bar (right side). It is the SAME [ChatPanel] the
 * main window renders, bound to the one global session ([AppState.assistant]): the role chip is the fixed `assistant`, the
 * model is picked with the Switch agent picker (its role row is locked), and nothing here is a second chat implementation.
 * Expanded, or in a compact window (Android), it fills its host as a full-screen sheet.
 * BLOCKS-LAYOUT L11: drawn as [AssistantPopupFrame], [AssistantPopupResize], then a column of [AssistantPopupHeader] and
 * [AssistantPopupChat]; the shell's `infantry.shell.assistant` node draws the same pieces as child blocks.
 */
@Composable
fun AssistantPopup(
    state: AppState,
    controller: Controller,
    panel: AssistantPanelState,
    chat: ChatPanelState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    AssistantPopupFrame(state, controller, panel, compact, modifier) {
        AssistantPopupResize(panel, compact)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            AssistantPopupHeader(panel, compact)
            AssistantPopupChat(state, controller, chat, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * The popup's frame: the row at the dragged width (or full size, expanded or compact), its border, and the first ask that
 * makes the Assistant; [content] goes straight into the row, left to right.
 */
@Composable
fun AssistantPopupFrame(
    state: AppState,
    controller: Controller,
    panel: AssistantPanelState,
    compact: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val assistant = state.assistant
    // The daemon makes the Assistant on the first ask; a later ask (after a reconnect) answers the same session.
    LaunchedEffect(assistant == null, state.connection.isLive) {
        if (assistant == null && state.connection.isLive) controller.openAssistant()
    }
    val full = compact || panel.expanded
    Row(
        (if (full) modifier.fillMaxSize() else modifier.width(panel.width.dp).fillMaxHeight())
            .background(Palette.Bg).border(1.dp, Palette.Border).testTag(ASSISTANT_POPUP_TAG),
        content = content,
    )
}

/** The docked popup's drag edge (drag left = wider); expanded or compact it draws nothing. */
@Composable
fun AssistantPopupResize(panel: AssistantPanelState, compact: Boolean, modifier: Modifier = Modifier) {
    if (!(compact || panel.expanded)) ResizeHandle(ASSISTANT_RESIZE_TAG, modifier) { panel.width = AssistantPanelWidth.dragged(panel.width, it) }
}

/** The title row (name, expand or dock, close) and the divider under it; [modifier] goes on the row. */
@Composable
fun AssistantPopupHeader(panel: AssistantPanelState, compact: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ASSISTANT_NAME, color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!compact) {
            PopupButton(if (panel.expanded) "\u2921" else "\u2922", if (panel.expanded) "Dock the Assistant" else "Expand the Assistant", ASSISTANT_EXPAND_TAG) { panel.expanded = !panel.expanded }
        }
        PopupButton("\u2715", "Close the Assistant", ASSISTANT_CLOSE_TAG) { panel.open = false }
    }
    HorizontalDivider(color = Palette.Border)
}

/** The Assistant's chat (the shared [ChatPanel]), or "Starting the Assistant..." until the daemon has made it; it wears [modifier]. */
@Composable
fun AssistantPopupChat(state: AppState, controller: Controller, chat: ChatPanelState, modifier: Modifier) {
    val assistant = state.assistant
    if (assistant == null) {
        Box(modifier.testTag(ASSISTANT_STARTING_TAG), contentAlignment = Alignment.Center) {
            Text(
                if (state.connection.isLive && !state.connection.hasAssistant) "This server is too old for the Assistant. Update Infantry AI on it."
                else "Starting the Assistant...",
                color = Palette.Muted, fontSize = 12.sp,
            )
        }
    } else {
        ChatPanel(assistant.id, controller, modifier, chat = chat, allowSpawn = false, transcriptTag = ASSISTANT_TRANSCRIPT_TAG)
    }
}

@Composable
private fun PopupButton(glyph: String, label: String, tag: String, onClick: () -> Unit) {
    IconActionTooltipBox(label, modifier = Modifier.size(26.dp).testTag(tag)) { m ->
        Box(
            m.clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, color = Palette.Text, fontSize = 14.sp) }
    }
}

/**
 * ASSISTANT-1: the Assistant's entry in the team bar. A pill (not a team portrait): the avatar, a 1dp accent border and an
 * "Assistant" label (dropped in a compact bar, the border kept). It fits the 22dp portrait row, so the bar never grows.
 */
@Composable
fun AssistantEntry(open: Boolean, needsInput: Boolean, onToggle: () -> Unit) {
    val border = if (open) Palette.Text else Palette.Accent
    val showLabel = AssistantEntryStyle.showLabel(LocalTitleBarCompact.current)
    IconActionTooltipBox(ASSISTANT_NAME, modifier = Modifier.padding(end = 4.dp).testTag(TITLE_BAR_ASSISTANT_TAG)) { m ->
        Row(
            m.clip(CircleShape).border(1.dp, border, CircleShape).padding(start = 1.dp, end = if (showLabel) 8.dp else 1.dp)
                .background(if (needsInput) Palette.Accent.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = if (open) "Close the Assistant" else "Open the Assistant", onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar("assistant", ASSISTANT_NAME, 22)
            if (showLabel) {
                Spacer(Modifier.width(5.dp))
                Text(ASSISTANT_NAME, color = Palette.Text, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}
