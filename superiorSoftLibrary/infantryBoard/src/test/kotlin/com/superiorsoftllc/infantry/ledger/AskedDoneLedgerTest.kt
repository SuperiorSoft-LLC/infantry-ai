// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AskedDoneLedgerTest {
    @Test
    fun askedOnlyIsPending() {
        val ledger = InMemoryAskedDoneLedger()
        val asked = asked("task-1", "project-1")

        ledger.recordAsked(asked)

        assertEquals(LedgerEntry(asked, null, Verdict.PENDING), ledger.entry("task-1"))
    }

    @Test
    fun completedIsDelivered() {
        val ledger = InMemoryAskedDoneLedger()
        val asked = asked("task-1")
        val done = Done("task-1", Outcome.COMPLETED, "completed")
        ledger.recordAsked(asked)

        ledger.recordDone(done)

        assertEquals(LedgerEntry(asked, done, Verdict.DELIVERED), ledger.entry("task-1"))
    }

    @Test
    fun failedAndStoppedAreUndelivered() {
        for (outcome in listOf(Outcome.FAILED, Outcome.STOPPED)) {
            val ledger = InMemoryAskedDoneLedger()
            val asked = asked("task-$outcome")
            val done = Done(asked.taskId, outcome, outcome.name.lowercase())
            ledger.recordAsked(asked)

            ledger.recordDone(done)

            assertEquals(LedgerEntry(asked, done, Verdict.UNDELIVERED), ledger.entry(asked.taskId))
        }
    }

    @Test
    fun doneForNeverAskedTaskIsRefusedAndNotStored() {
        val ledger = InMemoryAskedDoneLedger()

        assertFailsWith<IllegalArgumentException> {
            ledger.recordDone(Done("unknown", Outcome.COMPLETED, "unexpected"))
        }
        assertEquals(null, ledger.entry("unknown"))
    }

    @Test
    fun aSecondDoneIsRefusedAndDoesNotReplaceTheFirst() {
        val ledger = InMemoryAskedDoneLedger()
        val asked = asked("task-1")
        val first = Done("task-1", Outcome.FAILED, "first")
        ledger.recordAsked(asked)
        ledger.recordDone(first)

        assertFailsWith<IllegalArgumentException> {
            ledger.recordDone(Done("task-1", Outcome.COMPLETED, "second"))
        }
        assertEquals(LedgerEntry(asked, first, Verdict.UNDELIVERED), ledger.entry("task-1"))
    }

    @Test
    fun duplicateAskedIsRefusedAndDoesNotReplaceTheFirst() {
        val ledger = InMemoryAskedDoneLedger()
        val first = asked("task-1", "project-1")
        ledger.recordAsked(first)

        assertFailsWith<IllegalArgumentException> {
            ledger.recordAsked(asked("task-1", "project-2"))
        }
        assertEquals(LedgerEntry(first, null, Verdict.PENDING), ledger.entry("task-1"))
    }

    @Test
    fun entriesAreFilteredByProjectAndRemainInAskedOrder() {
        val ledger = InMemoryAskedDoneLedger()
        val first = asked("task-1", "project-1")
        val otherProject = asked("task-2", "project-2")
        val third = asked("task-3", "project-1")
        ledger.recordAsked(first)
        ledger.recordAsked(otherProject)
        ledger.recordAsked(third)
        ledger.recordDone(Done("task-3", Outcome.COMPLETED, "done"))

        assertEquals(
            listOf(
                LedgerEntry(first, null, Verdict.PENDING),
                LedgerEntry(third, Done("task-3", Outcome.COMPLETED, "done"), Verdict.DELIVERED),
            ),
            ledger.entries("project-1"),
        )
        assertEquals(listOf(LedgerEntry(otherProject, null, Verdict.PENDING)), ledger.entries("project-2"))
        assertEquals(emptyList(), ledger.entries("missing-project"))
    }

    private fun asked(taskId: String, projectId: String = "project-1") = Asked(
        taskId = taskId,
        projectId = projectId,
        title = "Title $taskId",
        instruction = "Instruction $taskId",
        promptChars = 12,
    )
}
