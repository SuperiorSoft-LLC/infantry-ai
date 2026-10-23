package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * M2-10: every clickable element shows the hand cursor on hover. This file is the one place that applies it:
 * same-named wrappers for `Modifier.clickable` and the Material buttons, menu items, checkboxes and radio buttons. They shadow
 * `androidx.compose.foundation.clickable` / `androidx.compose.material3.*` for this package, because a declaration
 * in the same package wins over the default and star imports, so every call site picks them up unchanged. That
 * only holds while no file imports the originals explicitly (an explicit import wins over the package);
 * `HandCursorGuardTest` fails if one does. [handCursor] is the modifier itself, for anything clickable that is
 * not one of these (e.g. a read-only text field used as a dropdown anchor).
 */

/** The hover cursor a clickable applies, exposed in semantics so tests can read it (the cursor itself is not). */
val HoverCursor = SemanticsPropertyKey<PointerIcon>("HoverCursor")
var SemanticsPropertyReceiver.hoverCursor by HoverCursor

/**
 * The hand cursor on hover, when [enabled] (a disabled control keeps the default arrow). [overrideDescendants]
 * forces it over children that set their own cursor, e.g. a read-only text field used as a dropdown anchor.
 */
fun Modifier.handCursor(enabled: Boolean = true, overrideDescendants: Boolean = false): Modifier =
    if (!enabled) this
    else semantics { hoverCursor = PointerIcon.Hand }.pointerHoverIcon(PointerIcon.Hand, overrideDescendants)

/** [androidx.compose.foundation.clickable] plus the hand cursor while [enabled]. */
fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = handCursor(enabled).foundationClickable(enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)

/** [androidx.compose.foundation.clickable] with an explicit interaction source / indication, plus the hand cursor while [enabled]. */
fun Modifier.clickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = handCursor(enabled).foundationClickable(
    interactionSource = interactionSource, indication = indication, enabled = enabled,
    onClickLabel = onClickLabel, role = role, onClick = onClick,
)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier.handCursor(enabled), enabled = enabled,
    colors = colors, contentPadding = contentPadding, content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick, modifier = modifier.handCursor(enabled), enabled = enabled,
    colors = colors, contentPadding = contentPadding, content = content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(
    onClick = onClick, modifier = modifier.handCursor(enabled), enabled = enabled,
    colors = colors, contentPadding = contentPadding, content = content,
)

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = androidx.compose.material3.DropdownMenuItem(
    text = text, onClick = onClick, modifier = modifier.handCursor(enabled), enabled = enabled,
)

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = androidx.compose.material3.Checkbox(
    checked = checked, onCheckedChange = onCheckedChange,
    modifier = modifier.handCursor(enabled && onCheckedChange != null), enabled = enabled,
)

@Composable
fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = androidx.compose.material3.RadioButton(
    selected = selected, onClick = onClick,
    modifier = modifier.handCursor(enabled && onClick != null), enabled = enabled,
)

/** The platform's grab cursor (an open hand over something that can be dragged): the hand on desktop, Android's grab. */
expect val GrabIcon: PointerIcon

/** The platform's grabbing cursor (while a drag is held): the move cross on desktop, Android's grabbing hand. */
expect val GrabbingIcon: PointerIcon

/**
 * A drag handle's cursor: [GrabIcon] on hover, [GrabbingIcon] while [dragging]; declared in [HoverCursor] like the
 * hand. A disabled handle keeps the default arrow. The board also applies it while a drag is held, so the grabbing
 * cursor follows the pointer off the handle.
 */
fun Modifier.dragHandleCursor(enabled: Boolean = true, dragging: Boolean = false): Modifier {
    if (!enabled) return this
    val icon = if (dragging) GrabbingIcon else GrabIcon
    return semantics { hoverCursor = icon }.pointerHoverIcon(icon, overrideDescendants = true)
}
