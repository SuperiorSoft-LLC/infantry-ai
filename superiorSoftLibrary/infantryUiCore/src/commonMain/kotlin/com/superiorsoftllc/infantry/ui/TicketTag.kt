package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.plugin.BadgeTone
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ticketTagFull
import com.superiorsoftllc.infantry.ui.model.ticketTagLabel

// RUNTIME-21: the agent-row pieces the shell's chat header and the official Infantry_Agents list both draw, in core.

const val AGENT_HEADER_TICKET_TAG = "agent-header-ticket"

/** PROMPT-2: test tag prefix of the "needs you" badge on a session row. */
const val PROMPT_NEEDS_YOU_TAG_PREFIX = "session-needs-you-"

/** UX-ICON-1: the sidebar's New agent link (icon plus label). */
const val SIDEBAR_NEW_AGENT_TAG = "sidebar-new-agent"
/** The New agent action's name and shortcut, shared by the composer icon and the sidebar link. */
const val NEW_AGENT_LABEL = "New agent"
const val NEW_AGENT_SHORTCUT = "Ctrl+N"

/**
 * A worker's ticket tag: short ticket id + truncated title (full title on hover), click opens the board. Nothing for
 * a session with no ticket. The title is resolved from the board; a ticket not (yet) on the board shows its id alone.
 * [onOpen] null = display-only (the sidebar row, where the whole row selects the agent).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketTag(a: AgentView, state: AppState, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
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
            modifier = modifier.then(if (onOpen != null) Modifier.clickable { onOpen(id) } else Modifier).semantics { contentDescription = full },
        )
    }
}

/** AR-7b: the badges modules contribute to session row [a] (display-only; the whole row selects the agent). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionRowBadges(a: AgentView, state: AppState) {
    val factory = LocalTabFactory.current
    factory.sessionBadgesFor(state, a).forEach { b ->
        val color = when (b.tone) {
            BadgeTone.INFO -> Palette.Info; BadgeTone.MUTED -> Palette.Muted; BadgeTone.ACCENT -> Palette.Accent
            BadgeTone.OK -> Palette.Ok; BadgeTone.WARN -> Palette.Warn; BadgeTone.DANGER -> Palette.Danger
        }
        val tip = b.tooltip ?: b.label
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(tip, fontSize = 11.sp) } },
            state = rememberTooltipState(),
        ) {
            Text(
                b.label, color = color, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = (b.testTag?.let { Modifier.testTag(it) } ?: Modifier).semantics { contentDescription = tip },
            )
        }
    }
}

/** PROMPT-2: the "needs you" badge on a session row whose agent waits on a prompt card. */
@Composable
fun NeedsYouBadge(sessionId: String) {
    Spacer(Modifier.width(4.dp))
    Text(
        "needs you", color = Palette.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, Palette.Accent, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp).testTag(PROMPT_NEEDS_YOU_TAG_PREFIX + sessionId),
    )
}
