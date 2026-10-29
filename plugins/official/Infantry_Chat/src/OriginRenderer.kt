package com.superiorsoftllc.agenthq.plugins.official.chat

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.MessageOrigin

/** Test tag on the sender label above a turn that did not come from the owner. */
const val ORIGIN_LABEL_TAG = "origin-label"

/**
 * THE one place a client turns a transcript turn's [MessageOrigin] into pixels. Every `when` over the sealed type is
 * here and exhaustive, so a new origin kind does not compile until it is handled; wasm, Android and desktop all draw
 * a turn through [OriginAvatar] and [originLabel] and never look at the turn's text.
 *
 *  - Owner: the human's disc, no label (as before).
 *  - Agent: that role's portrait ([AgentAvatar]) and the role's name.
 *  - External / System: the sender label only; no portrait (an empty gap keeps the row's indent).
 */
@Composable
fun OriginAvatar(origin: MessageOrigin, size: Int) {
    when (origin) {
        MessageOrigin.Owner -> UserAvatar(size)
        is MessageOrigin.Agent -> AgentAvatar(origin.role, origin.agentId ?: origin.sessionId, size)
        is MessageOrigin.External, is MessageOrigin.System -> Spacer(Modifier.width(size.dp))
    }
}

/** The sender's name as shown above the turn, or null when the owner (shown as today, unlabelled). */
fun originLabel(origin: MessageOrigin): String? = when (origin) {
    MessageOrigin.Owner -> null
    is MessageOrigin.Agent -> origin.role?.takeIf { it.isNotBlank() } ?: "agent ${(origin.agentId ?: origin.sessionId).take(8)}"
    is MessageOrigin.External -> origin.senderLabel
    is MessageOrigin.System -> "system · ${origin.kind}"
}

/** The sender label line, when [origin] has one. */
@Composable
fun OriginLabel(origin: MessageOrigin) {
    val label = originLabel(origin) ?: return
    Text(label, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(ORIGIN_LABEL_TAG))
}
