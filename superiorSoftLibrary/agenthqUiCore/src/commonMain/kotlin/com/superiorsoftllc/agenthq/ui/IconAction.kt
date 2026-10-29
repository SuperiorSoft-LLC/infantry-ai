package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * UX-ICON-1: the one icon button of the window. A Material icon in place of a text label, to save room, with a
 * tooltip that names the action (and its keyboard shortcut) on hover or keyboard focus.
 *
 * Screen readers get [label] as the contentDescription (the action alone, without the shortcut). When [text] is set
 * the button also shows that label beside the icon (e.g. the sidebar's New agent, which has the room), and the
 * visible text is what a screen reader reads instead, so the same action never carries its name twice.
 *
 * It is built on the same-package [Button] / [OutlinedButton] / [TextButton] wrappers, so it keeps the hand cursor
 * (M2-10) and looks enabled or disabled exactly like the text button it replaces.
 */
enum class IconActionStyle { FILLED, OUTLINED, TEXT }

/** What an icon action's tooltip says: the action, then its shortcut in brackets when it has one. */
fun iconActionTooltip(label: String, shortcut: String? = null): String =
    if (shortcut.isNullOrBlank()) label else "$label ($shortcut)"

/** The tooltip text of an icon action, in semantics so tests can read every one without hovering it. */
val IconActionTooltip = SemanticsPropertyKey<String>("IconActionTooltip")
var SemanticsPropertyReceiver.iconActionTooltip by IconActionTooltip

/** Test tag on the text inside a shown icon-action tooltip. */
const val ICON_ACTION_TOOLTIP_TAG = "icon-action-tooltip"

private val IconOnlyPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
private val IconAndTextPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    enabled: Boolean = true,
    style: IconActionStyle = IconActionStyle.OUTLINED,
    colors: ButtonColors? = null,
    text: String? = null,
    textColor: Color = Color.Unspecified,
    fontSize: TextUnit = 12.sp,
) {
    IconActionTooltipBox(label, shortcut, modifier, describe = text == null) { buttonModifier ->
        val content: @Composable RowScope.() -> Unit = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            if (text != null) {
                Spacer(Modifier.width(6.dp))
                Text(text, color = textColor, fontSize = fontSize)
            }
        }
        val padding = if (text == null) IconOnlyPadding else IconAndTextPadding
        when (style) {
            IconActionStyle.FILLED -> Button(onClick, buttonModifier, enabled, colors ?: ButtonDefaults.buttonColors(), padding, content)
            IconActionStyle.OUTLINED -> OutlinedButton(onClick, buttonModifier, enabled, colors ?: ButtonDefaults.outlinedButtonColors(), padding, content)
            IconActionStyle.TEXT -> TextButton(onClick, buttonModifier, enabled, colors ?: ButtonDefaults.textButtonColors(), padding, content)
        }
    }
}

/**
 * DEMO-MERGE-1: when the nearest [com.superiorsoftllc.agenthq.ui.shell.WritePolicy] refuses writes and gives a reason,
 * [content]'s (disabled) control says that reason on hover; otherwise it is [content] alone, unchanged.
 */
@Composable
fun WritePolicyTooltip(content: @Composable (Modifier) -> Unit) {
    // POLICY-1: inside [com.superiorsoftllc.agenthq.ui.shell.Gated] the gate's own decision; elsewhere the generic write's
    // (the daemon's policy and the nearest write policy), so a control that only checks `canWrite` says the policy's reason.
    val gate = com.superiorsoftllc.agenthq.ui.shell.LocalActionGate.current ?: com.superiorsoftllc.agenthq.ui.shell.rememberGate("")
    val reason = gate.reason?.takeIf { !gate.enabled && gate.reason.isNotBlank() }
    if (reason != null) IconActionTooltipBox(reason, describe = false, content = content)
    else content(Modifier)
}

/**
 * UX-POLISH-1: [IconAction]'s tooltip, for an icon control that is not a Material button (e.g. the title bar's square
 * New project box). Names the action on hover or keyboard focus, puts [label] in the contentDescription when
 * [describe] is set, and exposes the tooltip text as [IconActionTooltip]. [content] gets the modifier to put on the
 * control itself (it already carries [modifier]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconActionTooltipBox(
    label: String,
    shortcut: String? = null,
    modifier: Modifier = Modifier,
    describe: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    require(label.isNotBlank()) { "an icon action needs a name for its tooltip and screen readers" }
    val tooltip = iconActionTooltip(label, shortcut)
    // Persistent: the tooltip stays while the pointer (or focus) is on the button and closes on exit, as desktop
    // tooltips do; a plain M3 tooltip would vanish after 1.5 s mid-hover.
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip, fontSize = 11.sp, modifier = Modifier.testTag(ICON_ACTION_TOOLTIP_TAG)) } },
        state = tooltipState,
    ) {
        val buttonModifier = modifier
            .semantics {
                if (describe) contentDescription = label
                iconActionTooltip = tooltip
            }
            // TooltipBox opens on hover only while the button is enabled; a disabled icon must still say what it is, so
            // the pointer's enter/exit is watched here too (Initial pass: before the button sees it, consuming nothing).
            .pointerInput(tooltipState) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent(PointerEventPass.Initial).type) {
                            PointerEventType.Enter -> scope.launch { tooltipState.show() }
                            PointerEventType.Exit -> if (!focused) tooltipState.dismiss()
                        }
                    }
                }
            }
            // Keyboard focus (Tab onto the button) shows the same tooltip. Only a real change
            // of focus acts: an unfocused button is re-reported unfocused (e.g. when it is enabled), and dismissing on
            // that would close a tooltip the mouse just opened.
            .onFocusChanged { f ->
                if (f.hasFocus == focused) return@onFocusChanged
                focused = f.hasFocus
                if (focused) scope.launch { tooltipState.show() } else tooltipState.dismiss()
            }
        content(buttonModifier)
    }
}
