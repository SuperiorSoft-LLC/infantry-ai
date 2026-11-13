// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** CARD-1: the prefix of a card fence's info string and of the [ChatBlock.Custom] type it becomes. */
const val CARD_TYPE_PREFIX = "card:"

/** CARD-1: the keys of a card block's [ChatBlock.Custom.data]. */
object CardKeys {
    const val BODY = "card.body"
    const val ID = "card.id"
    const val OP = "card.op"
    const val STATE = "card.state"
    /** The fence's info string and body, so a client with no renderer for the type redraws the original code block. */
    const val LANGUAGE = "card.language"
    const val CODE = "card.code"
}

private val cardOps = setOf("set", "append", "replace")

/**
 * CARD-1 (docs/CARD-REGISTRY.md, section 2.1): a pure post-parse step over ANY engine's blocks. A [ChatBlock.Code]
 * whose info string is `card:<type> [id=..] [op=set|append|replace]` and whose body is one JSON object becomes
 * `ChatBlock.Custom("card:<type>", <original fenced text>, {card.body, card.id, card.op, card.state})`.
 * Everything else, an invalid header or body, and the still-open last fence while [streaming], stays as it is
 * (the code block every client showed before).
 */
fun cardBlocks(blocks: List<ChatBlock>, streaming: Boolean = false): List<ChatBlock> {
    if (blocks.none { it is ChatBlock.Code && it.language.startsWith(CARD_TYPE_PREFIX) }) return blocks
    return blocks.mapIndexed { i, b ->
        if (b !is ChatBlock.Code || (streaming && i == blocks.lastIndex)) b else cardBlockOf(b) ?: b
    }
}

/** The card block for one fenced [code] block, or null when it is not a valid card fence. */
fun cardBlockOf(code: ChatBlock.Code): ChatBlock.Custom? {
    val words = code.language.trim().split(Regex("\\s+"))
    val head = words.first()
    if (!head.startsWith(CARD_TYPE_PREFIX)) return null
    val type = head.removePrefix(CARD_TYPE_PREFIX)
    if (type.isEmpty() || !type.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) return null
    var id = ""
    var op = "set"
    for (w in words.drop(1)) {
        val eq = w.indexOf('=')
        if (eq <= 0) return null
        val v = w.substring(eq + 1)
        when (w.substring(0, eq)) {
            "id" -> id = v
            "op" -> if (v in cardOps) op = v else return null
            else -> return null
        }
    }
    val body = code.body.trim()
    val ok = try { Json.parseToJsonElement(body) is JsonObject } catch (_: Exception) { false }
    if (!ok) return null
    return ChatBlock.Custom(
        type = head,
        text = "```${code.language}\n${code.body}\n```",
        data = mapOf(
            CardKeys.BODY to body, CardKeys.ID to id, CardKeys.OP to op, CardKeys.STATE to "complete",
            CardKeys.LANGUAGE to code.language, CardKeys.CODE to code.body,
        ),
    )
}
