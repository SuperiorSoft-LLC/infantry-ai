package com.superiorsoftllc.agenthq.orchestration

/**
 * BUG-G2b: the first turn of a pod node's FIRST attempt, for the pod's project. The daemon wires the same composer
 * `prompt new` uses (knowledge, then skills, then the prompt); [RAW] keeps the bare prompt for an orchestrator built
 * without the daemon's knowledge stores (unit tests).
 */
fun interface PodFirstTurn {
    fun compose(projectId: String?, prompt: String): String

    companion object {
        val RAW = PodFirstTurn { _, prompt -> prompt }
    }
}
