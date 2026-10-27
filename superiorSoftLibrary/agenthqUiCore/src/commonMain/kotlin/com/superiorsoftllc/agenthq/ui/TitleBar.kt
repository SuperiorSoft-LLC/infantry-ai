package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.Canvas
import com.superiorsoftllc.agenthq.ui.model.PanelLayout
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AgentScope
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.PanelSide
import com.superiorsoftllc.agenthq.ui.model.needsInput
import com.superiorsoftllc.agenthq.ui.model.visibleAgents

/**
 * M2-6: what the title bar needs from the window it lives in. A desktop shell with an undecorated window
 * provides one (minimize / maximize-restore / close, and the drag behaviour of the empty bar); a platform
 * without a window (Android) provides none, and the bar then shows no window controls.
 */
interface WindowChrome {
    /** Read in composition, so an implementation backed by snapshot state swaps the maximize/restore glyph. */
    val isMaximized: Boolean
    fun minimize()
    fun toggleMaximize()
    fun close()

    /** Applied to the bar's empty areas: on desktop, pressing and dragging there moves the window. */
    val dragArea: Modifier get() = Modifier
}

/** The window chrome of the current platform window; null where there is no window to control. */
val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/** BUG-M2-15: one top-bar avatar, suffixed with the session id. */
const val TITLE_BAR_AVATAR_TAG_PREFIX = "title-bar-avatar-"
const val TITLE_BAR_TAG = "title-bar"
const val TITLE_BAR_LOGO_TAG = "title-bar-logo"
const val TITLE_BAR_PROJECT_SLOT_TAG = "title-bar-project-slot"
const val TITLE_BAR_DRAG_TAG = "title-bar-drag"
const val TITLE_BAR_MINIMIZE_TAG = "title-bar-minimize"
const val TITLE_BAR_MAXIMIZE_TAG = "title-bar-maximize"
const val TITLE_BAR_CLOSE_TAG = "title-bar-close"
const val TITLE_BAR_ATTENTION_CHIP_TAG = "title-bar-attention-chip"
const val TITLE_BAR_CONNECTION_CHIP_TAG = "title-bar-connection-chip"
/** PANEL-TOGGLE-1: the agent list (left sidebar) toggle, at the bar's left edge. */
const val TITLE_BAR_TOGGLE_LEFT_TAG = "title-bar-toggle-left"
/** PANEL-TOGGLE-1: the side panel (right panel) toggle, before the connection chip. */
const val TITLE_BAR_TOGGLE_RIGHT_TAG = "title-bar-toggle-right"
/** MOBILE-TITLEBAR-1: the compact bar's overflow button, its menu, and the menu's attention and connection entries. */
const val TITLE_BAR_OVERFLOW_TAG = "title-bar-overflow"
const val TITLE_BAR_OVERFLOW_MENU_TAG = "title-bar-overflow-menu"
const val TITLE_BAR_OVERFLOW_ATTENTION_TAG = "title-bar-overflow-attention"
const val TITLE_BAR_OVERFLOW_CONNECTION_TAG = "title-bar-overflow-connection"

/**
 * The app's single top bar. On desktop it is also the window's title bar: logo + name on the left, the slot
 * for the project picker (M2-7), the agent strip, the attention / connection status, and the window controls.
 */
@Composable
fun TitleBar(
    state: AppState,
    agentScope: AgentScope,
    onConnect: () -> Unit,
    onAttention: () -> Unit,
    chrome: WindowChrome? = LocalWindowChrome.current,
    projectSlot: @Composable () -> Unit = {},
    /** PANEL-TOGGLE-1: hides/shows one side panel; null = no toggle button (a bar shown on its own, e.g. in a test). */
    onTogglePanel: ((PanelSide) -> Unit)? = null,
    /** SWITCH-1: the server dropdown, shown before the connection chip; empty = none (a bar shown on its own). */
    serverSlot: @Composable () -> Unit = {},
    /** ASSISTANT-1: the Assistant's portrait, the LAST member of the team strip (and a button before the overflow in a compact bar); empty = none. */
    assistantSlot: @Composable () -> Unit = {},
) {
    // BLOCKS-LAYOUT L6: the same frame and items the shell header (`infantry.shell.header`) composes as child blocks.
    TitleBarFrame {
        TitleBarLead(state, agentScope, onConnect, onAttention, chrome, projectSlot, onTogglePanel, assistantSlot, serverSlot)
        TitleBarConnection(state, onConnect)
        TitleBarWindowControls(chrome)
    }
}

/**
 * BLOCKS-LAYOUT L6: the bar's frame: the 36dp Panel row (its items in order, left to right) and the divider under it.
 * Below PanelLayout.COMPACT_BELOW_DP it provides [LocalTitleBarCompact] = true; the items read it.
 */
@Composable
fun TitleBarFrame(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val compact = PanelLayout.isCompact(maxWidth.value)
    androidx.compose.runtime.CompositionLocalProvider(LocalTitleBarCompact provides compact) {
    Row(
        Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).background(Palette.Panel).testTag(TITLE_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    }
    }
    HorizontalDivider(color = Palette.Border)
}

/** The empty areas move the window (desktop drag) and a double-click there toggles maximize, like a native bar. */
private fun dragModifier(chrome: WindowChrome?): Modifier = if (chrome == null) Modifier else chrome.dragArea.pointerInput(chrome) {
    detectTapGestures(onDoubleTap = { chrome.toggleMaximize() })
}

/**
 * BLOCKS-LAYOUT L6: the bar's left and middle (L7 splits it): the left toggle, the logo + name, and the project slot +
 * avatar strip (wide) or the drag area + Assistant + overflow (compact). Its items go straight into the bar's row, so
 * the row measures them in the same order as the items after them (an overflowing 360dp bar clips at its right end).
 */
@Composable
fun RowScope.TitleBarLead(
    state: AppState,
    agentScope: AgentScope,
    onConnect: () -> Unit,
    onAttention: () -> Unit,
    chrome: WindowChrome?,
    projectSlot: @Composable () -> Unit,
    onTogglePanel: ((PanelSide) -> Unit)?,
    assistantSlot: @Composable () -> Unit,
    serverSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag = dragModifier(chrome)
    val compact = LocalTitleBarCompact.current
    // MOBILE-TITLEBAR-1: below PanelLayout.COMPACT_BELOW_DP the bar keeps the left toggle, the logo + name and the
    // connection chip (state only); the project picker, the attention entry, the side-panel toggle and the connection URL
    // move into an overflow menu, and the avatar strip gives way to a plain drag area. At or above it the bar is unchanged.
    if (onTogglePanel != null) {
        Spacer(modifier.width(6.dp))
        PanelToggle(PanelSide.LEFT, state.panels.leftShown, compact) { onTogglePanel(PanelSide.LEFT) }
    }
    Row((if (onTogglePanel != null) drag else modifier.then(drag)).fillMaxHeight().padding(start = if (onTogglePanel != null) 6.dp else 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo()
        Spacer(Modifier.width(8.dp))
        Text("Infantry AI", color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    if (compact) {
        // A narrow desktop window still moves by its bar: the free space is the drag area.
        Spacer(drag.weight(1f).fillMaxHeight().testTag(TITLE_BAR_DRAG_TAG))
        assistantSlot()
        TitleBarOverflow(state, onConnect, onAttention, projectSlot)
    } else {
        // M2-7: the project drop-down and the "+" New Project box ([ProjectPicker]).
        Box(Modifier.fillMaxHeight().testTag(TITLE_BAR_PROJECT_SLOT_TAG), contentAlignment = Alignment.Center) { projectSlot() }
        Row(
            drag.weight(1f).fillMaxHeight().padding(start = 10.dp).testTag(TITLE_BAR_DRAG_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The strip of agent avatars: one per session, the selected one outlined, needs-input ones in amber.
            visibleAgents(state, "", null, agentScope = agentScope).take(14).forEach { a ->
                val ring = if (a.id == state.selectedId) Palette.Text else if (needsInput(a, state.waiting, state.openPrompts, state.openQuestionSessions)) Palette.Accent else Color.Transparent
                Box(Modifier.padding(end = 4.dp).clip(CircleShape).border(2.dp, ring, CircleShape).padding(1.dp).testTag(TITLE_BAR_AVATAR_TAG_PREFIX + a.id)) {
                    AgentAvatar(a, state, 22)
                }
            }
            // ASSISTANT-1: the Assistant is the LAST portrait of the team.
            assistantSlot()
        }
    }
    TitleBarAttention(state, onAttention)
    if (onTogglePanel != null) TitleBarRightToggle(state) { onTogglePanel(PanelSide.RIGHT) } else if (compact) Spacer(Modifier.width(4.dp))
    serverSlot()
}

/** Contract 1.9.0: open attention is a durable signal from the daemon, so it is always in view (wide; compact has it in the overflow). Owns its 10dp gap. */
@Composable
fun TitleBarAttention(state: AppState, onAttention: () -> Unit, modifier: Modifier = Modifier) {
    if (!LocalTitleBarCompact.current && state.attentionOpenCount > 0) {
        Chip("${state.attentionOpenCount} need attention", Palette.Accent, modifier.clickable(onClick = onAttention).testTag(TITLE_BAR_ATTENTION_CHIP_TAG))
        Spacer(Modifier.width(10.dp))
    }
}

/** PANEL-TOGGLE-1: the side panel toggle; it owns its gap (8dp wide, 4dp compact, where it stays in view: the owner's ask). */
@Composable
fun TitleBarRightToggle(state: AppState, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val compact = LocalTitleBarCompact.current
    PanelToggle(PanelSide.RIGHT, state.panels.rightShown, compact, modifier, onToggle)
    Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
}

/** The connection chip: state + URL (wide) or state only (compact). */
@Composable
fun TitleBarConnection(state: AppState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    ConnChip(state, onConnect, showUrl = !LocalTitleBarCompact.current, modifier)
}

/** M2-6: minimize / maximize-restore / close where there is a window (with their 8dp lead), else the bar's 12dp end. */
@Composable
fun TitleBarWindowControls(chrome: WindowChrome?, modifier: Modifier = Modifier) {
    if (chrome == null) {
        Spacer(modifier.width(12.dp))
    } else {
        Spacer(modifier.width(8.dp))
        WindowButton(TITLE_BAR_MINIMIZE_TAG, "Minimize", Palette.Raised, chrome::minimize) { MinimizeGlyph() }
        val maximized = chrome.isMaximized
        WindowButton(TITLE_BAR_MAXIMIZE_TAG, if (maximized) "Restore" else "Maximize", Palette.Raised, chrome::toggleMaximize) {
            if (maximized) RestoreGlyph() else MaximizeGlyph()
        }
        WindowButton(TITLE_BAR_CLOSE_TAG, "Close", CloseHover, chrome::close) { CloseGlyph() }
    }
}

val TITLE_BAR_HEIGHT = 36.dp

/** True while the title bar is in its compact (narrow) layout; read by the Assistant pill to drop its label. */
val LocalTitleBarCompact = androidx.compose.runtime.compositionLocalOf { false }

/**
 * MOBILE-TITLEBAR-1: the compact bar's overflow button and its menu: the project picker slot, the attention entry (while
 * any is open), and the connection URL the chip no longer has room for (a tap opens the connection).
 */
@Composable
private fun TitleBarOverflow(
    state: AppState,
    onConnect: () -> Unit,
    onAttention: () -> Unit,
    projectSlot: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconActionTooltipBox("More", modifier = Modifier.size(28.dp).testTag(TITLE_BAR_OVERFLOW_TAG)) { m ->
            Box(
                m.clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button, onClickLabel = "More", onClick = { open = true }),
                contentAlignment = Alignment.Center,
            ) { Icon(AhqIcons.MoreHoriz, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(18.dp)) }
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(Palette.Raised).testTag(TITLE_BAR_OVERFLOW_MENU_TAG),
        ) {
            // M2-7: the same project picker the wide bar shows inline.
            Box(
                Modifier.padding(horizontal = 8.dp).height(TITLE_BAR_HEIGHT).testTag(TITLE_BAR_PROJECT_SLOT_TAG),
                contentAlignment = Alignment.CenterStart,
            ) { projectSlot() }
            if (state.attentionOpenCount > 0) {
                DropdownMenuItem(
                    text = { Text("${state.attentionOpenCount} need attention", color = Palette.Accent, fontSize = 12.sp) },
                    onClick = { open = false; onAttention() },
                    modifier = Modifier.testTag(TITLE_BAR_OVERFLOW_ATTENTION_TAG),
                )
            }
            HorizontalDivider(color = Palette.Border)
            DropdownMenuItem(
                text = { Text(state.connection.url.ifBlank { "Connect..." }, color = Palette.Muted, fontSize = 11.sp) },
                onClick = { open = false; onConnect() },
                modifier = Modifier.testTag(TITLE_BAR_OVERFLOW_CONNECTION_TAG),
            )
        }
    }
}
private val CloseHover = Color(0xFFC42B1C)

/** The Infantry AI emblem. */
@Composable
private fun Logo() {
    BrandEmblem(22.dp, Modifier.testTag(TITLE_BAR_LOGO_TAG))
}

/**
 * PANEL-TOGGLE-1: one side panel's show/hide button. Built like the New project box (an icon in the
 * [IconActionTooltipBox], clickable through the hand-cursor wrapper) so it fits the 36dp bar; full contrast while its
 * panel is shown, muted while hidden.
 */
@Composable
private fun PanelToggle(side: PanelSide, shown: Boolean, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val left = side == PanelSide.LEFT
    val label = (if (shown) "Hide " else "Show ") + (if (left) "agent list" else "side panel")
    val tag = if (left) TITLE_BAR_TOGGLE_LEFT_TAG else TITLE_BAR_TOGGLE_RIGHT_TAG
    IconActionTooltipBox(label, modifier = modifier.then(if (compact) Modifier.size(width = 40.dp, height = TITLE_BAR_HEIGHT) else Modifier.size(28.dp)).testTag(tag)) { m ->
        Box(
            m.clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (left) AhqIcons.PanelLeft else AhqIcons.PanelRight, contentDescription = null,
                tint = if (shown) Palette.Text else Palette.Muted, modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A 46x36 caption button, the size Windows uses; the glyph turns white on hover. */
@Composable
private fun WindowButton(tag: String, label: String, hover: Color, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.width(46.dp).fillMaxHeight()
            .background(if (hovered) hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalGlyphColor provides if (hovered) Color.White else Palette.Text) { glyph() }
    }
}

private val LocalGlyphColor = staticCompositionLocalOf { Palette.Text }

@Composable
private fun MinimizeGlyph() {
    val c = LocalGlyphColor.current
    Canvas(Modifier.size(10.dp)) {
        drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun MaximizeGlyph() {
    val c = LocalGlyphColor.current
    Canvas(Modifier.size(10.dp)) {
        val w = 1.dp.toPx()
        drawRect(c, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
    }
}

@Composable
private fun RestoreGlyph() {
    val c = LocalGlyphColor.current
    Canvas(Modifier.size(10.dp)) {
        val w = 1.dp.toPx()
        val o = 2.dp.toPx()
        // Back window: only its top and right edges show behind the front one.
        drawLine(c, Offset(o, w / 2), Offset(size.width - w / 2, w / 2), strokeWidth = w)
        drawLine(c, Offset(size.width - w / 2, w / 2), Offset(size.width - w / 2, size.height - o), strokeWidth = w)
        drawRect(c, Offset(w / 2, o + w / 2), Size(size.width - o - w, size.height - o - w), style = Stroke(w))
    }
}

@Composable
private fun CloseGlyph() {
    val c = LocalGlyphColor.current
    Canvas(Modifier.size(10.dp)) {
        val w = 1.dp.toPx()
        drawLine(c, Offset.Zero, Offset(size.width, size.height), strokeWidth = w)
        drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w)
    }
}

@Composable
private fun ConnChip(state: AppState, onClick: () -> Unit, showUrl: Boolean = true, modifier: Modifier = Modifier) {
    val c = state.connection
    val now = rememberTickingNow(c.state == ConnState.RECONNECTING)
    val (label, color) = when (c.state) {
        ConnState.CONNECTED -> "connected" to Palette.Ok
        ConnState.CONNECTING -> "connecting..." to Palette.Info
        ConnState.RECONNECTING -> "reconnecting (try ${c.attempt}${retryCountdownSeconds(c, now)?.let { ", ${it}s" } ?: ""})" to Palette.Accent
        ConnState.REFUSED -> "refused" to Palette.Danger
        ConnState.FAILED -> "failed" to Palette.Danger
        ConnState.IDLE -> "not connected" to Palette.Muted
    }
    Row(modifier.clickable(onClick = onClick).testTag(TITLE_BAR_CONNECTION_CHIP_TAG).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (showUrl) "$label  ${c.url}" else label, color = color, fontSize = 11.sp, maxLines = 1)
    }
}
