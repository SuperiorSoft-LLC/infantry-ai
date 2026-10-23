package com.superiorsoftllc.agenthq.context

import com.superiorsoftllc.agenthq.protocol.ContextSwitch
import com.superiorsoftllc.agenthq.session.ContextSource
import com.superiorsoftllc.agenthq.settings.ProjectSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TicketContextComposerTest {
    private class FixedProjectSettings(private val linkedTasks: Boolean?) : ProjectSettings {
        override fun all() = JsonObject(
            mapOf(KEY to JsonPrimitive(linkedTasks ?: true)),
        )

        override fun set(key: String, value: JsonElement): JsonObject = error("unused")

        override fun includeLinkedTasksContext(): Boolean? = linkedTasks
    }

    @Test
    fun effectivePrefersTicketThenProjectThenTheTrueDefault() {
        val projectOff = TicketContextComposer(FixedProjectSettings(false))

        assertEquals(ContextSwitch(false, "project"), projectOff.effective(null).includeLinkedTasksContext)
        assertEquals(ContextSwitch(true, "ticket"), projectOff.effective(true).includeLinkedTasksContext)
        assertEquals(ContextSwitch(false, "ticket"), projectOff.effective(false).includeLinkedTasksContext)

        val unset = TicketContextComposer(FixedProjectSettings(null))
        assertEquals(ContextSwitch(true, "default"), unset.effective(null).includeLinkedTasksContext)
    }

    @Test
    fun composeKeepsTheVerbatimCoreAndOrdersInstructionsCoreAndLinkLines() {
        val composer = TicketContextComposer(FixedProjectSettings(false))
        val instructions = "  Profile instructions stay exact.\n"
        val title = "  Keep this title verbatim  "
        val description = "First line\nsecond line\n"
        val links = listOf(
            LinkLine(type = "relates_to", ticketId = "T-21", title = "Related\ndesign", status = "done"),
            LinkLine(type = "child_of", ticketId = "T-08", title = "Parent epic", status = "pending"),
        )
        val ticketSection = title + "\n\n" + description
        val linkedSection = listOf(
            "T-21 | Related design | done | relates_to",
            "T-08 | Parent epic | pending | child_of",
        ).joinToString("\n")
        val linkedBody = "LINKED-BODY-MUST-NOT-APPEAR"
        val epicBody = "E".repeat(20_000)

        val result = composer.compose(
            ContextInput(
                instructions = instructions,
                title = title,
                description = description,
                links = links,
                includeLinkedOverride = true,
                ticketId = "T-21",
                projectId = "test-project",
            ),
        )

        val expectedPrompt = "$instructions\n\n$ticketSection\n\n$linkedSection"
        val agenthqSection = "You are running inside AgentHQ. Ticket: T-21. Project: test-project. AgentHQ tickets, agents, mail and project knowledge live in the AgentHQ daemon and are reached with the `agenthq` CLI, never through files. See superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md.\n" + TicketContextComposer.ticketMoveLine("T-21") + "\n" + com.superiorsoftllc.agenthq.context.TicketContextComposer.MCP_LINE
        assertEquals("$agenthqSection\n\n$teamSection\n\n$expectedPrompt", result.prompt)
        assertEquals(2, result.prompt.substringAfter("\n\n$agenthqSection").substringAfterLast("\n\n").lines().size, "one line per link, in input order")
        assertFalse(result.prompt.contains(linkedBody))
        assertFalse(result.prompt.contains(epicBody))
        assertEquals(
            listOf(
                ContextSource(source = "agenthq", chars = agenthqSection.length),
                ContextSource(source = "team", chars = teamSection.length),
                ContextSource(source = "instructions", chars = instructions.length),
                ContextSource(source = "ticket", chars = ticketSection.length),
                ContextSource(source = "linkedTickets", chars = linkedSection.length),
            ),
            result.sources,
        )
    }

    @Test
    fun composeOmitsBlankInstructionsAndDisabledLinkedTicketsSource() {
        val composer = TicketContextComposer(FixedProjectSettings(true))
        val title = "Ticket title"
        val description = "Verbatim description"
        val ticketSection = title + "\n\n" + description

        val result = composer.compose(
            ContextInput(
                instructions = " \n\t ",
                title = title,
                description = description,
                links = listOf(LinkLine("blocks", "T-2", "Blocked ticket", "todo")),
                includeLinkedOverride = false,
                ticketId = title,
                projectId = "test-project",
            ),
        )

        val expectedTicketSection = ticketSection
        val agenthqSection = "You are running inside AgentHQ. Ticket: Ticket title. Project: test-project. AgentHQ tickets, agents, mail and project knowledge live in the AgentHQ daemon and are reached with the `agenthq` CLI, never through files. See superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md.\n" + TicketContextComposer.ticketMoveLine("Ticket title") + "\n" + com.superiorsoftllc.agenthq.context.TicketContextComposer.MCP_LINE
        assertEquals("$agenthqSection\n\n$teamSection\n\n$expectedTicketSection", result.prompt)
        assertEquals(
            listOf(
                ContextSource(source = "agenthq", chars = agenthqSection.length),
                ContextSource(source = "team", chars = teamSection.length),
                ContextSource(source = "ticket", chars = ticketSection.length),
            ),
            result.sources,
        )
    }

    @Test
    fun composeCarriesOneTeamBlockWithTheMailCommandsEveryTime() {
        val composer = TicketContextComposer(FixedProjectSettings(false))
        val input = ContextInput(
            instructions = null,
            title = "Title",
            description = "Body",
            links = emptyList(),
            includeLinkedOverride = null,
            ticketId = "T-5",
            projectId = "test-project",
        )

        val firstPrompt = composer.compose(input).prompt
        val secondPrompt = composer.compose(input).prompt

        for (prompt in listOf(firstPrompt, secondPrompt)) {
            assertEquals(1, prompt.occurrencesOf("agenthq agents --live"), "team block lists the live roster exactly once")
            assertEquals(3, prompt.occurrencesOf("agenthq send"), "team block documents send once, the finish line twice")
            assertEquals(1, prompt.occurrencesOf("agenthq inbox"), "team block documents inbox exactly once")
        }
    }

    /**
     * BUG-MAIL-MCP-2: since MAIL-MCP-1 an agent reads its mail through its own MCP server, so the team block names
     * agenthq_inbox / agenthq_read first and keeps `agenthq inbox` / `agenthq read` only as the fallback.
     */
    @Test
    fun teamBlockNamesTheMcpMailToolsAndKeepsTheCliAsTheFallback() {
        val composer = TicketContextComposer(FixedProjectSettings(false))
        val input = ContextInput(
            instructions = null,
            title = "Title",
            description = "Body",
            links = emptyList(),
            includeLinkedOverride = null,
            ticketId = "T-6",
            projectId = "test-project",
        )

        val team = composer.compose(input).prompt.substringAfter("Team (scoped to your project):")
        val mailLine = team.lines().single { it.contains("your mail") }

        assertTrue(mailLine.contains("agenthq_inbox"), "the mail line names the MCP inbox tool: $mailLine")
        assertTrue(mailLine.contains("agenthq_read"), "the mail line names the MCP read tool: $mailLine")
        assertTrue(mailLine.contains("fallback"), "the CLI is offered only as the fallback: $mailLine")
        assertTrue(
            mailLine.indexOf("agenthq_inbox") < mailLine.indexOf("agenthq inbox"),
            "the MCP tool comes before the CLI fallback: $mailLine",
        )
    }

    /** BUG-MAIL-MCP-2: AGENT-QUICKREF section 6 quotes the team block verbatim, so the doc cannot drift from the preamble. */
    @Test
    fun quickrefSectionSixQuotesTheTeamBlockVerbatim() {
        val section = findQuickref().substringAfter("## 6. Team block in the ticket preamble").substringBefore("\n## 7.")
        val quoted = section.substringAfter("```\n").substringBefore("\n```")

        assertEquals(TicketContextComposer.TEAM_BLOCK, quoted)
    }

    /** `docs/AGENT-QUICKREF.md`, found by walking up from the test's working directory, line endings normalized. */
    private fun findQuickref(): String {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "docs/AGENT-QUICKREF.md")
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            dir = dir.parentFile
        }
        error("docs/AGENT-QUICKREF.md not found above ${java.io.File("").absolutePath}")
    }

    /** PROMPT-5: every ticket start tells the agent, once, that `agenthq ask` asks the owner and waits. */
    @Test
    fun composeTellsTheAgentHowToAskTheOwnerAChoice() {
        val composer = TicketContextComposer(FixedProjectSettings(false))
        val input = ContextInput(
            instructions = null,
            title = "Title",
            description = "Body",
            links = emptyList(),
            includeLinkedOverride = null,
            ticketId = "T-9",
            projectId = "test-project",
        )
        val prompt = composer.compose(input).prompt
        assertEquals(1, prompt.occurrencesOf(ASK_LINE), "the ask line appears exactly once: $prompt")
        assertEquals(1, prompt.occurrencesOf("agenthq ask"), "agenthq ask is documented exactly once")
    }

    /** BUG-E2E-2: the first turn tells the agent how to close its own ticket, by its real id. */
    @Test
    fun composeTellsTheAgentToMoveItsTicketToDoneWhenTheWorkIsDone() {
        val prompt = TicketContextComposer(FixedProjectSettings(false)).compose(
            ContextInput(
                instructions = null,
                title = "Title",
                description = "Body",
                links = emptyList(),
                includeLinkedOverride = null,
                ticketId = "T-9",
                projectId = "test-project",
            ),
        ).prompt

        assertTrue(prompt.contains("When the work is done, run `agenthq ticket move T-9 done`."), prompt)
        assertTrue(prompt.contains("If you are blocked, leave the ticket in_progress"), prompt)
    }

    private fun String.occurrencesOf(needle: String): Int = split(needle).size - 1

    @Test
    fun composeOmitsEmptySources() {
        val composer = TicketContextComposer(FixedProjectSettings(null))
        val core = "Title\n\n"

        val result = composer.compose(
            ContextInput(
                instructions = null,
                title = "Title",
                description = "",
                links = emptyList(),
                includeLinkedOverride = null,
                ticketId = "Title",
                projectId = "test-project",
            ),
        )

        val coreSection = "Title\n\n"
        val agenthqSection = "You are running inside AgentHQ. Ticket: Title. Project: test-project. AgentHQ tickets, agents, mail and project knowledge live in the AgentHQ daemon and are reached with the `agenthq` CLI, never through files. See superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md.\n" + TicketContextComposer.ticketMoveLine("Title") + "\n" + com.superiorsoftllc.agenthq.context.TicketContextComposer.MCP_LINE
        assertEquals("$agenthqSection\n\n$teamSection\n\n$coreSection", result.prompt)
        assertEquals(
            listOf(
                ContextSource(source = "agenthq", chars = agenthqSection.length),
                ContextSource(source = "team", chars = teamSection.length),
                ContextSource(source = "ticket", chars = coreSection.length),
            ),
            result.sources,
        )
    }

    /** BUG-MAIL-2: a ticket started from a session names that session in its Team block; a UI start adds nothing. */
    @Test
    fun aStartFromASessionNamesTheStarterInTheTeamBlock() {
        val composer = TicketContextComposer(FixedProjectSettings(false))
        fun input(reportTo: ReportTo?) = ContextInput(
            instructions = null, title = "T", description = "D", links = emptyList(), includeLinkedOverride = null,
            ticketId = "ticket-1", projectId = "p2", reportTo = reportTo,
        )

        val started = composer.compose(input(ReportTo(name = "Project Manager", sessionId = "sess-a")))
        val expected = teamBlock + "\n- Report to: Project Manager (sess-a), who started this ticket. Mail it with " +
            "`agenthq send starter \"msg\"` (or `agenthq send sess-a \"msg\"`); it works across projects." + "\n" + TicketContextComposer.FINISH_LINE
        assertTrue(started.prompt.contains(expected), "the Team block names the starter: ${started.prompt}")
        assertEquals(expected.length, started.sources.single { it.source == "team" }.chars)

        val fromUi = composer.compose(input(null))
        assertFalse(fromUi.prompt.contains("Report to:"), "a UI start keeps today's Team block")
        assertEquals(teamSection.length, fromUi.sources.single { it.source == "team" }.chars)
    }

    /** Every harness gets the same end-of-work instruction: mail the starter with sha, changes, tests, problems. */
    @Test
    fun `every ticket brief ends its team block with the finish instruction`() {
        val composer = TicketContextComposer(FixedProjectSettings(false))
        for (reportTo in listOf(null, ReportTo(name = "PM", sessionId = "s1"))) {
            val prompt = composer.compose(
                ContextInput(
                    instructions = null, title = "T", description = "D", links = emptyList(), includeLinkedOverride = null,
                    ticketId = "t-1", projectId = "p", reportTo = reportTo,
                ),
            ).prompt
            assertTrue(prompt.contains(TicketContextComposer.FINISH_LINE), prompt)
            assertTrue(TicketContextComposer.FINISH_LINE.contains("commit sha"))
            assertTrue(TicketContextComposer.FINISH_LINE.contains("agenthq send starter"))
        }
    }

    private companion object {
        const val KEY = "context.includeLinkedTasksContext"

        // PROMPT-5: the one preamble line that tells every harness how to ask the owner a choice.
        const val ASK_LINE = "- To ask the owner a choice, run `agenthq ask --question \"...\" --option \"label|consequence\" --recommend <label> --reason \"...\"` " +
            "(repeat --option; --other, --default-after <min>). It returns a question id at once and does not wait: carry on with other work; the owner's pick arrives as mail (thread question:<id>)."

        // Must match TicketContextComposer's TEAM_BLOCK exactly (MAIL-5).
        val teamBlock = "Team (scoped to your project):\n" +
            "- agenthq agents --live: who is live in this project\n" +
            "- agenthq send <role|agentId> \"msg\": message a colleague (queued if they are busy)\n" +
            "- agenthq_inbox / agenthq_read <id> (your AgentHQ MCP tools): your mail; " +
            "fallback when they are not available: agenthq inbox / agenthq read <id>\n" +
            "- agenthq mail: every mail command (answer one with --reply-to <id>); agenthq --help: every command, grouped. " +
            "Start here: superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md, section 0\n" +
            ASK_LINE

        val teamSection = teamBlock + "\n" + TicketContextComposer.FINISH_LINE
    }
}
