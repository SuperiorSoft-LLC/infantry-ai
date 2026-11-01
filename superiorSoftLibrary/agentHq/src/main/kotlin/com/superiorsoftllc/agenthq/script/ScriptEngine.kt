package com.superiorsoftllc.agenthq.script

/** SCRIPT-2: budgets for one script run. Defaults live here; the daemon reads overrides from settings (`script.*`). */
data class ScriptLimits(
    val maxSteps: Long = 5_000_000L,
    val timeoutMs: Long = 2_000L,
    val maxAllocBytes: Long = 64L * 1024 * 1024,
)

/** One action a script asked for with `ih.call(name, args)`. Nothing runs inside the script; the caller decides. */
data class QueuedAction(val name: String, val args: Map<String, Any?>)

enum class ScriptOutcome { OK, ERROR, BUDGET_EXCEEDED, TIMEOUT, ALLOC_EXCEEDED }

data class ScriptResult(
    val outcome: ScriptOutcome,
    val actions: List<QueuedAction> = emptyList(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

/** The seam: engines are looked up by [id] so another engine (e.g. an exec engine) can slot in later. */
interface ScriptEngine {
    val id: String
    fun run(source: String, input: Map<String, Any?> = emptyMap(), limits: ScriptLimits = ScriptLimits()): ScriptResult
}

class ScriptEngines(engines: List<ScriptEngine>) {
    private val byId = engines.associateBy { it.id }
    fun get(id: String): ScriptEngine? = byId[id]
    fun ids(): Set<String> = byId.keys
}
