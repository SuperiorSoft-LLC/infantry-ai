package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.filterSkills
import com.superiorsoftllc.agenthq.ui.model.insertIntoPrompt
import com.superiorsoftllc.agenthq.ui.model.parsePromptTags
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.withSkillsRequested
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.SkillView
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-D: the Skills tab's model. The `prompts` / `prompt_item` frames (contract 1.45.0) fill the library,
 * the three states (empty, loading, daemon error) come from the state, search is the shared ranked filter,
 * and Insert is a pure function of the prompt box text.
 */
class SkillLibraryModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }
    private fun p(id: String, name: String, vararg tags: String, body: String = "body of $name", desc: String = "") =
        SkillView(id, name, desc, body, tags.toList(), 1, 2)
    private fun list(vararg x: SkillView) = Envelope(Envelope.SKILLS, json.encodeToString(ListSerializer(SkillView.serializer()), x.toList()))
    private fun item(x: SkillView) = Envelope(Envelope.SKILL_ITEM, json.encodeToString(SkillView.serializer(), x))

    @Test fun promptsFrameFillsTheLibrarySortedByNameAndEndsLoading() {
        val s = feed(list(p("2", "zebra"), p("1", "Alpha", "ops")), from = AppState().withSkillsRequested())
        assertEquals(listOf("Alpha", "zebra"), s.skills.map { it.name })
        assertFalse(s.skillsLoading)
    }

    @Test fun emptyListIsAnAnswerNotAMissingOne() {
        val s = feed(list(p("1", "a")), list())
        assertTrue(s.skills.isEmpty())
    }

    @Test fun promptItemUpsertsById() {
        val s = feed(list(p("1", "a"), p("2", "b")), item(p("1", "renamed", "x", body = "new")), item(p("3", "c")))
        assertEquals(listOf("b", "c", "renamed"), s.skills.map { it.name })
        assertEquals("new", s.skills.first { it.id == "1" }.body)
    }

    @Test fun loadingIsSetByTheRequestAndClearedByTheAnswer() {
        val asked = AppState().withSkillsRequested()
        assertTrue(asked.skillsLoading)
        assertFalse(feed(list(), from = asked).skillsLoading)
    }

    @Test fun daemonRefusalShowsInlineAndEndsLoadingThenClearsOnSuccess() {
        val refused = feed(Envelope(Envelope.ERROR, "skill_save refused: name is blank"), from = AppState().withSkillsRequested())
        assertEquals("skill_save refused: name is blank", refused.skillError)
        assertFalse(refused.skillsLoading)
        assertNull(feed(item(p("1", "a")), from = refused).skillError)
        // An unrelated refusal is not a prompt error.
        assertNull(feed(Envelope(Envelope.ERROR, "unknown pod")).skillError)
    }

    @Test fun searchMatchesNameOrTag() {
        val all = listOf(p("1", "Release notes", "writing"), p("2", "Bug triage", "ops", "release"), p("3", "Standup"))
        assertEquals(listOf("Release notes", "Bug triage"), filterSkills(all, "release").map { it.name })
        assertEquals(listOf("Bug triage"), filterSkills(all, "ops").map { it.name })
        assertEquals(3, filterSkills(all, "  ").size)
        assertTrue(filterSkills(all, "nomatch").isEmpty())
    }

    @Test fun searchAlsoMatchesDescription() {
        val all = listOf(p("1", "Alpha", desc = "reviews pull requests"), p("2", "Beta", desc = "writes changelogs"))
        assertEquals(listOf("Alpha"), filterSkills(all, "pull").map { it.name })
    }

    @Test fun descriptionIsOptionalOnTheWire() {
        val k = json.decodeFromString(SkillView.serializer(), """{"id":"1","name":"n","body":"b"}""")
        assertEquals("", k.description)
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
        assertEquals(Envelope.SKILL_LIST, WireRequests.skillList().type)
        assertEquals("", WireRequests.skillList().data)
        assertEquals(Envelope.SKILL_DELETE, WireRequests.skillDelete("id-1").type)
        assertEquals("id-1", WireRequests.skillDelete("id-1").data)
        val save = WireRequests.skillSave("n", "d", "b", listOf("t1", "t2"))
        assertEquals(Envelope.SKILL_SAVE, save.type)
        assertEquals("""{"name":"n","description":"d","body":"b","tags":["t1","t2"]}""", save.data)
    }
}
