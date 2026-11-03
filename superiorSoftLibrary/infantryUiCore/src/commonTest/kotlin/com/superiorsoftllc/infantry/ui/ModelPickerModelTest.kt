// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.groupModels
import com.superiorsoftllc.infantry.ui.model.modelChoices
import com.superiorsoftllc.infantry.ui.model.modelGroupChoices
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AGENT-UX-10: the profile editor's model picker is driven ENTIRELY by the `providers` frame -- the
 * model list the daemon read from the provider CLI. These tests feed the frame and read the choices, so
 * none of them can be satisfied by a hardcoded list in the UI.
 */
class ModelPickerModelTest {

    private val opencode = ProviderInfo(
        id = "opencode",
        default = true,
        defaultModel = "opencode/space-bunny-free",
        models = listOf("opencode/space-bunny-free", "opencode-go/gpt-6-luna", "opencode-go/deepseek-v4-pro"),
    )
    private val claude = ProviderInfo(id = "claude", defaultModel = "claude/sonnet", models = listOf("claude/sonnet", "claude/haiku"))

    @Test
    fun `the model list renders from the providers frame and selecting a provider narrows it`() {
        val providers = listOf(opencode, claude)
        assertEquals(
            listOf("opencode/space-bunny-free", "opencode-go/gpt-6-luna", "opencode-go/deepseek-v4-pro"),
            modelChoices(providers, "opencode", null).options,
        )
        assertEquals(
            listOf("claude/sonnet", "claude/haiku"),
            modelChoices(providers, "claude", null).options,
            "the model list is the CHOSEN provider's, not everyone's",
        )
        // No provider named: the frame's default provider.
        assertEquals(modelChoices(providers, "opencode", null).options, modelChoices(providers, null, null).options)
    }

    @Test
    fun `the chosen model id is the wire id verbatim, including its slash`() {
        // The picker's options ARE the ids the daemon expects, so choosing one stores it unchanged.
        val choices = modelChoices(listOf(opencode), "opencode", null)
        assertEquals(opencode.models, choices.options, "every option is an exact wire id, never a label")
        val picked = choices.options.single { it == "opencode-go/gpt-6-luna" }
        assertEquals("opencode-go/gpt-6-luna", picked, "the slash-bearing id is the value")
        // Round-trips: stored back, it is still that exact id, not flagged missing.
        val stored = modelChoices(listOf(opencode), "opencode", picked)
        assertFalse(stored.selectedMissing)
        assertTrue(stored.options.contains(picked))
    }

    @Test
    fun `a stored model absent from the reported list is surfaced, not dropped and not rewritten`() {
        val choices = modelChoices(listOf(opencode), "opencode", "opencode/space-bunny-freee")
        assertTrue(choices.selectedMissing, "a stale id is information and must be flagged")
        assertTrue(choices.options.contains("opencode/space-bunny-freee"), "kept verbatim: ${choices.options}")
        assertEquals("opencode/space-bunny-freee", choices.options.first(), "surfaced so it is not lost")
    }

    @Test
    fun `a provider that reports no list keeps the form usable as free text`() {
        val noList = ProviderInfo(id = "stub", default = true, defaultModel = null)
        val empty = modelChoices(listOf(noList), "stub", null)
        assertTrue(empty.freeText, "no reported list -> free text, never a disabled form")
        assertTrue(empty.options.isEmpty())

        val emptyFrame = modelChoices(emptyList(), null, "opencode/x")
        assertTrue(emptyFrame.freeText, "an empty providers frame must not break the form")
        assertTrue(emptyFrame.options.contains("opencode/x"), "the stored value still shows")
    }

    @Test
    fun `a stale catalogue is reported so the picker never hides it`() {
        val stale = opencode.copy(modelsStale = true)
        assertTrue(modelChoices(listOf(stale), "opencode", null).stale)
        assertFalse(modelChoices(listOf(opencode), "opencode", null).stale)
    }

    // ---- UI-PROV-1: ONE flat list from many sub-providers, as `opencode models` really returns it ----

    /**
     * The shape the daemon serves for opencode: three sub-provider prefixes in one list, deliberately
     * out of order, plus one id that names no sub-provider at all.
     */
    private val subProviderIds = listOf(
        "opencode-go/mimo-v2.6-pro",
        "opencode/nemotron-3.5-lightning-free",
        "opencode-go/gpt-6-luna",
        "anthropic/claude-opus-5",
        "bare-model",
    )

    private val multiProvider = ProviderInfo(
        id = "opencode",
        default = true,
        defaultModel = "opencode/nemotron-3.5-lightning-free",
        models = listOf("opencode/nemotron-3.5-lightning-free", "opencode-go/mimo-v2.6-pro", "opencode-go/gpt-6-luna"),
    )

    @Test
    fun `groupModels keys every id by the text before its first slash, keys and ids sorted, a slashless id under the empty key`() {
        assertEquals(
            mapOf(
                "" to listOf("bare-model"),
                "anthropic" to listOf("anthropic/claude-opus-5"),
                "opencode" to listOf("opencode/nemotron-3.5-lightning-free"),
                "opencode-go" to listOf("opencode-go/gpt-6-luna", "opencode-go/mimo-v2.6-pro"),
            ),
            groupModels(subProviderIds),
            "three prefixes plus the slashless ids, keys and values sorted",
        )
        assertEquals(
            listOf("", "anthropic", "opencode", "opencode-go"),
            groupModels(subProviderIds).keys.toList(),
            "the group keys are sorted, never the CLI's print order",
        )
        // Only the FIRST slash splits: a nested id stays in its top-level sub-provider.
        assertEquals(mapOf("x" to listOf("x/y/z")), groupModels(listOf("x/y/z")))
        // Nothing reported -> nothing to group, never a phantom empty group.
        assertEquals(emptyMap<String, List<String>>(), groupModels(emptyList()))
    }

    @Test
    fun `choosing a sub-provider narrows the models to that group's ids`() {
        // Nothing stored yet: both sub-providers are offered, the first is shown, its ids are the list.
        val fresh = modelGroupChoices(listOf(multiProvider), "opencode", null)
        assertEquals(listOf("opencode", "opencode-go"), fresh.groups)
        assertTrue(fresh.twoLevel, "two sub-providers = a group row above the model list")
        assertEquals("opencode", fresh.group)
        assertEquals(listOf("opencode/nemotron-3.5-lightning-free"), fresh.models)

        // Editing a profile preselects the sub-provider of its STORED model (the ticket's requirement 2).
        val editing = modelGroupChoices(listOf(multiProvider), "opencode", "opencode-go/mimo-v2.6-pro")
        assertEquals("opencode-go", editing.group)
        assertEquals(listOf("opencode-go/gpt-6-luna", "opencode-go/mimo-v2.6-pro"), editing.models)
        assertEquals("opencode-go/mimo-v2.6-pro", editing.model, "the stored id is shown verbatim")
        assertTrue("opencode/nemotron-3.5-lightning-free" !in editing.models, "the other sub-provider's ids are gone")
    }

    @Test
    fun `switching the sub-provider clears a model that is no longer in the list`() {
        val editing = modelGroupChoices(listOf(multiProvider), "opencode", "opencode-go/mimo-v2.6-pro")

        // A model outside the newly chosen sub-provider is dropped (back to "provider default") -- keeping
        // it would show a model the dropdown no longer offers.
        val moved = editing.withGroup("opencode")
        assertEquals("opencode", moved.group)
        assertEquals("", moved.model, "a model outside the chosen sub-provider is cleared, never kept as a lie")
        assertEquals(listOf("opencode/nemotron-3.5-lightning-free"), moved.models)

        // A model that IS in the newly chosen sub-provider survives.
        assertEquals("opencode-go/mimo-v2.6-pro", editing.withGroup("opencode-go").model)
        // An unknown group changes nothing (the UI only ever passes a key it was offered).
        assertEquals(editing, editing.withGroup("no-such-sub-provider"))
    }

    @Test
    fun `one sub-provider keeps the flat picker, and a provider with no list stays free text`() {
        val single = ProviderInfo(id = "claude", default = true, defaultModel = "claude/sonnet", models = listOf("claude/sonnet", "claude/haiku"))
        val one = modelGroupChoices(listOf(single), "claude", null)
        assertFalse(one.twoLevel, "one sub-provider has nothing to choose: no group row")
        assertEquals(listOf("claude"), one.groups)
        assertEquals(listOf("claude/haiku", "claude/sonnet"), one.models, "one group = the reported ids") // sorted, no reordering loss

        // Ids that carry no slash at all land in the "" group, which the picker can still offer.
        val noSlash = modelGroupChoices(listOf(ProviderInfo(id = "p", default = true, models = listOf("alpha", "beta"))), "p", "beta")
        assertEquals(listOf(""), noSlash.groups)
        assertFalse(noSlash.twoLevel)
        assertEquals(listOf("alpha", "beta"), noSlash.models)
        assertEquals("beta", noSlash.model)

        // A provider with no catalogue keeps the free-text form: grouping has nothing to group.
        val free = modelGroupChoices(listOf(ProviderInfo(id = "stub", default = true)), "stub", null)
        assertTrue(modelChoices(listOf(ProviderInfo(id = "stub", default = true)), "stub", null).freeText)
        assertFalse(free.twoLevel, "no reported list -> the form stays free text, never a broken picker")
    }

    @Test
    fun `a stored model the provider no longer lists keeps its own group and stays visible`() {
        val info = ProviderInfo(id = "opencode", default = true, models = listOf("opencode-go/gpt-6-luna"))
        val stale = modelGroupChoices(listOf(info), "opencode", "deepseek/chat-v3")
        assertEquals(listOf("deepseek", "opencode-go"), stale.groups, "the stale id is surfaced as its own sub-provider")
        assertEquals("deepseek", stale.group, "and that group is selected, so the stored id is never hidden")
        assertEquals(listOf("deepseek/chat-v3"), stale.models)
        assertEquals("deepseek/chat-v3", stale.model)
        assertTrue(modelChoices(listOf(info), "opencode", "deepseek/chat-v3").selectedMissing, "still flagged as before")
    }
}
