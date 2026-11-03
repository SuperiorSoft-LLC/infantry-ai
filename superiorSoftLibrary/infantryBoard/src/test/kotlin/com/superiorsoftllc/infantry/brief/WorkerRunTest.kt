package com.superiorsoftllc.infantry.brief

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero leaf 5 (ticket 7162ac29, "worker run"): the WORKER HALF OF ONE STORY.
 *
 * Leaf 4 decided what a lead hands out ([LeadBriefComposer]); this file decides what a worker does
 * with it. The MVP's claim is that a worker can run one story on a small context -- the brief --
 * without ever being handed the project. So every assertion below is about exactly one of three
 * things:
 *
 *  - what the model actually READS: the prompt the injected call receives is the brief, field for
 *    field, and nothing around it (no goal, no name, no board, no sibling, no history);
 *  - what the LEAD gets back: the worker's text, tagged with the task id it belongs to, so a result
 *    is never an orphan;
 *  - what a worker must not do to the lead: a model that throws is REPORTED as a failed run, never
 *    propagated, never swallowed.
 *
 * The model is injected as a `(String) -> String`, so this test touches no CLI, no provider, no
 * daemon and no process. The seam is the whole point: the run's bookkeeping is what is under test
 * here, and a real provider is wired in later, behind the same function type.
 */
class WorkerRunTest {

    @Test
    fun theWorkerOnlyReadsTheBriefTheLeadHandedIt() {
        val task = story(id = "story-7", title = "Wire the project store")
        val leadProject = project(goal = "PROJECT-GOAL-MUST-NOT-LEAK")
        val brief = LeadBriefComposer.compose(
            BriefInput(
                ticket = task,
                project = leadProject,
                instruction = "Build the store over the Project DTO.",
                otherTickets = listOf(story(id = "story-8", title = "SIBLING-TITLE-MUST-NOT-LEAK")),
            ),
        )

        val prompts = mutableListOf<String>()
        val result = WorkerRun.run(brief) { prompt ->
            prompts += prompt
            "store wired"
        }

        assertEquals(1, prompts.size, "one story, one run, one prompt")
        val prompt = prompts.single()
        assertEquals(brief.prompt, prompt, "the worker reads the brief, verbatim and nothing else")

        for (field in listOf(brief.taskId, brief.projectId, brief.title, brief.status, brief.instruction)) {
            assertTrue(prompt.contains(field), "the brief must carry '$field'")
        }
        for (marker in listOf(
            "PROJECT-GOAL-MUST-NOT-LEAK",
            "Sprint-Zero MVP",
            "story-8",
            "SIBLING-TITLE-MUST-NOT-LEAK",
            "HISTORY-MUST-NOT-LEAK",
            "HISTORY",
        )) {
            assertFalse(prompt.contains(marker), "'$marker' is not the worker's context")
        }
        assertEquals(brief.taskId, result.taskId, "the answer comes back labelled with its task")
        assertEquals("store wired", result.output)
    }

    @Test
    fun aBriefWithNoInstructionSectionIsRunAsItStands() {
        val brief = LeadBriefComposer.compose(
            BriefInput(
                ticket = story(id = "story-7", title = "Wire the project store"),
                project = project(),
                instruction = " \n\t ",
            ),
        )

        var prompt = "unset"
        WorkerRun.run(brief) { prompt = it; "ok" }

        assertEquals(brief.prompt, prompt)
        assertFalse(prompt.contains("instruction"), "a blank instruction contributes no section")
        assertEquals(4, brief.fields.size, "taskId, projectId, title, status -- and no instruction")
    }

    @Test
    fun aModelThatThrowsComesBackAsAFailedRun() {
        val brief = brief()

        val result = WorkerRun.run(brief) { throw IllegalStateException("provider exploded") }

        assertFalse(result.succeeded, "a thrown call is a failed run, not a crash")
        assertEquals("", result.output, "a failed run carries no text it never got")
        assertTrue(
            result.error.orEmpty().contains("provider exploded"),
            "the failure says what went wrong, so the lead can act on it",
        )
        assertEquals(brief.taskId, result.taskId, "a failure is still attributed to its task")
    }

    @Test
    fun aModelThatAnswersLeavesNoFailureBehind() {
        val result = WorkerRun.run(brief()) { "done" }

        assertTrue(result.succeeded)
        assertNull(result.error, "a success carries no error")
    }

    private fun brief(): WorkerBrief = LeadBriefComposer.compose(
        BriefInput(
            ticket = story(id = "story-7", title = "Wire the project store"),
            project = project(),
            instruction = "Build the store over the Project DTO.",
        ),
    )

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
