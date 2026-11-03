package com.superiorsoftllc.infantry.ledger

data class Asked(
    val taskId: String,
    val projectId: String,
    val title: String,
    val instruction: String,
    val promptChars: Int,
) {
    companion object
}

enum class Outcome { COMPLETED, FAILED, STOPPED }

data class Done(val taskId: String, val outcome: Outcome, val summary: String)

enum class Verdict { PENDING, DELIVERED, UNDELIVERED }

data class LedgerEntry(val asked: Asked, val done: Done?, val verdict: Verdict)
