// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.CardFoldLimits
import com.superiorsoftllc.infantry.ui.model.CardKeys
import com.superiorsoftllc.infantry.ui.model.CardRole
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.foldCards
import com.superiorsoftllc.infantry.ui.model.parseChatMarkdownSafely
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** CARD-UPDATE-1: addressed cards fold over a session's lines; the first fence draws the latest state. */
class ChatCardFoldTest {
    private fun lines(vararg texts: String) = texts.mapIndexed { i, t -> i to parseChatMarkdownSafely(BuiltInChatMarkdownEngine, t) }
    private fun values(body: String) = ((Json.parseToJsonElement(body) as JsonObject)["items"] as JsonArray).map { (it as JsonObject)["value"].toString() }

    private val post = "Prices:\n\n```card:ticker id=acme\n{\"title\":\"ACME\",\"items\":[{\"value\":1}]}\n```"
    private val add2 = "```card:ticker id=acme op=append\n{\"items\":[{\"value\":2}]}\n```"
    private val add3 = "```card:ticker id=acme op=append\n{\"items\":[{\"value\":3}],\"unit\":\"USD\"}\n```"

    private fun append(v: Int) = "```card:ticker id=acme op=append\n{\"items\":[{\"value\":$v}]}\n```"

    @Test fun appendsFoldIntoTheFirstCard() {
        val ls = lines(post, add2, add3)
        val fold = foldCards(ls)
        val anchor = assertIs<CardRole.Anchor>(fold.rolesOf(0).single())
        assertEquals(listOf("1", "2", "3"), values(anchor.body))
        assertTrue("\"unit\":\"USD\"" in anchor.body)
        assertTrue("\"title\":\"ACME\"" in anchor.body)
        assertEquals(2, anchor.updates.size)
        assertIs<CardRole.Update>(fold.rolesOf(1).single())
        assertIs<CardRole.Update>(fold.rolesOf(2).single())
        // apply: the anchor block carries the folded body and the updates; the card renderer sees them.
        val anchorBlock = fold.apply(0, ls[0].second).last()
        val card = chatCardOf(anchorBlock)!!
        assertEquals(anchor.body, card.body)
        assertEquals(2, card.updates.size)
        assertFalse(isCardUpdate(anchorBlock))
        assertTrue(isCardUpdate(fold.apply(1, ls[1].second).single()))
    }

    @Test fun replaceReplacesTheState() {
        val fold = foldCards(lines(post, "```card:ticker id=acme op=replace\n{\"title\":\"NEW\",\"items\":[]}\n```"))
        assertEquals("{\"title\":\"NEW\",\"items\":[]}", assertIs<CardRole.Anchor>(fold.rolesOf(0).single()).body)
    }

    @Test fun cardsWithoutIdOrWithOneIdAreUntouched() {
        val ls = lines("```card:ticker\n{\"items\":[]}\n```", "```card:ticker id=b\n{\"items\":[]}\n```", "plain text")
        val fold = foldCards(ls)
        ls.forEach { (k, blocks) -> assertEquals(blocks, fold.apply(k, blocks)) }
    }

    @Test fun anIdReusedForAnotherTypeIsItsOwnCard() {
        val fold = foldCards(lines(post, "```card:news id=acme op=append\n{\"items\":[{\"value\":9}]}\n```"))
        assertTrue(fold.rolesOf(1).all { it == null })
    }

    @Test fun limitsBoundTheFold() {
        val many = (2..6).map(::append)
        val ls = lines(post, *many.toTypedArray())
        val fold = foldCards(ls, CardFoldLimits(maxUpdates = 3, maxItems = 2))
        val anchor = assertIs<CardRole.Anchor>(fold.rolesOf(0).single())
        // three updates applied (values 2, 3, 4), then two refused; only the newest two items kept.
        assertEquals(listOf("3", "4"), values(anchor.body))
        assertEquals(3, anchor.updates.size)
        assertFalse(assertIs<CardRole.Update>(fold.rolesOf(4).single()).applied)
        val refused = fold.apply(4, ls[4].second).single() as ChatBlock.Custom
        assertEquals("false", refused.data[CardKeys.APPLIED])
    }

    @Test fun anUpdateThatOutgrowsTheStateIsNotApplied() {
        val big = "```card:ticker id=acme op=append\n{\"items\":[{\"value\":2,\"pad\":\"${"x".repeat(200)}\"}]}\n```"
        val fold = foldCards(lines(post, big), CardFoldLimits(maxStateBytes = 100))
        assertFalse(assertIs<CardRole.Update>(fold.rolesOf(1).single()).applied)
        assertEquals(listOf("1"), values(assertIs<CardRole.Anchor>(fold.rolesOf(0).single()).body))
    }

    @Test fun updatesInTheSameLineFoldToo() {
        val roles = foldCards(lines("$post\n\n$add2")).rolesOf(0)
        assertEquals(listOf("1", "2"), values(assertIs<CardRole.Anchor>(roles[0]).body))
        assertIs<CardRole.Update>(roles[1])
    }
}
