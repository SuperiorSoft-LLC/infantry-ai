// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** CARD-UPDATE-1: the default of `limits.chat.cardUpdates` (most update fences folded into one card). */
const val CARD_UPDATES_DEFAULT = 50

/** CARD-UPDATE-1: the default of `limits.chat.cardItems` (most `items` one folded card keeps; the oldest drop). */
const val CARD_ITEMS_DEFAULT = 200

/** CARD-UPDATE-1: the default of `limits.chat.cardStateBytes` (largest folded card state, as JSON text). */
const val CARD_STATE_BYTES_DEFAULT = 32_768

/** CARD-UPDATE-1: the key of a card's `items` array, the one an `op=append` appends to. */
const val CARD_ITEMS_KEY = "items"

/** CARD-UPDATE-1 (docs/CARD-REGISTRY.md, section 2.4): the named bounds of folding. Each is a ChatFeed setting. */
data class CardFoldLimits(
    val maxUpdates: Int = CARD_UPDATES_DEFAULT,
    val maxItems: Int = CARD_ITEMS_DEFAULT,
    val maxStateBytes: Int = CARD_STATE_BYTES_DEFAULT,
)

/** CARD-UPDATE-1: what one addressed card block is after folding. */
sealed interface CardRole {
    /** The card's first fence: it draws the folded [body]; [updates] are the raw bodies folded into it, oldest first. */
    data class Anchor(val body: String, val updates: List<String>) : CardRole

    /** A later fence with the same id: drawn as a muted "updated" line. [applied] is false when a limit refused it. */
    data class Update(val type: String, val id: String, val applied: Boolean) : CardRole
}

/**
 * CARD-UPDATE-1: the folded picture of one session's cards, keyed by line. [apply] rewrites one line's blocks: the anchor
 * gets the folded body and `card.updates`, an update gets `card.role = update`. A line with no addressed card is returned
 * as it is.
 */
class CardFold<K> internal constructor(private val roles: Map<K, List<CardRole?>>) {
    /** The roles of the card blocks of line [key], in block order (null = not addressed, drawn as is). */
    fun rolesOf(key: K): List<CardRole?> = roles[key].orEmpty()

    fun apply(key: K, blocks: List<ChatBlock>): List<ChatBlock> {
        val lineRoles = roles[key] ?: return blocks
        var k = 0
        return blocks.map { b ->
            if (!isCompleteCard(b)) return@map b
            val role = lineRoles.getOrNull(k++)
            withRole(b as ChatBlock.Custom, role)
        }
    }

    companion object {
        private val EMPTY = CardFold<Any?>(emptyMap())

        @Suppress("UNCHECKED_CAST")
        fun <K> empty(): CardFold<K> = EMPTY as CardFold<K>
    }
}

private fun isCompleteCard(b: ChatBlock): Boolean =
    b is ChatBlock.Custom && b.type.startsWith(CARD_TYPE_PREFIX) && b.data[CardKeys.STATE] == "complete"

private fun withRole(b: ChatBlock.Custom, role: CardRole?): ChatBlock = when (role) {
    null -> b
    is CardRole.Anchor -> if (role.updates.isEmpty()) b else b.copy(
        data = b.data + mapOf(CardKeys.BODY to role.body, CardKeys.UPDATES to JsonArray(role.updates.map { JsonPrimitive(it) }).toString()),
    )
    is CardRole.Update -> b.copy(data = b.data + mapOf(CardKeys.ROLE to CardKeys.ROLE_UPDATE, CardKeys.APPLIED to role.applied.toString()))
}

private class Folding(val type: String, var state: JsonObject, val updates: ArrayDeque<String> = ArrayDeque(), var count: Int = 0)

/**
 * CARD-UPDATE-1: fold the addressed cards of one session. [lines] are the session's parsed lines in order (key, blocks).
 * A card's id is scoped to these lines. The first complete card fence with an id is its anchor; a later fence with the same
 * id and type is an update: `op=replace` (and a repeated `op=set`) replaces the state, `op=append` appends the body's
 * `items` and merges its other keys (last wins). Beyond [CardFoldLimits.maxItems] the oldest items drop; an update past
 * [CardFoldLimits.maxUpdates] or one whose state would outgrow [CardFoldLimits.maxStateBytes] is not applied. Pure: the same
 * lines always give the same picture (reload, remote sessions).
 */
fun <K> foldCards(lines: List<Pair<K, List<ChatBlock>>>, limits: CardFoldLimits = CardFoldLimits()): CardFold<K> {
    val cards = HashMap<String, Folding>()
    val anchors = HashMap<String, Pair<K, Int>>()
    val roles = LinkedHashMap<K, MutableList<CardRole?>>()
    for ((key, blocks) in lines) {
        val lineRoles = ArrayList<CardRole?>()
        for (b in blocks) {
            if (!isCompleteCard(b)) continue
            val c = b as ChatBlock.Custom
            val id = c.data[CardKeys.ID].orEmpty()
            val body = parseObject(c.data[CardKeys.BODY])
            val type = c.type.removePrefix(CARD_TYPE_PREFIX)
            val known = cards[id]
            when {
                id.isEmpty() || body == null -> lineRoles += null
                known == null -> {
                    cards[id] = Folding(type, capItems(body, limits.maxItems))
                    anchors[id] = key to lineRoles.size
                    lineRoles += null // the anchor's role is filled in at the end, with the final state
                }
                known.type != type -> lineRoles += null // an id reused for another card type is its own card, not an update
                else -> {
                    val next = if (c.data[CardKeys.OP] == "append") append(known.state, body) else body
                    val capped = capItems(next, limits.maxItems)
                    val ok = known.count < limits.maxUpdates && capped.toString().length <= limits.maxStateBytes
                    if (ok) {
                        known.state = capped
                        known.count++
                        known.updates.addLast(c.data[CardKeys.BODY].orEmpty().trim())
                    }
                    lineRoles += CardRole.Update(type, id, ok)
                }
            }
        }
        if (lineRoles.isNotEmpty()) roles[key] = lineRoles
    }
    for ((id, at) in anchors) {
        val f = cards.getValue(id)
        val lineRoles = roles.getOrPut(at.first) { ArrayList() }
        lineRoles[at.second] = CardRole.Anchor(f.state.toString(), f.updates.toList())
    }
    roles.entries.removeAll { (_, r) -> r.all { it == null } }
    return CardFold(roles)
}

private fun parseObject(text: String?): JsonObject? =
    try { text?.let { Json.parseToJsonElement(it) as? JsonObject } } catch (_: Exception) { null }

/** `op=append`: [body]'s items after [state]'s, every other key of [body] merged over [state]'s. */
private fun append(state: JsonObject, body: JsonObject): JsonObject {
    val merged = LinkedHashMap<String, JsonElement>(state)
    for ((k, v) in body) if (k != CARD_ITEMS_KEY) merged[k] = v
    val old = state[CARD_ITEMS_KEY] as? JsonArray
    val add = body[CARD_ITEMS_KEY]
    val added = when (add) {
        null -> emptyList()
        is JsonArray -> add
        else -> listOf(add)
    }
    if (old != null || added.isNotEmpty()) merged[CARD_ITEMS_KEY] = JsonArray(old.orEmpty() + added)
    return JsonObject(merged)
}

/** The oldest items drop once there are more than [max]. */
private fun capItems(state: JsonObject, max: Int): JsonObject {
    val items = state[CARD_ITEMS_KEY] as? JsonArray ?: return state
    if (items.size <= max) return state
    return JsonObject(state + (CARD_ITEMS_KEY to JsonArray(items.takeLast(max.coerceAtLeast(0)))))
}
