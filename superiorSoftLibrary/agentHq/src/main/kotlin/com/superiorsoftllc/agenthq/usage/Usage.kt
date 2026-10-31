package com.superiorsoftllc.agenthq.usage

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.attention.AttentionStore
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Observed usage of one run. [costUsd] is null when the provider reported no cost. */
@Serializable
data class RunUsage(
    val runId: String,
    val provider: String,
    val model: String,
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val costUsd: Double? = null,
)

/** Push frame sent when a run ends (wire type `usage`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UsageFrame(val usage: RunUsage, @EncodeDefault val type: String = "usage")

/** Sums every OpenCode `step_finish` line of a run. No such line means null, never zero. */
object RunUsageParser {
    fun parse(runId: String, provider: String, model: String, lines: Sequence<String>): RunUsage? {
        var input = 0L
        var output = 0L
        var reasoning = 0L
        var read = 0L
        var write = 0L
        var cost: Double? = null
        var seen = false
        for (line in lines) {
            val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (obj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content != "step_finish") continue
            val part = obj["part"] as? JsonObject ?: continue
            val tokens = part["tokens"] as? JsonObject ?: continue
            input += tokens["input"]?.jsonPrimitive?.longOrNull ?: continue
            output += tokens["output"]?.jsonPrimitive?.longOrNull ?: 0L
            reasoning += tokens["reasoning"]?.jsonPrimitive?.longOrNull ?: 0L
            val cache = tokens["cache"] as? JsonObject
            read += cache?.get("read")?.jsonPrimitive?.longOrNull ?: 0L
            write += cache?.get("write")?.jsonPrimitive?.longOrNull ?: 0L
            part["cost"]?.jsonPrimitive?.doubleOrNull?.let { cost = (cost ?: 0.0) + it }
            seen = true
        }
        return if (seen) RunUsage(runId, provider, model, input, output, reasoning, read, write, cost) else null
    }
}

/** UNKNOWN is the default: no signal is never reported as 0%. Wire names are lower case (contract 1.13.0). */
@Serializable
enum class ProviderStatus(val wireName: String) {
    @SerialName("unknown") UNKNOWN("unknown"),
    @SerialName("stalled") STALLED("stalled"),
    @SerialName("exhausted") EXHAUSTED("exhausted");

    companion object {
        /** A stored status this build does not know reads as [UNKNOWN]: never a guess in either direction. */
        fun fromWireName(value: String): ProviderStatus = entries.firstOrNull { it.wireName == value } ?: UNKNOWN
    }
}

/** Last observed limit state of a provider (wire type `provider_state`). */
@Serializable
data class ProviderState(
    val provider: String,
    val status: ProviderStatus,
    val window: String? = null,
    val utilization: Double? = null,
    val resetsAt: Long? = null,
    val observedAt: Long? = null,
    val source: String = "none",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProviderStateFrame(val state: ProviderState, @EncodeDefault val type: String = "provider_state")

/**
 * Holds the latest [ProviderState] per provider. Exhaustion is only ever declared by the injected
 * [exhaustion] classifier, which is empty by default because no exhausted sample has been captured.
 * A stalled run is [ProviderStatus.STALLED]: the stream cannot tell it from free-quota exhaustion.
 */
class ProviderStateTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (ProviderState) -> Unit = {},
    private val exhaustion: (rawLine: String) -> Boolean = { false },
) {
    private val states = LinkedHashMap<String, ProviderState>()

    @Synchronized
    fun state(provider: String): ProviderState = states[provider] ?: ProviderState(provider, ProviderStatus.UNKNOWN)

    @Synchronized
    fun states(): List<ProviderState> = states.values.toList()

    /** Daemon startup: put back the last observations persisted before a restart, publishing nothing. */
    @Synchronized
    fun restore(saved: List<ProviderState>) {
        for (state in saved) states[state.provider] = state
    }

    /** Returns the new state when [line] is a `rate_limit_event`, else null. */
    fun observeStreamLine(provider: String, line: String): ProviderState? {
        val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        if (obj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content != "rate_limit_event") return null
        val info = obj["rate_limit_info"] as? JsonObject
        val next = ProviderState(
            provider = provider,
            status = if (exhaustion(line)) ProviderStatus.EXHAUSTED else ProviderStatus.UNKNOWN,
            window = info?.get("rateLimitType")?.jsonPrimitive?.takeIf { it.isString }?.content,
            utilization = info?.get("utilization")?.jsonPrimitive?.doubleOrNull,
            resetsAt = info?.get("resetsAt")?.jsonPrimitive?.longOrNull,
            observedAt = clock(),
            source = "stream",
        )
        return publish(next)
    }

    fun markStalled(provider: String): ProviderState =
        publish(ProviderState(provider, ProviderStatus.STALLED, observedAt = clock(), source = "stall"))

    private fun publish(next: ProviderState): ProviderState {
        synchronized(this) { states[next.provider] = next }
        sink(next)
        return next
    }
}

data class BudgetSnapshot(val runs: Int, val tokens: Long)

/** Operator budget counted from the daemon's own runs per UTC day. Never called "quota". */
class OperatorBudget(
    private val maxRunsPerDay: Int?,
    private val maxTokensPerDay: Long?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Day(val day: Long, var runs: Int = 0, var tokens: Long = 0)

    private val days = HashMap<String, Day>()

    private fun today() = clock() / 86_400_000L

    @Synchronized
    fun record(usage: RunUsage) {
        val day = days[usage.provider]?.takeIf { it.day == today() }
            ?: Day(today()).also { days[usage.provider] = it }
        day.runs += 1
        day.tokens += usage.input + usage.output + usage.reasoning
    }

    /** A run that reported no usage still counts as a run; its tokens are not invented. */
    @Synchronized
    fun recordRun(provider: String) {
        val day = days[provider]?.takeIf { it.day == today() }
            ?: Day(today()).also { days[provider] = it }
        day.runs += 1
    }

    /** Daemon startup: today's totals for [provider], recounted from the stored runs. */
    @Synchronized
    fun restore(provider: String, runs: Int, tokens: Long) {
        days[provider] = Day(today(), runs, tokens)
    }

    @Synchronized
    fun snapshot(provider: String): BudgetSnapshot =
        days[provider]?.takeIf { it.day == today() }?.let { BudgetSnapshot(it.runs, it.tokens) } ?: BudgetSnapshot(0, 0)

    companion object {
        /** `AGENTHQ_BUDGET_RUNS_PER_DAY` / `AGENTHQ_BUDGET_TOKENS_PER_DAY`; unset or invalid = no limit. */
        fun fromEnv(env: (String) -> String? = InfantryEnv::get): OperatorBudget = OperatorBudget(
            maxRunsPerDay = env("AGENTHQ_BUDGET_RUNS_PER_DAY")?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            maxTokensPerDay = env("AGENTHQ_BUDGET_TOKENS_PER_DAY")?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }

    fun isSpent(provider: String): Boolean {
        val s = snapshot(provider)
        return (maxRunsPerDay != null && s.runs >= maxRunsPerDay) ||
            (maxTokensPerDay != null && s.tokens >= maxTokensPerDay)
    }
}

/** Minimal launch gate: unattended starts are refused, human starts only warned. */
class LaunchGate(
    private val tracker: ProviderStateTracker,
    private val budget: OperatorBudget,
    private val attention: AttentionStore,
) {
    fun check(provider: String, origin: LaunchOrigin, ticketId: String? = null, triggerId: String? = null): GateDecision {
        val reason = when {
            tracker.state(provider).status == ProviderStatus.EXHAUSTED -> "provider $provider limit is exhausted"
            budget.isSpent(provider) -> "operator budget for provider $provider is spent"
            else -> return GateDecision.Allow
        }
        if (origin == LaunchOrigin.HUMAN) return GateDecision.Warn(reason)
        attention.raise(
            kind = AttentionKind.LAUNCH_BLOCKED,
            ticketId = ticketId,
            triggerId = triggerId,
            outcome = "refused_launch_gate",
            detail = "Unattended start refused: $reason.",
        )
        return GateDecision.Refused(reason)
    }
}

/**
 * One ended run as stored in `run_usage`: [usage] is null when the run reported no usage (the row still
 * exists, so the budget can count the run after a restart).
 */
data class RunUsageRecord(
    val runId: String,
    val sessionId: String,
    val provider: String,
    val model: String,
    val endedAt: Long,
    val usage: RunUsage?,
    /** CONTEXT-LIMIT-1: the context limit (tokens) the run's session had, null = none; for comparing cost with and without one. */
    val contextLimit: Int? = null,
    /** EFFORT-1: the reasoning effort level the run's session had, null = the harness default; for comparing turns and tokens by effort. */
    val effort: String? = null,
)

/**
 * Story 9.7b wiring: turns each run's stream into its usage record, as one more arm of the daemon's
 * session-event sink. It must run BEFORE the backlog arm, so a run that spends the budget is counted
 * before the same exit drains a queue (the drained start is then judged against the spent budget).
 *
 * - a `turn` opens a fresh buffer for the session's new run;
 * - every stdout line goes past the [tracker] (a `rate_limit_event` becomes that provider's state), and
 *   only `step_finish` lines are kept, so a chatty run costs no memory;
 * - the run's exit parses the kept lines ([RunUsageParser]): usage found -> stored, counted with its
 *   tokens and [publish]ed; none found -> stored as a run with no usage and counted as a run only.
 *
 * The run id is `<sessionId>:<runCount>`: a session's runs are numbered by the session itself.
 */
class UsageRecorder(
    private val tracker: ProviderStateTracker,
    private val budget: OperatorBudget,
    private val sessions: (String) -> com.superiorsoftllc.agenthq.session.SessionRecord?,
    private val defaultModel: (provider: String) -> String?,
    private val persist: (RunUsageRecord) -> Unit,
    private val publish: (sessionId: String, usage: RunUsage) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val buffers = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    // USAGE-1: Claude and Pi report no `step_finish`; their usage comes from the stream accumulator (usagemeter package).
    private val streams = java.util.concurrent.ConcurrentHashMap<String, com.superiorsoftllc.agenthq.usagemeter.StreamUsageAccumulator>()

    fun onSessionEvent(event: com.superiorsoftllc.agenthq.session.SessionEvent) {
        when (event) {
            is com.superiorsoftllc.agenthq.session.SessionEvent.Turn -> {
                    buffers[event.sessionId] = java.util.Collections.synchronizedList(mutableListOf())
                    streams[event.sessionId] = com.superiorsoftllc.agenthq.usagemeter.StreamUsageAccumulator()
                }

            is com.superiorsoftllc.agenthq.session.SessionEvent.Stdout -> {
                val provider = sessions(event.sessionId)?.provider ?: return
                runCatching { tracker.observeStreamLine(provider, event.line) }
                streams[event.sessionId]?.accept(event.line)
                if (event.line.contains("step_finish")) {
                    buffers.computeIfAbsent(event.sessionId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(event.line)
                }
            }

            is com.superiorsoftllc.agenthq.session.SessionEvent.Exit -> {
                val lines = buffers.remove(event.sessionId)?.let { synchronized(it) { it.toList() } }.orEmpty()
                val record = sessions(event.sessionId) ?: return
                val runId = "${record.id}:${record.runCount}"
                val model = record.model ?: defaultModel(record.provider) ?: "default"
                val streamed = streams.remove(event.sessionId)?.totals()
                val usage = RunUsageParser.parse(runId, record.provider, model, lines.asSequence())
                    ?: streamed?.let { RunUsage(runId, record.provider, model, it.input, it.output, 0, it.cacheRead, it.cacheWrite, it.cost) }
                runCatching { persist(RunUsageRecord(runId, record.id, record.provider, model, clock(), usage, record.contextLimit, record.effort)) }
                    .onFailure { System.err.println("[agentHq] run usage not stored for $runId: ${it.message}") }
                if (usage != null) {
                    budget.record(usage)
                    publish(record.id, usage)
                } else {
                    budget.recordRun(record.provider)
                }
            }

            else -> Unit
        }
    }
}
