// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.superiorsoftllc.infantry.ui.LocalChatMarkdown
import com.superiorsoftllc.infantry.ui.isCardUpdate
import com.superiorsoftllc.infantry.ui.model.CARD_ITEMS_DEFAULT
import com.superiorsoftllc.infantry.ui.model.CARD_STATE_BYTES_DEFAULT
import com.superiorsoftllc.infantry.ui.model.CARD_UPDATES_DEFAULT
import com.superiorsoftllc.infantry.ui.model.CardFold
import com.superiorsoftllc.infantry.ui.model.CardFoldLimits
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.OutputLine
import com.superiorsoftllc.infantry.ui.model.chatLineBlocks
import com.superiorsoftllc.infantry.ui.model.foldCards
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext

/** CARD-UPDATE-1: setting, most update fences folded into one card. */
const val CARD_UPDATES_KEY = "limits.chat.cardUpdates"

/** CARD-UPDATE-1: setting, most `items` a folded card keeps (the oldest drop). */
const val CARD_ITEMS_KEY_SETTING = "limits.chat.cardItems"

/** CARD-UPDATE-1: setting, largest folded card state (bytes of JSON). */
const val CARD_STATE_BYTES_KEY = "limits.chat.cardStateBytes"

/** CARD-UPDATE-1: setting, whether a card's later update fences show as a muted "updated" line (off = nothing). */
const val CARD_UPDATE_LINE_KEY = "chat.cardUpdateLine"

/** CARD-UPDATE-1: the session's folded cards, provided by [AgentChat] around its rows; empty outside a transcript. */
internal val LocalSessionCards = compositionLocalOf { SessionCards(CardFold.empty(), showUpdateLines = true) }

/** CARD-UPDATE-1: one session's card fold plus the update-line setting. */
internal class SessionCards(val fold: CardFold<OutputLine>, val showUpdateLines: Boolean) {
    /** [blocks] of [line] with the fold applied; update fences dropped when the update line is off. */
    fun apply(line: OutputLine, blocks: List<ChatBlock>): List<ChatBlock> {
        val folded = fold.apply(line, blocks)
        return if (showUpdateLines) folded else folded.filterNot(::isCardUpdate)
    }
}

/** A cheap text test first: only an assistant line holding an addressed card fence is parsed for the fold. */
private fun mayHoldAddressedCard(line: OutputLine): Boolean =
    line.kind == LineKind.TEXT && "```card:" in line.text && "id=" in line.text

/** CARD-UPDATE-1: fold the addressed cards of [output] (pure; the chat recomputes it when the transcript changes). */
internal fun sessionCardFold(output: List<OutputLine>, engine: ChatMarkdownEngine, limits: CardFoldLimits): CardFold<OutputLine> {
    val lines = output.filter(::mayHoldAddressedCard).map { it to chatLineBlocks(it.kind, it.text, engine).orEmpty() }
    return if (lines.isEmpty()) CardFold.empty() else foldCards(lines, limits)
}

/** CARD-UPDATE-1: [output]'s folded cards with the ChatFeed settings; recomputed only when the transcript or a setting changes. */
@Composable
internal fun rememberSessionCards(output: List<OutputLine>): SessionCards {
    val values = LocalShellContext.current?.config?.values(FEED_SECTION_ID)
    val limits = CardFoldLimits(
        maxUpdates = values?.int(CARD_UPDATES_KEY) ?: CARD_UPDATES_DEFAULT,
        maxItems = values?.int(CARD_ITEMS_KEY_SETTING) ?: CARD_ITEMS_DEFAULT,
        maxStateBytes = values?.int(CARD_STATE_BYTES_KEY) ?: CARD_STATE_BYTES_DEFAULT,
    )
    val show = values?.boolean(CARD_UPDATE_LINE_KEY) ?: true
    val engine = LocalChatMarkdown.current.engine
    return remember(output, limits, show, engine) { SessionCards(sessionCardFold(output, engine, limits), show) }
}
