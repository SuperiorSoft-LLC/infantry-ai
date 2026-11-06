// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqIcons
import com.superiorsoftllc.infantry.ui.ChatImageLines
import com.superiorsoftllc.infantry.ui.Mono
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.model.MessageOrigin
import com.superiorsoftllc.infantry.ui.model.PendingSend
import com.superiorsoftllc.infantry.ui.model.PendingSends
import com.superiorsoftllc.infantry.ui.model.SendState

/** CHAT-SENDING-1: test tag on a sent message's bubble while the daemon's echo is due. */
const val PENDING_SEND_TAG = "pending-send"

/** CHAT-SENDING-1: test tag on the bubble's small state marker ("sending...", "sent", "not sent - retry"). */
const val PENDING_SEND_STATE_TAG = "pending-send-state"

/** CHAT-SENDING-1: test tag on the "not sent - retry" action, and on the discard X next to it. */
const val PENDING_SEND_RETRY_TAG = "pending-send-retry"
const val PENDING_SEND_DISCARD_TAG = "pending-send-discard"

/** The tooltip-free label of the discard action (read by screen readers). */
const val PENDING_SEND_DISCARD_LABEL = "Discard this message"

/**
 * CHAT-SENDING-1: a message the human sent, drawn like their own chat card the moment Send is pressed, with a small state marker
 * where the card's time goes: a tiny spinner and "sending..." while it waits, a check and "sent" once the daemon took it, and
 * "not sent - retry" (tap to resend, X to discard) when it failed. The daemon's `turn` echo replaces the whole row. [compact]
 * (a phone-width pane) makes the actions 48dp touch targets.
 */
@Composable
fun PendingSendRow(p: PendingSend, onRetry: (String) -> Unit, onDiscard: (String) -> Unit, compact: Boolean = false) {
    val origin = MessageOrigin.Owner
    SpeakerRow({ OriginAvatar(origin, CHAT_AVATAR_SIZE) }) {
        Column(Modifier.testTag(PENDING_SEND_TAG)) {
            OriginLabel(origin)
            CommsCard(commsCardStyle(origin), p.at, trailing = { SendMarker(p, onRetry, onDiscard, compact) }) {
                // The text stays readable in every state: a failed send never loses what was typed. CHAT-DUP-1: drawn like the
                // turn it becomes ([UserRow]): `[image: ...]` lines show as thumbnails, never as raw paths.
                val (said, images) = ChatImageLines.split(p.text)
                if (said.isNotBlank() || images.isEmpty()) {
                    Text(said, color = if (p.state == SendState.FAILED) Palette.Muted else Palette.Accent, fontFamily = Mono, fontSize = 12.sp)
                }
                ChatSentImages(images)
            }
        }
    }
}

@Composable
private fun SendMarker(p: PendingSend, onRetry: (String) -> Unit, onDiscard: (String) -> Unit, compact: Boolean) {
    val label = PendingSends.label(p)
    val touch = if (compact) 48.dp else 0.dp
    Row(
        Modifier.padding(start = 8.dp).semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (p.state) {
            SendState.SENDING -> {
                CircularProgressIndicator(Modifier.size(9.dp), color = Palette.Muted, strokeWidth = 1.5.dp)
                Text(label, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, modifier = Modifier.testTag(PENDING_SEND_STATE_TAG))
            }
            SendState.ACCEPTED -> {
                Icon(AhqIcons.Check, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(11.dp))
                Text(label, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, modifier = Modifier.testTag(PENDING_SEND_STATE_TAG))
            }
            SendState.FAILED -> {
                Box(
                    Modifier.defaultMinSize(minHeight = touch).clickable(role = Role.Button) { onRetry(p.clientId) }.testTag(PENDING_SEND_RETRY_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AhqIcons.Refresh, contentDescription = null, tint = Palette.Danger, modifier = Modifier.size(11.dp))
                        Text(
                            label, color = Palette.Danger, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 3.dp).testTag(PENDING_SEND_STATE_TAG),
                        )
                    }
                }
                Box(
                    Modifier.defaultMinSize(minWidth = touch, minHeight = touch)
                        .clickable(role = Role.Button) { onDiscard(p.clientId) }
                        .semantics { contentDescription = PENDING_SEND_DISCARD_LABEL }.testTag(PENDING_SEND_DISCARD_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AhqIcons.Close, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}
