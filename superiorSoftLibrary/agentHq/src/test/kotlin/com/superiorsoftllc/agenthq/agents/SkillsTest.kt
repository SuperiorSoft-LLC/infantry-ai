package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContextComposer
import com.superiorsoftllc.agenthq.protocol.SkillView
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.settings.ProjectSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-K15 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D10, owner decision 2026-10-18): the pure `skills` section. Attached skills
 * in full inside 24 000 chars, never cut mid-body; every other visible skill as one `name: description` line inside
 * 40 lines / 6 000 chars; `attachedOnly` hidden unless attached; a deleted attached id prints `(deleted)`; nothing to say
 * = no section, so the first turn is today's text.
 */
class SkillsTest {

    @Test
    fun noSkillsAtAllGivesNoSectionSoTheFirstTurnIsTodaysText() {
        assertNull(renderSkills(emptyList(), emptyList()))
        assertNull(renderSkills(emptyList(), listOf(skill("s1", "hidden", attachedOnly = true))), "only attachedOnly skills, none attached")
        val instance = RoleInstance("backend", "backend", "persona")
        assertEquals("persona\n\n${identityLine("backend", "backend")}\n\ntask", firstTurn(instance, "backend", "backend", "task", null, SkillsSource.NONE.section(instance)))
    }

    @Test
    fun attachedSkillsGoInFullThenTheIndexOfTheRestByName() {
        val library = listOf(
            skill("s1", "tdd", "Use when writing code.", "RED first.\nThen GREEN."),
            skill("s2", "zeta", "Use when z."),
            skill("s3", "alpha", "Use when a.\nTwo lines."),
        )
        val text = renderSkills(listOf("s1"), library)!!
        assertEquals(
            listOf(
                SKILLS_HEADER,
                "Attached to you (always available; follow a skill whenever its description applies to your work):",
                "### Skill: tdd\nUse when writing code.\nRED first.\nThen GREEN.",
                "Other skills in the Infantry AI daemon, one `name: description` line each. When a description matches your " +
                    "work, run `agenthq skill show <name>` and follow it:",
                "- alpha: Use when a. Two lines.",
                "- zeta: Use when z.",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun attachedOnlyIsHiddenUnlessAttached() {
        val library = listOf(skill("s1", "secret", "Use when secret.", "SECRET BODY", attachedOnly = true), skill("s2", "open", "Use when open."))
        val unattached = renderSkills(emptyList(), library)!!
        assertFalse("secret" in unattached, unattached)
        assertTrue("- open: Use when open." in unattached)
        val attached = renderSkills(listOf("s1"), library)!!
        assertTrue("### Skill: secret\nUse when secret.\nSECRET BODY" in attached, attached)
    }

    @Test
    fun aDeletedAttachedSkillPrintsTheDeletedLine() {
        val text = renderSkills(listOf("gone-id", "s1"), listOf(skill("s1", "tdd", body = "B")))!!
        assertTrue("\n- gone-id (deleted)" in text, text)
        assertTrue("### Skill: tdd\nB" in text)
    }

    @Test
    fun aBodyThatDoesNotFitDegradesWholeToItsLineNeverCutAndSmallerOnesStillFit() {
        val big = "B".repeat(15_000)
        val bigger = "C".repeat(15_000)
        val small = "small body"
        val library = listOf(skill("s1", "one", "Use when 1.", big), skill("s2", "two", "Use when 2.", bigger), skill("s3", "three", "Use when 3.", small))
        val text = renderSkills(listOf("s1", "s2", "s3"), library)!!
        assertTrue("### Skill: one\nUse when 1.\n$big" in text, "the first one fits whole")
        assertFalse("C".repeat(10) in text, "the second is never cut: none of its body is inlined")
        assertTrue("- two: Use when 2. (attached to you, too long to inline: run `agenthq skill show two`)" in text, text.takeLast(800))
        assertTrue("### Skill: three\nUse when 3.\n$small" in text, "a later, smaller one still fits")
        // The owner's ceiling: 24 000 chars of bodies plus a 6 000-char index, plus the fixed wording.
        assertTrue(text.length <= 24_000 + 6_000 + FIXED_WORDING, "section is ${text.length} chars")
    }

    @Test
    fun theBodyBudgetIsAHardCeilingOverManyAttachedSkills() {
        val library = (1..40).map { skill("s$it", "skill-$it", "Use when $it.", "x".repeat(1_000 + it)) }
        val text = renderSkills(library.map { it.id }, library)!!
        val inlinedBlocks = text.split("\n").filter { it.startsWith("### Skill:") }.size
        val inlinedChars = library.take(inlinedBlocks).sumOf { "### Skill: ${it.name}\n${it.description}\n${it.body}".length + 1 }
        assertTrue(inlinedChars <= 24_000, "inlined $inlinedChars chars")
        assertTrue(inlinedBlocks in 1 until 40, "some degrade: $inlinedBlocks inlined")
        assertTrue(text.length <= 24_000 + 6_000 + FIXED_WORDING, "section is ${text.length} chars")
        // A smaller budget degrades more, still in order.
        val tight = renderSkills(library.map { it.id }, library, SkillBudgets(bodyChars = 3_000))!!
        assertEquals(2, tight.split("\n").count { it.startsWith("### Skill:") })
    }

    @Test
    fun theIndexIsCappedAt40LinesThenNMore() {
        val library = (1..55).map { skill("s$it", "skill-%02d".format(it), "Use when $it.") }
        val lines = renderSkills(emptyList(), library)!!.split("\n")
        val index = lines.filter { it.startsWith("- skill-") }
        assertEquals(40, index.size)
        assertEquals("- skill-40: Use when 40.", index.last())
        assertEquals("- 15 more: `agenthq skill list`", lines.last())
    }

    @Test
    fun theIndexIsCappedAt6000CharsNeverCuttingALine() {
        val description = "Use when " + "d".repeat(480)
        val library = (1..30).map { skill("s$it", "skill-%02d".format(it), description) }
        val text = renderSkills(emptyList(), library)!!
        val index = text.split("\n").filter { it.startsWith("- skill-") }
        assertTrue(index.all { it == "- ${it.substring(2, 10)}: $description" }, "no line is cut")
        assertTrue(index.sumOf { it.length + 1 } <= 6_000, "index is ${index.sumOf { it.length + 1 }} chars")
        assertEquals(11, index.size)
        assertTrue(text.endsWith("- 19 more: `agenthq skill list`"), text.takeLast(200))
    }

    @Test
    fun includeFalseOrNoInstanceGivesNoSection() {
        val library = listOf(skill("s1", "tdd", body = "B"), skill("s2", "other"))
        val instance = RoleInstance("backend", "backend", null, skillIds = listOf("s1"))
        val source = SkillsSource(library = { library }, includeSkillsContext = { null })
        assertTrue(source.section(instance)!!.contains("### Skill: tdd"))
        assertNull(source.section(instance, include = false), "the ticket's switch")
        assertNull(SkillsSource(library = { library }, includeSkillsContext = { false }).section(instance), "the project's switch")
        assertTrue(SkillsSource(library = { library }, includeSkillsContext = { false }).section(instance, include = true) != null, "the ticket's switch wins")
        assertNull(source.section(null as RoleInstance?), "an anonymous spawn has no instance, so no skills (K-D11)")
    }

    @Test
    fun skillsRenderAfterKnowledgeAndBeforeTheTaskOnFirstTurn() {
        val instance = RoleInstance("backend", "backend", "persona", skillIds = listOf("s1"))
        val skills = renderSkills(instance.skillIds, listOf(skill("s1", "tdd", body = "B")))
        val turn = firstTurn(instance, "backend", "backend", "the task", "KNOWLEDGE", skills)
        assertEquals("persona\n\n${identityLine("backend", "backend")}\n\nKNOWLEDGE\n\n$skills\n\nthe task", turn)
    }

    @Test
    fun theTicketComposerAddsTheSkillsSourceAfterKnowledgeAndHonoursTheTicketSwitch() {
        val library = listOf(skill("s1", "tdd", body = "B"))
        val composer = TicketContextComposer(
            NoSettings,
            knowledge = KnowledgeCounter { KnowledgeCounts(1, 0, 0) }.let { c -> com.superiorsoftllc.agenthq.context.KnowledgeSection { c.section(it) } },
            skills = SkillsSource(library = { library }),
        )
        val input = ContextInput(
            instructions = "persona", title = "T", description = "D", links = emptyList(), includeLinkedOverride = null,
            ticketId = "t1", projectId = "p1", role = "backend", agentId = "backend", skillIds = listOf("s1"),
        )
        val composed = composer.compose(input)
        assertEquals(listOf("agenthq", "team", "instructions", "identity", "knowledge", "skills", "ticket"), composed.sources.map { it.source })
        val expected = renderSkills(listOf("s1"), library)!!
        assertEquals(expected.length, composed.sources.single { it.source == "skills" }.chars)
        assertTrue(composed.prompt.contains("PROJECT KNOWLEDGE") && composed.prompt.indexOf(expected) > composed.prompt.indexOf("PROJECT KNOWLEDGE"))
        assertTrue(composed.prompt.endsWith("$expected\n\nT\n\nD"))

        val off = composer.compose(input.copy(includeSkillsOverride = false))
        assertTrue(off.sources.none { it.source == "skills" }, "include = false: no section")
        assertTrue(composer.compose(input.copy(skillIds = null)).sources.none { it.source == "skills" }, "no instance: no section")
    }

    private object NoSettings : ProjectSettings {
        override fun all() = kotlinx.serialization.json.JsonObject(emptyMap())
        override fun set(key: String, value: kotlinx.serialization.json.JsonElement) = error("unused")
        override fun includeLinkedTasksContext(): Boolean? = null
    }

    private fun skill(id: String, name: String, description: String = "", body: String = "body", attachedOnly: Boolean = false) =
        SkillView(id = id, name = name, description = description, body = body, createdAt = 1L, updatedAt = 1L, attachedOnly = attachedOnly)

    private companion object {
        /** The header, the two section lines and the `N more` line: everything that is not skill text. */
        const val FIXED_WORDING = 600
    }
}
