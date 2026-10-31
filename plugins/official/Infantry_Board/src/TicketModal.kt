package com.superiorsoftllc.agenthq.plugins.official.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.*
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext

const val TICKET_MODAL_TAG = "ticket-modal"
const val TICKET_MODAL_BACK_TAG = "ticket-modal-back"
const val TICKET_MODAL_COMMENTS_TAG = "ticket-modal-comments"
const val TICKET_OPEN_TAG_PREFIX = "ticket-open-"

/**
 * TICKET-COMMENTS-1: one ticket, full open, over whatever is underneath (the board or a session view). Same width as the
 * configuration modal; Back, Esc and a tap on the scrim close it. It reuses the inline [TicketEditForm].
 */
@Composable
fun TicketModal(ticketId: String, onClose: () -> Unit) {
    val ctx = LocalShellContext.current ?: return
    val deps = BoardDeps(ctx)
    val ticket = ctx.tickets.ticket(ticketId)
    BoardModalFrame(
        onDismiss = onClose,
        panel = Modifier.widthIn(max = CONFIG_DIALOG_MAX_WIDTH).fillMaxWidth(CONFIG_DIALOG_WIDTH_FRACTION).fillMaxHeight(),
        tag = TICKET_MODAL_TAG,
    ) {
        Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onClick = onClose, modifier = Modifier.testTag(TICKET_MODAL_BACK_TAG)) { Text("← Back", fontSize = 13.sp) }
        }
        HorizontalDivider(color = Palette.Border)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ticket == null) {
                Text("This ticket is not on the board (it may have been deleted).", color = Palette.Muted, fontSize = 12.sp)
            } else {
                TicketEditForm(ticket, deps, onDone = onClose)
                RelatedList(ticket)
                // TICKET-COMMENTS-3 fills this area; no comment logic here.
                Column(Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(TICKET_MODAL_COMMENTS_TAG)) {
                    Text("Comments", color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
