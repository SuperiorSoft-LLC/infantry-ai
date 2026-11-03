// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.SlashAnswer
import com.superiorsoftllc.infantry.ui.model.moveSlashSelection
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.slashHeader
import com.superiorsoftllc.infantry.ui.model.slashPick
import com.superiorsoftllc.infantry.ui.model.slashSubmitOnEnter
import com.superiorsoftllc.infantry.ui.model.slashSuggestions
import com.superiorsoftllc.infantry.ui.model.slashTyped
import com.superiorsoftllc.infantry.ui.protocol.CommandCatalogAnswer
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SlashCommand
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CP-4(c): the pure rules behind the slash autocomplete -- what is a command, what rows to show, what picking one types. */
class SlashModelTest {
    private val model = SlashCommand("model", "Switch the model", "SET_MODEL", argument = "<model>")
    private val effort = SlashCommand("effort", "Set the effort", "SET_EFFORT", argument = "<level>")
    private val compact = SlashCommand("compact", "Compact", "NATIVE", native = "/compact")

    private fun stateWith(sessionId: String?, prefix: String, vararg commands: SlashCommand) =
        AppState().copy(slash = SlashAnswer(sessionId, CommandCatalogAnswer("claude", prefix, commands.toList())))

    @Test
    fun aLeadingSlashIsACommandAndTheTypedTextIsWhatFollowsIt() {
        assertEquals("", slashTyped("/"))
        assertEquals("mo", slashTyped("/mo"))
        assertEquals("effort hi", slashTyped("  /effort hi"))
        assertNull(slashTyped("hello /model"))
        assertNull(slashTyped(""))
    }

    @Test
    fun pickingACommandThatTakesAnArgumentTypesItWithATrailingSpace() {
        val rows = slashSuggestions(stateWith("s1", "", model, effort, compact), "s1", "/")
        assertEquals(listOf("/model <model>", "/effort <level>", "/compact"), rows.map { it.label })
        assertEquals(listOf("/model ", "/effort ", "/compact"), rows.map { it.replacement })
        assertEquals("Compact", rows.last().detail)
    }

    @Test
    fun theValuesOfANamedCommandAreOfferedAndPickingOneCompletesTheLine() {
        val high = effort.copy(values = listOf("high"))
        val rows = slashSuggestions(stateWith("s1", "effort hi", high), "s1", "/effort hi")
        assertEquals(listOf("high"), rows.map { it.label })
        assertEquals(listOf("/effort high"), rows.map { it.replacement })
    }

    @Test
    fun anAnswerForAnotherSessionOrAnOlderPrefixIsNeverShown() {
        val s = stateWith("s1", "m", model)
        assertTrue(slashSuggestions(s, "s2", "/m").isEmpty(), "another session's provider")
        assertTrue(slashSuggestions(s, "s1", "/mo").isEmpty(), "the box moved on: the answer is stale")
        assertTrue(slashSuggestions(s, "s1", "hello").isEmpty(), "not a command")
        assertEquals(1, slashSuggestions(s, "s1", "/m").size)
    }

    @Test
    fun theCommandCatalogFrameLandsInStateAndTheRequestCarriesTheTypedPrefix() {
        val request = WireRequests.commandCatalog("s1", "mo")
        assertEquals(Envelope.COMMAND_CATALOG, request.type)
        assertEquals("s1", request.sessionId)
        assertEquals("""{"prefix":"mo"}""", request.data)

        val frame = Envelope(
            Envelope.COMMAND_CATALOG,
            """{"provider":"claude","prefix":"mo","commands":[{"name":"model","description":"d","action":"SET_MODEL","argument":"<model>","values":[]}]}""",
            "s1",
        )
        val state = reduce(AppState(), frame, 0L)
        assertEquals("s1", state.slash?.sessionId)
        assertEquals(listOf("model"), state.slash?.answer?.commands?.map { it.name })
    }

    @Test
    fun theSelectionWrapsAndAnEmptyListStaysAtZero() {
        assertEquals(1, moveSlashSelection(0, 1, 3))
        assertEquals(2, moveSlashSelection(0, -1, 3))
        assertEquals(0, moveSlashSelection(2, 1, 3))
        assertEquals(0, moveSlashSelection(5, 1, 0))
    }

    @Test
    fun enterPicksTheHighlightedRowUnlessItIsAlreadyInTheBox() {
        val rows = slashSuggestions(stateWith("s1", "", model, compact), "s1", "/")
        assertEquals("/compact", slashPick(rows, 1, "/")?.replacement)
        assertNull(slashPick(rows, 1, "/compact"), "already typed: Enter submits instead of re-picking")
        assertNull(slashPick(rows, 7, "/"), "no such row")
    }

    @Test
    fun spawnModeOnlyTrustsTheAnswerForThePickedProvider() {
        val s = stateWith(null, "", model)
        assertEquals(1, slashSuggestions(s, null, "/", "claude").size)
        assertTrue(slashSuggestions(s, null, "/", "codex").isEmpty(), "asked for another provider before the pick changed")
        assertEquals(1, slashSuggestions(s, null, "/", null).size, "no pick: the daemon default's answer")
    }

    @Test
    fun theSpawnRequestNamesTheProviderAndASessionRequestStaysUnchanged() {
        val spawn = WireRequests.commandCatalog(null, "mo", "codex")
        assertNull(spawn.sessionId)
        assertEquals("""{"prefix":"mo","provider":"codex"}""", spawn.data)
        assertEquals("""{"prefix":"mo"}""", WireRequests.commandCatalog("s1", "mo").data)
    }

    @Test
    fun slashModelListsTheDaemonsCatalogForThatProviderOnly() {
        val catalog = listOf(
            com.superiorsoftllc.infantry.ui.protocol.ProviderModels("claude", "", "claude", listOf("opus-x", "sonnet-y")),
            com.superiorsoftllc.infantry.ui.protocol.ProviderModels("other", "", "other", listOf("zzz")),
        )
        val st = stateWith("s1", "model ", model).copy(modelCatalog = catalog)
        assertEquals(listOf("opus-x", "sonnet-y"), slashSuggestions(st, "s1", "/model ").map { it.label })
        assertEquals(listOf("/model sonnet-y"), slashSuggestions(stateWith("s1", "model son", model).copy(modelCatalog = catalog), "s1", "/model son").map { it.replacement })
    }

    private val modelIds = listOf("opus", "sonnet", "haiku", "opus[1m]", "sonnet[1m]", "claude-sonnet-5-5", "claude-fable-5-1")
    private fun modelState(typed: String) = stateWith("s1", typed, model.copy(values = modelIds))

    @Test
    fun cmdModel1FiltersModelsBySubstringIgnoringCase() {
        assertEquals(listOf("sonnet", "sonnet[1m]", "claude-sonnet-5-5"), slashSuggestions(modelState("model SON"), "s1", "/model SON").map { it.label })
        assertEquals("claude models", slashHeader(modelState("model son"), "s1", "/model son"))
        assertNull(slashHeader(stateWith("s1", "effort ", effort), "s1", "/effort "), "only the model list has the harness header")
    }

    @Test
    fun cmdModel1NoMatchIsOneRowThatIsNeverPicked() {
        val rows = slashSuggestions(modelState("model zzz"), "s1", "/model zzz")
        assertEquals(listOf("No model matches 'zzz'"), rows.map { it.label })
        assertNull(slashPick(rows, 0, "/model zzz"))
        assertNull(slashSubmitOnEnter(rows, "/model zzz"))
    }

    @Test
    fun cmdModel1EnterOnTheSingleMatchSubmitsIt() {
        val rows = slashSuggestions(modelState("model haik"), "s1", "/model haik")
        assertEquals("/model haiku", slashSubmitOnEnter(rows, "/model haik")?.replacement)
        assertNull(slashSubmitOnEnter(slashSuggestions(modelState("model son"), "s1", "/model son"), "/model son"), "three matches: a choice is left")
    }
}
