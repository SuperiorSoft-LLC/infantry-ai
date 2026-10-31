package com.superiorsoftllc.agenthq.ledger

import com.superiorsoftllc.agenthq.brief.BriefInput
import com.superiorsoftllc.agenthq.brief.from
import com.superiorsoftllc.agenthq.brief.LeadBriefComposer
import com.superiorsoftllc.agenthq.brief.WorkerBrief
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MVP Sprint-Zero leaf 6.1: the LEDGER's `Asked` half, derived from a [WorkerBrief].
 *
 * The ledger exists to answer one question the MVP has to be able to answer about every run: what was
 * actually ASKED, against what was DONE. So the first half -- what was asked -- is not an independent
 * record a human writes by hand; it is the [WorkerBrief] the composer already produced, read back out
 * through [Asked.from]. That makes a brief the single source of truth for both sides: the worker
 * receives what the ledger later holds it to.
 *
 * Every assertion below is therefore about the copy being FAITHFUL: the four fields a worker could act
 * on (`taskId`, `projectId`, `title`, `instruction`) arrive unchanged, and the cost the brief actually
 * carried (`promptChars`) is the composed prompt's length, measured rather than estimated. A field the
 * worker saw but the ledger dropped would let a run evade the ledger, and a field the ledger invented
 * would accuse a run of being asked something it never was. Neither is detectable after the fact, so
 * neither is allowed.
 */
class AskedFromBriefTest {

    @Test
    fun askedIsTheBriefItsTaskSpecificFieldsVerbatim() {
        val instruction = "Build the store over the Project DTO."

        val asked = Asked.from(brief(instruction = instruction))

        assertEquals("story-7", asked.taskId, "the task's stable handle, which a result is matched against")
        assertEquals("proj-1", asked.projectId, "the join key the result is recorded against")
        assertEquals("Wire the project store", asked.title, "the task, verbatim")
        assertEquals(instruction, asked.instruction, "the lead's words, verbatim")
    }

    @Test
    fun promptCharsIsTheComposedPromptsLength() {
        val brief = brief(instruction = "Build the store over the Project DTO.")

        val asked = Asked.from(brief)

        assertEquals(brief.prompt.length, asked.promptChars, "the ask's cost is the brief's, measured not estimated")
    }

    @Test
    fun anEmptyInstructionStillCountsItsCharacters() {
        val brief = brief(instruction = "")

        val asked = Asked.from(brief)

        assertEquals("", asked.instruction, "a blank instruction is dropped by the composer, not by the ledger")
        assertEquals(brief.prompt.length, asked.promptChars)
    }

    @Test
    fun theLedgersCopyCannotDriftFromTheBriefItWasBuiltFrom() {
        val first = Asked.from(brief(instruction = "Build the store over the Project DTO."))

        val second = Asked.from(brief(instruction = "Build the store over the Project DTO."))

        assertEquals(first, second, "two asks built from the same brief are the same ask")
    }

    private fun brief(instruction: String, id: String = "story-7"): WorkerBrief = LeadBriefComposer.compose(
        BriefInput(
            ticket = Ticket(position = 0, createdAt = 0L, updatedAt = 0L, 
                id = id,
                projectId = "proj-1",
                title = "Wire the project store",
                status = TicketStatus.TODO,
            ),
            project = Project(
                id = "proj-1",
                name = "Sprint-Zero MVP",
                goal = "Prove the cost thesis",
            ),
            instruction = instruction,
        ),
    )
}
