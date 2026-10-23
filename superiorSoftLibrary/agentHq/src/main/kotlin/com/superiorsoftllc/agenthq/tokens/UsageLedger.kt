package com.superiorsoftllc.agenthq.tokens

data class ArmTotals(
    val arm: Arm,
    val runs: Int,
    val tokensIn: Long,
    val tokensOut: Long,
    val wallClockMs: Long,
    val defects: Int,
)

data class ArmComparison(
    val taskId: String,
    val totals: Map<Arm, ArmTotals>,
)

interface UsageLedger {
    fun record(sample: UsageSample)
    fun samples(taskId: String): List<UsageSample>
    fun compare(taskId: String): ArmComparison
}

class InMemoryUsageLedger : UsageLedger {
    private val samplesByTask = mutableMapOf<String, MutableList<UsageSample>>()

    override fun record(sample: UsageSample) {
        val list = samplesByTask.getOrPut(sample.taskId) { mutableListOf() }
        list.add(sample)
    }

    override fun samples(taskId: String): List<UsageSample> {
        return samplesByTask[taskId]?.toList() ?: emptyList()
    }

    override fun compare(taskId: String): ArmComparison {
        val taskSamples = samplesByTask[taskId] ?: emptyList()
        val totals = taskSamples
            .groupBy { it.arm }
            .mapValues { (_, samples) ->
                val runs = samples.size
                val tokensIn = samples.sumOf { it.tokensIn }
                val tokensOut = samples.sumOf { it.tokensOut }
                val wallClockMs = samples.sumOf { it.wallClockMs }
                val defects = samples.sumOf { it.defects }
                ArmTotals(samples.first().arm, runs, tokensIn, tokensOut, wallClockMs, defects)
            }
        return ArmComparison(taskId, totals)
    }
}