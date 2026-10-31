package com.superiorsoftllc.agenthq.brief

import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.ContextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero leaf 4 (ticket 1d9ccb79): the LEAD BRIEF COMPOSER.
 *
 * A lead holds one [Project] and its board, decomposes the work into stories, and hands each one to
 * a worker. This file is the seam where the lead's knowledge stops and the worker's context starts,
 * so every assertion below is about exactly one of three things:
 *
 *  - what a worker MUST get: the task-specific fields that make the story actionable on its own;
 *  - what the lead MUST keep: project-level context (the goal, the name, the board);
 *  - what nobody outside the task may ever see: the rest of the project's stories.
 *
 * The exclusion assertions are deliberately written against `WorkerBrief.toString()` as well as
 * against the prompt: a data class prints every field it carries, so a value that leaked into a
 * field fails even when the prompt that was rendered from it looks clean.
 */
class LeadBriefComposerTest {

    @Test
    fun aTicketGoesInAndTheBriefCarriesItsIdTitleAndStatus() {
        val ticket = Ticket(
            id = "tkt-42",
            title = "Retype the brief",
            projectId = "proj-1",
            status = TicketStatus.IN_PROGRESS,
            position = 3,
            createdAt = 1L,
            updatedAt = 2L,
        )

        val brief = LeadBriefComposer.compose(BriefInput(ticket = ticket, project = project()))

        assertEquals("tkt-42", brief.taskId, "the ticket's id is the task handle")
        assertEquals("Retype the brief", brief.title, "the ticket's title crosses verbatim")
        assertEquals(TicketStatus.IN_PROGRESS, brief.status, "the status comes from TicketStatus")
    }

    @Test
    fun theWorkerBriefCarriesEveryTaskSpecificField() {
        val story = story(id = "story-7", title = "  Wire the project store  ", status = TicketStatus.TODO)
        val instruction = "  Build the store over the Project DTO.\n"

        val brief = LeadBriefComposer.compose(
            BriefInput(ticket = story, project = project(), instruction = instruction),
        )

        assertEquals("story-7", brief.taskId, "the story id is the task's stable handle")
        assertEquals("proj-1", brief.projectId, "the join key the worker must record its result against")
        assertEquals("  Wire the project store  ", brief.title, "the title is verbatim, whitespace included")
        assertEquals(TicketStatus.TODO, brief.status, "the column the task starts in")
        assertEquals(instruction, brief.instruction, "the lead's words are verbatim too")

        assertEquals(
            listOf("story-7", "proj-1", brief.title, TicketStatus.TODO, instruction).joinToString("\n\n"),
            brief.prompt,
            "the prompt is every field, in order, verbatim",
        )
        for (value in listOf(brief.taskId, brief.projectId, brief.title, brief.status, brief.instruction)) {
            assertTrue(brief.prompt.contains(value), "the prompt must carry '$value'")
        }
    }

    @Test
    fun theBriefCountsEachFieldInPromptOrderAndDropsABlankInstruction() {
        val story = story(id = "story-7", title = "Wire the project store")
        val instruction = "Build the store over the Project DTO."

        val withInstruction = LeadBriefComposer.compose(
            BriefInput(ticket = story, project = project(), instruction = instruction),
        )
        assertEquals(
            listOf(
                ContextSource(source = "taskId", chars = story.id.length),
                ContextSource(source = "projectId", chars = story.projectId!!.length),
                ContextSource(source = "title", chars = story.title.length),
                ContextSource(source = "status", chars = TicketStatus.TODO.length),
                ContextSource(source = "instruction", chars = instruction.length),
            ),
            withInstruction.fields,
        )

        val blank = LeadBriefComposer.compose(
            BriefInput(ticket = story, project = project(), instruction = " \n\t "),
        )
        assertEquals(
            listOf("story-7", "proj-1", story.title, TicketStatus.TODO).joinToString("\n\n"),
            blank.prompt,
            "a blank instruction contributes no section at all",
        )
        assertEquals(
            listOf("taskId", "projectId", "title", "status"),
            blank.fields.map { it.source },
        )
        assertEquals("", blank.instruction)
    }

    @Test
    fun projectLevelContextStaysWithTheLead() {
        val goal = "PROJECT-GOAL-MUST-NOT-LEAK"
        val name = "PROJECT-NAME-MUST-NOT-LEAK"
        val leadProject = project(goal = goal, name = name)

        val brief = LeadBriefComposer.compose(
            BriefInput(ticket = story(), project = leadProject, instruction = "Build the store."),
        )

        assertFalse(brief.prompt.contains(goal), "the goal is the lead's to reason over, not the worker's")
        assertFalse(brief.prompt.contains(name), "the project name is project-level context")
        assertFalse(brief.toString().contains(goal), "the goal must not reach a field either")
        assertFalse(brief.toString().contains(name), "the name must not reach a field either")
        assertFalse(
            brief.fields.map { it.source }.any { it == "goal" || it == "name" || it == "storyIds" },
            "the accounting must not name a project-level field",
        )
    }

    @Test
    fun unrelatedStoriesNeverReachTheWorker() {
        val siblings = listOf(
            story(id = "story-8", title = "SIBLING-TITLE-MUST-NOT-LEAK", status = TicketStatus.IN_PROGRESS),
            story(id = "story-9", title = "ANOTHER-SIBLING-MUST-NOT-LEAK", status = TicketStatus.DONE),
        )

        val brief = LeadBriefComposer.compose(
            BriefInput(ticket = story(id = "story-7"), project = project(), otherTickets = siblings),
        )

        for (marker in listOf("story-8", "story-9", "SIBLING-TITLE-MUST-NOT-LEAK", "ANOTHER-SIBLING-MUST-NOT-LEAK")) {
            assertFalse(brief.prompt.contains(marker), "'$marker' is another story's business")
            assertFalse(brief.toString().contains(marker), "'$marker' must not reach a field either")
        }
        assertEquals(TicketStatus.TODO, brief.status, "a sibling moving on must not move this task")
    }

    @Test
    fun aSiblingWhoseTitleContainsTheTaskTitleStillLeaksNothing() {
        val task = story(id = "story-7", title = "Wire the project store")
        val collision = story(id = "story-8", title = "Wire the project store AND the story board")

        val brief = LeadBriefComposer.compose(
            BriefInput(ticket = task, project = project(), otherTickets = listOf(collision)),
        )

        assertEquals("Wire the project store", brief.title, "the task's own title, not the longer one")
        assertFalse(brief.prompt.contains("AND the story board"))
        assertFalse(brief.toString().contains("story-8"))
    }

    @Test
    fun aBriefIsByteIdenticalWithOrWithoutTheLeadsContext() {
        val task = story(id = "story-7", title = "Wire the project store")
        val rich = BriefInput(
            ticket = task,
            project = project(goal = "Prove the cost thesis"),
            instruction = "Build the store over the Project DTO.",
            otherTickets = listOf(story(id = "story-8", title = "Board listing")),
        )
        val bare = BriefInput(
            ticket = task,
            project = project(goal = ""),
            instruction = "Build the store over the Project DTO.",
            otherTickets = emptyList(),
        )

        assertEquals(LeadBriefComposer.compose(bare), LeadBriefComposer.compose(rich))
    }

    @Test
    fun anOffVocabularyStatusIsRefusedNotSilentlyNormalised() {
        val story = story(id = "story-7", status = "blocked")

        val failure = assertFailsWith<IllegalArgumentException> {
            LeadBriefComposer.compose(BriefInput(ticket = story, project = project()))
        }

        assertTrue(failure.message!!.contains("blocked"), "the refusal names the offending status")
        assertTrue(failure.message!!.contains("in_progress"), "and the vocabulary it expected")
    }

    @Test
    fun aStoryFromAnotherProjectIsRefused() {
        val story = story(id = "story-7", projectId = "proj-other")

        val failure = assertFailsWith<IllegalArgumentException> {
            LeadBriefComposer.compose(BriefInput(ticket = story, project = project(id = "proj-1")))
        }

        assertTrue(failure.message!!.contains("proj-other"))
        assertTrue(failure.message!!.contains("proj-1"))
    }

    @Test
    fun aTaskWithNoIdentityOfItsOwnIsRefused() {
        for (title in listOf("", "   ", "\n")) {
            assertFailsWith<IllegalArgumentException>("title '$title'") {
                LeadBriefComposer.compose(BriefInput(ticket = story(id = "story-7", title = title), project = project()))
            }
        }
        assertFailsWith<IllegalArgumentException>("id ''") {
            LeadBriefComposer.compose(BriefInput(ticket = story(id = "  ", title = "Wire the project store"), project = project()))
        }
    }

    private fun project(
        id: String = "proj-1",
        name: String = "Sprint-Zero MVP",
        goal: String = "Prove the cost thesis",
    ) = Project(id = id, name = name, goal = goal)

    private fun story(
        id: String = "story-7",
        projectId: String = "proj-1",
        title: String = "Wire the project store",
        status: String = TicketStatus.TODO,
    ) = Ticket(position = 0, createdAt = 0L, updatedAt = 0L, id = id, projectId = projectId, title = title, status = status)
}
