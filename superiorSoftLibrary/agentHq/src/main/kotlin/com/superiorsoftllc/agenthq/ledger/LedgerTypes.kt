package com.superiorsoftllc.agenthq.ledger

import com.superiorsoftllc.agenthq.brief.WorkerBrief

data class Asked(
    val taskId: String,
    val projectId: String,
    val title: String,
    val instruction: String,
    val promptChars: Int,
) {
    companion object {
        fun from(brief: WorkerBrief): Asked = Asked(
            taskId = brief.taskId,
            projectId = brief.projectId,
            title = brief.title,
            instruction = brief.instruction,
            promptChars = brief.prompt.length,
        )
    }
}

enum class Outcome { COMPLETED, FAILED, STOPPED }

data class Done(val taskId: String, val outcome: Outcome, val summary: String)

enum class Verdict { PENDING, DELIVERED, UNDELIVERED }

data class LedgerEntry(val asked: Asked, val done: Done?, val verdict: Verdict)
