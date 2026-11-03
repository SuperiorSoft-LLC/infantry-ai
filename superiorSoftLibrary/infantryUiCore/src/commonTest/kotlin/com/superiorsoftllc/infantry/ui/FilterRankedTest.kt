// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.SearchText
import com.superiorsoftllc.infantry.ui.model.filterRanked
import kotlin.test.Test
import kotlin.test.assertEquals

/** ROLE-FIRST S6: the pure ranking behind every type-to-filter list (docs/INFANTRY-ROLE-FIRST.md section 7.1). */
class FilterRankedTest {
    private fun rank(items: List<SearchText>, query: String): List<String> = filterRanked(items, query) { it }.map { it.id }

    private val models = listOf(
        SearchText("anthropic/claude-sonnet-4-5", "claude-sonnet-4-5", "anthropic"),
        SearchText("anthropic/claude-sonnet-4", "claude-sonnet-4", "anthropic"),
        SearchText("anthropic/claude-opus-4", "claude-opus-4", "anthropic"),
        SearchText("openrouter/anthropic/claude-sonnet-4", "anthropic/claude-sonnet-4", "openrouter"),
        SearchText("deepseek/deepseek-chat", "deepseek-chat", "deepseek"),
    )

    @Test
    fun `an empty or blank query keeps the original order and drops nothing`() {
        assertEquals(models.map { it.id }, rank(models, ""))
        assertEquals(models.map { it.id }, rank(models, "   "))
    }

    @Test
    fun `every term must match somewhere, so 'son 4' finds the sonnet 4 models and nothing else`() {
        assertEquals(
            listOf("anthropic/claude-sonnet-4-5", "anthropic/claude-sonnet-4", "openrouter/anthropic/claude-sonnet-4"),
            rank(models, "son 4"),
        )
    }

    @Test
    fun `a query that matches nothing returns an empty list`() = assertEquals(emptyList(), rank(models, "gemini"))

    @Test
    fun `a term that matches no item empties the list even when the other term matches`() =
        assertEquals(emptyList(), rank(models, "sonnet zzz"))

    @Test
    fun `matching is case insensitive on both sides`() {
        assertEquals(listOf("anthropic/claude-opus-4"), rank(models, "OPUS"))
        assertEquals(listOf("DeepSeek/Chat"), rank(listOf(SearchText("DeepSeek/Chat")), "deepseek/CHAT"))
    }

    @Test
    fun `an exact id outranks a segment prefix, a label prefix, a substring and a group match, in that order`() {
        val items = listOf(
            SearchText("zz-lite", "unrelated", "gpt"),      // group only         -> tier 4
            SearchText("xgptx", "plain", ""),              // substring in id    -> tier 3
            SearchText("zz-one", "gpt label", ""),         // label prefix       -> tier 2
            SearchText("vendor/gpt-4", "plain-two", ""),   // id segment prefix  -> tier 1
            SearchText("gpt", "plain-three", ""),          // exact id           -> tier 0
        )
        assertEquals(listOf("gpt", "vendor/gpt-4", "zz-one", "xgptx", "zz-lite"), rank(items, "gpt"))
    }

    @Test
    fun `items of the same rank keep the daemon's order`() {
        val items = listOf(SearchText("b/m-one"), SearchText("a/m-two"), SearchText("c/m-three"))
        assertEquals(listOf("b/m-one", "a/m-two", "c/m-three"), rank(items, "m"))
    }

    @Test
    fun `a multi-term query ranks an item by its weakest term`() {
        val items = listOf(
            SearchText("vendor/model-sonnet", "x", ""),   // 'model' segment prefix (1), 'nnet' substring (3) -> weakest 3
            SearchText("model/nnet-x", "y", ""),          // both terms segment prefixes (1, 1)              -> weakest 1
        )
        assertEquals(listOf("model/nnet-x", "vendor/model-sonnet"), rank(items, "model nnet"))
    }

    @Test
    fun `a group match counts but ranks below every id and label match`() {
        val items = listOf(SearchText("alpha", "alpha", "claude"), SearchText("claude-x", "claude-x", ""))
        assertEquals(listOf("claude-x", "alpha"), rank(items, "claude"))
    }

    @Test
    fun `segments split on slash dash dot colon and underscore`() {
        val items = listOf(SearchText("a/b-c.d:e_f"))
        for (term in listOf("b", "c", "d", "e", "f")) assertEquals(1, rank(items, term).size, term)
        // 'x' prefix of nothing; but a mid-segment fragment is only a substring match (still a match)
        assertEquals(1, rank(items, "-c").size)
    }
}
