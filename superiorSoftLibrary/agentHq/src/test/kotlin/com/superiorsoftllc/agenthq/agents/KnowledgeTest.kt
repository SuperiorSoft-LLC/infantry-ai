package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContextComposer
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.TraitSnapshot
import com.superiorsoftllc.agenthq.settings.ProjectSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P10-K9 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md section 4.1): the pure knowledge renderer and its place in a first turn. */
class KnowledgeTest {

    @Test
    fun noProjectGivesNoSection() {
        assertNull(renderKnowledge(null, KnowledgeCounts(3, 2, 1)))
        assertNull(renderKnowledge("  ", KnowledgeCounts(3, 2, 1)))
        assertNull(renderKnowledge(null, KnowledgeCounts(0, 0, 0)))
        assertNull(KnowledgeCounter { KnowledgeCounts(3, 2, 1) }.section(null), "no project is never even counted")
        assertNull(KnowledgeCounter.NONE.section("p1"), "no knowledge base wired: no section")
    }

    @Test
    fun anEmptyBaseGetsTheShortFormNamingTheWriteVerbs() {
        assertEquals(
            "PROJECT KNOWLEDGE. Project p1 has an empty knowledge base in the Infantry AI daemon. Record what a later session " +
                "must not re-derive: `agenthq decision add` for a choice, `agenthq issue open|attempt|fix` for a bug, " +
                "`agenthq memory save` for anything else.",
            renderKnowledge(" p1 ", KnowledgeCounts(0, 0, 0)),
        )
    }

    @Test
    fun anyKnowledgeGetsTheFullFormWithTheLiveCounts() {
        assertEquals(FULL, renderKnowledge("p1", KnowledgeCounts(4, 2, 1)))
        // One count alone is enough for the full form.
        assertEquals(true, renderKnowledge("p1", KnowledgeCounts(0, 0, 1))!!.startsWith("PROJECT KNOWLEDGE (this applies"))
    }

    @Test
    fun theSectionGoesAfterTheTraitsAndBeforeTheTask() {
        val instance = RoleInstance("backend", "backend", "PERSONA", listOf(TraitSnapshot("tdd", 1, "TDD", "red first")))
        val identity = identityLine("backend", "backend")!!
        val traits = renderTraits(instance.traits)!!
        assertEquals(
            "PERSONA\n\n$identity\n\n$traits\n\n$FULL\n\nthe task",
            firstTurn(instance, "backend", "backend", "the task", renderKnowledge("p1", KnowledgeCounts(4, 2, 1))),
        )
        assertEquals("PERSONA\n\n$identity\n\n$traits\n\nthe task", firstTurn(instance, "backend", "backend", "the task"))
    }

    @Test
    fun theComposerAddsTheKnowledgeSourceAfterTraitsAndBeforeTheTicket() {
        val counted = mutableListOf<String>()
        val composer = TicketContextComposer(NoSettings, KnowledgeCounter { counted += it; KnowledgeCounts(4, 2, 1) }.let { c -> com.superiorsoftllc.agenthq.context.KnowledgeSection { c.section(it) } })
        val input = ContextInput(
            instructions = "PERSONA", title = "T", description = "D", links = emptyList(), includeLinkedOverride = false,
            ticketId = "T-1", projectId = "p1", role = "backend", agentId = "backend",
            traits = listOf(TraitSnapshot("tdd", 1, "TDD", "red first")),
        )
        val composed = composer.compose(input)
        assertEquals(listOf("agenthq", "team", "instructions", "identity", "traits", "knowledge", "ticket"), composed.sources.map { it.source })
        assertEquals(FULL.length, composed.sources.single { it.source == "knowledge" }.chars)
        assertEquals(true, composed.prompt.endsWith("\n\n$FULL\n\nT\n\nD"), composed.prompt.takeLast(300))
        assertEquals(listOf("p1"), counted)

        val none = composer.compose(input.copy(projectId = null))
        assertEquals(false, none.sources.any { it.source == "knowledge" }, "a ticket with no project gets no knowledge source")
    }

    /** BUG-G2e: with a digest, the full form carries the knowledge and drops the fetch-it-first pointer. */
    @Test
    fun aDigestIsCarriedInTheFirstTurnInsteadOfThePointer() {
        val text = renderKnowledge(
            "p1",
            KnowledgeCounts(1, 1, 1, KnowledgeDigest(listOf("D2 use wal"), listOf("I1 flaky (a.kt)"), listOf("arch: how"))),
        )!!
        assertEquals(
            // BUG-G2e-1: the authority label is read before the first entry.
            "$DIGEST_AUTHORITY\n$KNOWLEDGE_DIGEST_HEADING\nACTIVE DECISIONS\nD2 use wal\nOPEN ISSUES\nI1 flaky (a.kt)\nNOTES (index)\narch: how\n",
            text.substringAfter("start.\n").substringBefore("Before you decide"),
        )
        assertEquals(false, text.contains("run `agenthq knowledge`: it lists"))
        assertEquals(true, text.contains("`agenthq decision show D<n>`"), "the retrieval verbs stay")
        assertEquals(true, text.contains("`agenthq decision add`"), "the record verbs stay")
    }

    /** BUG-G2e: the line builders the CLI and the daemon share. */
    @Test
    fun theLineBuildersDropSupersededDecisionsAndFixedIssues() {
        val decisions = listOf(
            com.superiorsoftllc.agenthq.decisions.Decision("p1", 2, "D2", "use wal", supersedes = 1, createdAt = 2),
            com.superiorsoftllc.agenthq.decisions.Decision("p1", 1, "D1", "use sqlite", supersededBy = 2, createdAt = 1),
        )
        val issues = listOf(
            com.superiorsoftllc.agenthq.issues.Issue("p1", 1, "I1", "flaky", "a.kt", status = "open", createdAt = 1),
            com.superiorsoftllc.agenthq.issues.Issue("p1", 2, "I2", "slow", status = "fixed", createdAt = 2),
        )
        assertEquals(listOf("D2 use wal"), KnowledgeLines.decisions(decisions))
        assertEquals(listOf("I1 flaky (a.kt)"), KnowledgeLines.issues(issues))
        val notes = listOf(
            com.superiorsoftllc.agenthq.memory.MemoryIndexEntry("b", "two", "arch"),
            com.superiorsoftllc.agenthq.memory.MemoryIndexEntry("a", "one"),
        )
        assertEquals(listOf("a: one", "arch/b: two"), KnowledgeLines.notes(notes))
    }

    /** BUG-G2e caps: per section, then the whole digest in characters, each ending with a `N more` line. */
    @Test
    fun theDigestIsCappedPerSectionAndInCharacters() {
        val many = digestLines(KnowledgeDigest((1..35).map { "D$it d" }, emptyList(), emptyList()))
        assertEquals(DIGEST_DECISIONS_CAP, many.count { it.startsWith("D") })
        assertEquals("5 more: run `agenthq knowledge`", many[DIGEST_DECISIONS_CAP + 1])
        assertEquals(listOf("OPEN ISSUES", "(none)", "NOTES (index)", "(none)"), many.takeLast(4))

        val long = "x".repeat(200)
        val big = digestLines(KnowledgeDigest((1..30).map { "D$it $long" }, (1..20).map { "I$it $long" }, (1..40).map { "n$it: $long" }))
        assertTrue(big.sumOf { it.length + 1 } <= DIGEST_CHARS_CAP, "the whole digest stays under the char cap")
        assertEquals("D1 $long", big[1], "decisions survive the char cap first")
        val shown = big.count { it.endsWith(long) }
        assertEquals("${90 - shown} more: run `agenthq knowledge`", big.last())
    }

    private object NoSettings : ProjectSettings {
        override fun all() = kotlinx.serialization.json.JsonObject(emptyMap())
        override fun set(key: String, value: kotlinx.serialization.json.JsonElement) = error("unused")
        override fun includeLinkedTasksContext(): Boolean? = null
    }

    private companion object {
        val FULL =
            "PROJECT KNOWLEDGE (this applies to your whole session, including after a context compaction). Project p1 keeps a " +
                "shared knowledge base in the Infantry AI daemon: 4 notes, 2 active decisions, 1 open issues.\n" +
                "Before you explore the code, and before you decide anything an earlier session may already have decided, run " +
                "`agenthq knowledge`: it lists the active decisions, the open issues and the note index. Then walk down: " +
                "`agenthq memory index --folder <folder>`, `agenthq memory search <term>`, `agenthq memory show <name>`, " +
                "`agenthq decision show D<n>`, and `agenthq issue precheck <path>` before you change a file. An index line is " +
                "a retrieval cue, not the content: open the note before you rely on it.\n" +
                "Record through the same CLI, never in files: `agenthq decision add` (decisions are never edited; a new one " +
                "replaces an old one with `--supersedes D<n>`), `agenthq issue open|attempt|fix`, `agenthq memory save` " +
                "(`--append` adds a section without reading the note, `--edit --find \"<exact passage>\"` changes a passage " +
                "that occurs exactly once).\n" +
                "If a command fails, the knowledge base is NOT empty: report the command and its answer, then go on."
    }
}
