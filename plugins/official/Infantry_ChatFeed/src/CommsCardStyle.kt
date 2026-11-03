package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.ui.*

import androidx.compose.ui.graphics.Color
import com.superiorsoftllc.infantry.ui.model.MessageOrigin

/** Which theme colour family a chat card is tinted from. */
enum class CardTone {
    /** The owner's own message: the theme accent. */
    OwnerAccent,
    /** The agent's reply to the owner: the raised surface. */
    AgentReply,
    /** A message from an outside sender: the theme info colour, visibly distinct from the owner card. */
    Incoming;

    /** The card body fill. */
    val body: Color get() = when (this) {
        OwnerAccent -> Palette.Accent.copy(alpha = 0.12f)
        AgentReply -> Palette.Raised
        Incoming -> Palette.Info.copy(alpha = 0.14f)
    }

    /** The header band: a lighter tint of the same family, drawn over [body]. */
    val header: Color get() = when (this) {
        OwnerAccent -> Palette.Accent.copy(alpha = 0.24f)
        AgentReply -> Palette.Text.copy(alpha = 0.08f)
        Incoming -> Palette.Info.copy(alpha = 0.28f)
    }
}

/** What a comms card's header shows: [title], the [tone] its colours come from, and whether the icon and time appear. */
data class CommsCardStyle(val title: String, val tone: CardTone, val showIcon: Boolean = true, val showTime: Boolean = true)

private val Conversation = CommsCardStyle("Comms", CardTone.OwnerAccent)
private val Incoming = CommsCardStyle("Incoming Comms", CardTone.Incoming)

/** Style of the agent's own reply card (owner-agent conversation). */
val AgentReplyCardStyle = CommsCardStyle("Comms", CardTone.AgentReply)

/**
 * THE one table from a turn's [MessageOrigin] to its card style. Exhaustive: a new origin kind does not compile
 * until it is styled here. Never looks at text.
 */
fun commsCardStyle(origin: MessageOrigin): CommsCardStyle = when (origin) {
    MessageOrigin.Owner -> Conversation
    is MessageOrigin.Agent, is MessageOrigin.External, is MessageOrigin.System -> Incoming
}
