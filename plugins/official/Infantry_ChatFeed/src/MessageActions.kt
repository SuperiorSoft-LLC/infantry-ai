// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ChatItem
import com.superiorsoftllc.infantry.ui.model.MessageOrigin
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.ChildSlotRow
import com.superiorsoftllc.infantry.ui.plugin.RowItem
import com.superiorsoftllc.infantry.ui.plugin.slotFilled

/**
 * CHAT-VOICE-SEAMS-1: the per-message actions slot. Infantry_Chat declares it (its module.json `slots`, its contract); the
 * feed draws it on each message row (the owner's and the agent's messages). The argument keys are Chat's `MessageArgs`.
 */
const val MESSAGE_ACTIONS_SLOT = "chat.message.actions"

/** Test tag on the actions row shown over a message. */
const val MESSAGE_ACTIONS_TAG = "chat-message-actions"

/** The row kinds that are messages (a person's or an agent's words), the ones that get actions. */
internal val MESSAGE_KINDS = setOf("user", "text")

/** The window's state and controller for the [MESSAGE_ACTIONS_SLOT] the rows draw (set by [FeedSection]). */
internal class MessageActionsHost(val state: AppState, val controller: Controller)

internal val LocalMessageActionsHost = staticCompositionLocalOf<MessageActionsHost?> { null }

/** The [MESSAGE_ACTIONS_SLOT] arguments of message [row] at [index] in its session's transcript; null when it is no message. */
internal fun messageArgs(row: RowItem, index: Int): Map<String, Any?>? {
    val line = (row.item as? ChatItem.Line)?.line ?: return null
    if (row.kind !in MESSAGE_KINDS) return null
    val (author, role, agentId) = when {
        row.kind == "text" -> Triple("agent", row.agentRole, row.agentId)
        else -> when (val o = line.origin) {
            MessageOrigin.Owner -> Triple("owner", null, null)
            is MessageOrigin.Agent -> Triple("agent", o.role, o.agentId ?: o.sessionId)
            is MessageOrigin.External -> Triple("external", null, null)
            is MessageOrigin.System -> Triple("system", null, null)
        }
    }
    return mapOf(
        "sessionId" to row.agentId, "messageId" to index.toString(), "author" to author,
        "authorRole" to role, "authorAgent" to agentId, "text" to plainMessageText(line.text),
    )
}

/**
 * Draws [content] (one feed row); when it is a message and a plugin fills [MESSAGE_ACTIONS_SLOT], the slot's row floats
 * over its top-right corner while the pointer is on it (desktop, web) or after a long-press (Android, toggles). With no
 * fill nothing wraps the row: the feed draws exactly as before.
 */
@Composable
internal fun MessageWithActions(row: RowItem, index: Int, content: @Composable () -> Unit) {
    val host = LocalMessageActionsHost.current
    val args = if (host != null && slotFilled(MESSAGE_ACTIONS_SLOT)) messageArgs(row, index) else null
    if (host == null || args == null) { content(); return }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var pinned by remember { mutableStateOf(false) }
    Box(Modifier.hoverable(source).pointerInput(Unit) { detectTapGestures(onLongPress = { pinned = !pinned }) }) {
        content()
        if (hovered || pinned) {
            ChildSlotRow(
                MESSAGE_ACTIONS_SLOT, host.state, host.controller,
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
                    .background(Palette.Raised, RoundedCornerShape(6.dp)).padding(2.dp).testTag(MESSAGE_ACTIONS_TAG),
                args = args,
            )
        }
    }
}

private val FENCE = Regex("""(?m)^\s*(```|~~~).*$""")
private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
private val QUOTE = Regex("""(?m)^\s{0,3}>\s?""")
private val BULLET = Regex("""(?m)^(\s*)[-*+]\s+(\[[ xX]]\s+)?""")
private val RULE = Regex("""(?m)^\s*([-*_])(\s*\1){2,}\s*$""")
private val EMPHASIS = Regex("""(\*\*|\*|~~)(\S(?:.*?\S)?)\1""")
private val UNDERSCORE = Regex("""(?<!\w)(__|_)(\S(?:.*?\S)?)\1(?!\w)""")
private val CODE = Regex("""`([^`]*)`""")
private val TABLE_SEP = Regex("""(?m)^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

/** A message as plain text: the markdown marks (fences, emphasis, code, links, headings, quotes, bullets, rules) removed. */
fun plainMessageText(markdown: String): String {
    var t = markdown.replace("\r\n", "\n")
    t = FENCE.replace(t, "")
    t = TABLE_SEP.replace(t, "")
    t = RULE.replace(t, "")
    t = IMAGE.replace(t) { it.groupValues[1] }
    t = LINK.replace(t) { it.groupValues[1] }
    t = HEADING.replace(t, "")
    t = QUOTE.replace(t, "")
    t = BULLET.replace(t) { it.groupValues[1] }
    t = CODE.replace(t) { it.groupValues[1] }
    repeat(2) { t = UNDERSCORE.replace(EMPHASIS.replace(t) { it.groupValues[2] }) { it.groupValues[2] } }
    // A table row "| a | b |" reads as its cells.
    t = t.lines().joinToString("\n") { l -> if (l.trimStart().startsWith("|")) l.trim().trim('|').split('|').joinToString("  ") { it.trim() } else l.trimEnd() }
    return t.replace(Regex("""\n{3,}"""), "\n\n").trim()
}
