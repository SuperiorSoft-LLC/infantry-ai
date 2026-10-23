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
) {
    // The empty areas move the window (desktop drag) and a double-click there toggles maximize, like a native bar.
    val drag = if (chrome == null) Modifier else chrome.dragArea.pointerInput(chrome) {
        detectTapGestures(onDoubleTap = { chrome.toggleMaximize() })
    }
    // MOBILE-TITLEBAR-1: below PanelLayout.COMPACT_BELOW_DP the bar keeps the left toggle, the logo + name and the
    // connection chip (state only); the project picker, the attention entry, the side-panel toggle and the connection URL
    // move into an overflow menu, and the avatar strip gives way to a plain drag area. At or above it the bar is unchanged.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = PanelLayout.isCompact(maxWidth.value)
    Row(
        Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).background(Palette.Panel).testTag(TITLE_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onTogglePanel != null) {
            Spacer(Modifier.width(6.dp))
            PanelToggle(PanelSide.LEFT, state.panels.leftShown) { onTogglePanel(PanelSide.LEFT) }
        }
        Row(drag.fillMaxHeight().padding(start = if (onTogglePanel != null) 6.dp else 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo()
            Spacer(Modifier.width(8.dp))
            Text("Infantry AI", color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        if (compact) {
            // A narrow desktop window still moves by its bar: the free space is the drag area.
            Spacer(drag.weight(1f).fillMaxHeight().testTag(TITLE_BAR_DRAG_TAG))
            TitleBarOverflow(state, onConnect, onAttention, projectSlot, onTogglePanel)
            Spacer(Modifier.width(6.dp))
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
            }
            // Contract 1.9.0: open attention is a durable signal from the daemon, so it is always in view.
            if (state.attentionOpenCount > 0) {
                Chip("${state.attentionOpenCount} need attention", Palette.Accent, Modifier.clickable(onClick = onAttention).testTag(TITLE_BAR_ATTENTION_CHIP_TAG))
                Spacer(Modifier.width(10.dp))
            }
            if (onTogglePanel != null) {
                PanelToggle(PanelSide.RIGHT, state.panels.rightShown) { onTogglePanel(PanelSide.RIGHT) }
                Spacer(Modifier.width(8.dp))
            }
        }
        ConnChip(state, onConnect, showUrl = !compact)
        if (chrome == null) {
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(8.dp))
            WindowButton(TITLE_BAR_MINIMIZE_TAG, "Minimize", Palette.Raised, chrome::minimize) { MinimizeGlyph() }
            val maximized = chrome.isMaximized
            WindowButton(TITLE_BAR_MAXIMIZE_TAG, if (maximized) "Restore" else "Maximize", Palette.Raised, chrome::toggleMaximize) {
                if (maximized) RestoreGlyph() else MaximizeGlyph()
            }
            WindowButton(TITLE_BAR_CLOSE_TAG, "Close", CloseHover, chrome::close) { CloseGlyph() }
        }
    }
    }
    HorizontalDivider(color = Palette.Border)
}

val TITLE_BAR_HEIGHT = 36.dp

/**
 * MOBILE-TITLEBAR-1: the compact bar's overflow button and its menu: the project picker slot, the attention entry (while
 * any is open), the side-panel toggle, and the connection URL the chip no longer has room for (a tap opens the connection).
 */
@Composable
private fun TitleBarOverflow(
    state: AppState,
    onConnect: () -> Unit,
    onAttention: () -> Unit,
    projectSlot: @Composable () -> Unit,
    onTogglePanel: ((PanelSide) -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconActionTooltipBox("More", modifier = Modifier.size(28.dp).testTag(TITLE_BAR_OVERFLOW_TAG)) { m ->
            Box(
                m.clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button, onClickLabel = "More", onClick = { open = true }),
                contentAlignment = Alignment.Center,
            ) { Text("\u22EF", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
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
            if (onTogglePanel != null) {
                val shown = state.panels.rightShown
                DropdownMenuItem(
                    text = { Text(if (shown) "Hide side panel" else "Show side panel", color = Palette.Text, fontSize = 12.sp) },
                    onClick = { open = false; onTogglePanel(PanelSide.RIGHT) },
                    modifier = Modifier.testTag(TITLE_BAR_TOGGLE_RIGHT_TAG),
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

/** The Infantry AI mark: an amber tile with a dark "A". */
@Composable
private fun Logo() {
    Box(
        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Accent).testTag(TITLE_BAR_LOGO_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = Color(0xFF1A1200), fontWeight = FontWeight.Black, fontSize = 12.sp, lineHeight = 12.sp)
    }
}

/**
 * PANEL-TOGGLE-1: one side panel's show/hide button. Built like the New project box (an icon in the
 * [IconActionTooltipBox], clickable through the hand-cursor wrapper) so it fits the 36dp bar; full contrast while its
 * panel is shown, muted while hidden.
 */
@Composable
private fun PanelToggle(side: PanelSide, shown: Boolean, onClick: () -> Unit) {
    val left = side == PanelSide.LEFT
    val label = (if (shown) "Hide " else "Show ") + (if (left) "agent list" else "side panel")
    val tag = if (left) TITLE_BAR_TOGGLE_LEFT_TAG else TITLE_BAR_TOGGLE_RIGHT_TAG
    IconActionTooltipBox(label, modifier = Modifier.size(28.dp).testTag(tag)) { m ->
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
private fun ConnChip(state: AppState, onClick: () -> Unit, showUrl: Boolean = true) {
    val c = state.connection
    val (label, color) = when (c.state) {
        ConnState.CONNECTED -> "connected" to Palette.Ok
        ConnState.CONNECTING -> "connecting..." to Palette.Info
        ConnState.RECONNECTING -> "reconnecting (try ${c.attempt})" to Palette.Accent
        ConnState.REFUSED -> "refused" to Palette.Danger
        ConnState.FAILED -> "failed" to Palette.Danger
        ConnState.IDLE -> "not connected" to Palette.Muted
    }
    Row(Modifier.clickable(onClick = onClick).testTag(TITLE_BAR_CONNECTION_CHIP_TAG).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (showUrl) "$label  ${c.url}" else label, color = color, fontSize = 11.sp, maxLines = 1)
    }
}
