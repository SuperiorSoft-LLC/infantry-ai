package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.filterPrompts
import com.superiorsoftllc.agenthq.ui.model.insertIntoPrompt
import com.superiorsoftllc.agenthq.ui.model.parsePromptTags
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.withPromptsRequested
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.PromptView
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-C: the Prompts tab's model. The `prompts` / `prompt_item` frames (contract 1.44.0) fill the library,
 * the three states (empty, loading, daemon error) come from the state, search is the shared ranked filter,
 * and Insert is a pure function of the prompt box text.
 */
class PromptLibraryModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }
    private fun p(id: String, name: String, vararg tags: String, body: String = "body of $name") =
        PromptView(id, name, body, tags.toList(), 1, 2)
    private fun list(vararg x: PromptView) = Envelope(Envelope.PROMPTS, json.encodeToString(ListSerializer(PromptView.serializer()), x.toList()))
    private fun item(x: PromptView) = Envelope(Envelope.PROMPT_ITEM, json.encodeToString(PromptView.serializer(), x))

    @Test fun promptsFrameFillsTheLibrarySortedByNameAndEndsLoading() {
        val s = feed(list(p("2", "zebra"), p("1", "Alpha", "ops")), from = AppState().withPromptsRequested())
        assertEquals(listOf("Alpha", "zebra"), s.prompts.map { it.name })
        assertFalse(s.promptsLoading)
    }

    @Test fun emptyListIsAnAnswerNotAMissingOne() {
        val s = feed(list(p("1", "a")), list())
        assertTrue(s.prompts.isEmpty())
    }

    @Test fun promptItemUpsertsById() {
        val s = feed(list(p("1", "a"), p("2", "b")), item(p("1", "renamed", "x", body = "new")), item(p("3", "c")))
        assertEquals(listOf("b", "c", "renamed"), s.prompts.map { it.name })
        assertEquals("new", s.prompts.first { it.id == "1" }.body)
    }

    @Test fun loadingIsSetByTheRequestAndClearedByTheAnswer() {
        val asked = AppState().withPromptsRequested()
        assertTrue(asked.promptsLoading)
        assertFalse(feed(list(), from = asked).promptsLoading)
    }

    @Test fun daemonRefusalShowsInlineAndEndsLoadingThenClearsOnSuccess() {
        val refused = feed(Envelope(Envelope.ERROR, "prompt_save refused: name is blank"), from = AppState().withPromptsRequested())
        assertEquals("prompt_save refused: name is blank", refused.promptError)
        assertFalse(refused.promptsLoading)
        assertNull(feed(item(p("1", "a")), from = refused).promptError)
        // An unrelated refusal is not a prompt error.
        assertNull(feed(Envelope(Envelope.ERROR, "unknown pod")).promptError)
    }

    @Test fun searchMatchesNameOrTag() {
        val all = listOf(p("1", "Release notes", "writing"), p("2", "Bug triage", "ops", "release"), p("3", "Standup"))
        assertEquals(listOf("Release notes", "Bug triage"), filterPrompts(all, "release").map { it.name })
        assertEquals(listOf("Bug triage"), filterPrompts(all, "ops").map { it.name })
        assertEquals(3, filterPrompts(all, "  ").size)
        assertTrue(filterPrompts(all, "nomatch").isEmpty())
    }

    @Test fun tagFieldSplitsOnCommasAndDropsBlanks() {
        assertEquals(listOf("a", "b c", "d"), parsePromptTags(" a, b c ,, d ,"))
        assertTrue(parsePromptTags("").isEmpty())
    }

    @Test fun insertReplacesAnEmptyBoxAndAppendsOtherwise() {
        assertEquals("BODY", insertIntoPrompt("  ", "BODY"))
        assertEquals("typed\nBODY", insertIntoPrompt("typed\n", "BODY"))
    }

    @Test fun requestsMatchTheContractFrames() {
        assertEquals(Envelope.PROMPT_LIST, WireRequests.promptList().type)
        assertEquals("", WireRequests.promptList().data)
        assertEquals(Envelope.PROMPT_DELETE, WireRequests.promptDelete("id-1").type)
        assertEquals("id-1", WireRequests.promptDelete("id-1").data)
        val save = WireRequests.promptSave("n", "b", listOf("t1", "t2"))
        assertEquals(Envelope.PROMPT_SAVE, save.type)
        assertEquals("""{"name":"n","body":"b","tags":["t1","t2"]}""", save.data)
    }
}
