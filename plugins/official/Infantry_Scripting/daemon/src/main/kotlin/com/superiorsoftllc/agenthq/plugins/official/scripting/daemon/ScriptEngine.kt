package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

/**
 * SCRIPT-2 (D17/D19): the public contract of the Scripting plugin's open extension point. An engine plugin implements
 * [ScriptEngine] and publishes it with [ScriptEngineWire.publish]; Scripting finds every published engine and routes by id or file extension.
 */
data class ScriptLimits(
    val maxSteps: Long = 5_000_000L,
    val timeoutMs: Long = 2_000L,
    val maxAllocBytes: Long = 64L * 1024 * 1024,
)

/** Who runs the script. Data only (D18): an engine never checks it. */
data class ScriptCaller(val id: String = "", val role: String = "")

/** One action a script asked for with `ih.call(name, args)`. Nothing runs inside the script; the caller decides. */
data class QueuedAction(val name: String, val args: Map<String, Any?>)

enum class ScriptOutcome { OK, ERROR, BUDGET_EXCEEDED, TIMEOUT, ALLOC_EXCEEDED }

data class ScriptResult(
    val outcome: ScriptOutcome,
    val actions: List<QueuedAction> = emptyList(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

interface ScriptEngine {
    val id: String
    /** File extensions (no dot, lower case) this engine runs, e.g. `lua`. */
    val extensions: List<String>
    fun run(source: String, input: Map<String, Any?> = emptyMap(), limits: ScriptLimits = ScriptLimits(), caller: ScriptCaller = ScriptCaller()): ScriptResult
}
