package com.superiorsoftllc.agenthq.ledger

interface AskedDoneLedger {
    fun recordAsked(asked: Asked)
    fun recordDone(done: Done)
    fun entry(taskId: String): LedgerEntry?
    fun entries(projectId: String): List<LedgerEntry>
}

class InMemoryAskedDoneLedger : AskedDoneLedger {
    private val entriesByTaskId = linkedMapOf<String, LedgerEntry>()

    override fun recordAsked(asked: Asked) {
        require(asked.taskId !in entriesByTaskId) { "Task '${asked.taskId}' was already asked" }
        entriesByTaskId[asked.taskId] = LedgerEntry(asked, done = null, verdict = Verdict.PENDING)
    }

    override fun recordDone(done: Done) {
        val current = entriesByTaskId[done.taskId]
            ?: throw IllegalArgumentException("Task '${done.taskId}' was never asked")
        require(current.done == null) { "Task '${done.taskId}' already has a Done record" }

        val verdict = when (done.outcome) {
            Outcome.COMPLETED -> Verdict.DELIVERED
            Outcome.FAILED, Outcome.STOPPED -> Verdict.UNDELIVERED
        }
        entriesByTaskId[done.taskId] = current.copy(done = done, verdict = verdict)
    }

    override fun entry(taskId: String): LedgerEntry? = entriesByTaskId[taskId]

    override fun entries(projectId: String): List<LedgerEntry> =
        entriesByTaskId.values.filter { it.asked.projectId == projectId }
}
