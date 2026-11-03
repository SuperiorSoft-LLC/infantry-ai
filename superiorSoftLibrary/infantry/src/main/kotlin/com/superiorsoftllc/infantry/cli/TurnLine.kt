package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.MessageOrigin

/**
 * Contract 1.125.0: how the CLI and the MCP tools show a `turn` frame, from its typed origin only
 * (`[turn from architect (dd062cba)]`); the one place either reads [Envelope.origin], so the two cannot drift.
 */
object TurnLine {
    fun of(turn: Envelope): String = "[turn ${(turn.origin ?: MessageOrigin.Owner).label()}]"
}
