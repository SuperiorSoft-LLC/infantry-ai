package com.superiorsoftllc.agenthq.stats

/**
 * STATS-1: the harness-agnostic fold. A reader walks its harness's record in order and reports four things:
 * a timestamp seen, a user prompt, a model request (with its token counts), and a tool call made BY the most
 * recent request. Everything in [SessionStats] that a harness can fill is derived here, the same way for all.
 *
 * Context sources: the growth between request N and request N+1 is what the tool results of step N added. It is
 * credited to a tool only when step N ran exactly one tool; a multi-tool step's growth goes to
 * [SessionStats.unattributedToolTokens] (never split by guess), and a shrink (compaction) credits nothing.
 */
internal class StatsAccumulator {
    private var first: Long? = null
    private var last: Long? = null
    private var prompts = 0
    private var turns = 0
    private val models = LinkedHashSet<String>()
    private val series = ArrayList<StatsPoint>()
    private var billed = 0L
    private val calls = HashMap<String, Int>()
    private val added = HashMap<String, Long>()
    private var unattributed = 0L
    private var toolUses = 0
    /** Tools called by the most recent request, waiting for the next request to measure what they added. */
    private val pending = ArrayList<String>()
    // EXPERIMENTS-1 (1.126.0): the A/B measures.
    private var firstEditAt: Long? = null
    private var callsBeforeFirstEdit: Int? = null
    private var resultCount = 0
    private var resultChars = 0L
    private var resultMax = 0L
    private var lastResultAt: Long? = null
    private val stepMs = ArrayList<Long>()

    fun seen(at: Long?) {
        if (at == null) return
        if (first == null || at < first!!) first = at
        if (last == null || at > last!!) last = at
    }

    fun prompt() {
        prompts++
    }

    fun turn(model: String?) {
        turns++
        if (!model.isNullOrBlank()) models += model
    }

    /** The model of the most recent non-empty request. */
    var lastModel: String? = null
        private set
    private var cost = 0.0
    private var anyCost = false

    /** One model request, once per model call; an all-zero request is skipped (STATS-2). */
    fun request(u: RequestUsage) {
        if (u.isEmpty) return
        if (!u.model.isNullOrBlank()) lastModel = u.model
        u.cost?.let { cost += it; anyCost = true }
        request(u.at, u.contextTokens, u.billedTokens, if (anyCost) cost else null)
    }

    private fun request(at: Long?, contextTokens: Long, billedTokens: Long, cumulativeCost: Double?) {
        val previous = series.lastOrNull()?.contextTokens
        if (previous != null && pending.isNotEmpty()) {
            val delta = contextTokens - previous
            if (delta > 0) {
                if (pending.size == 1) added.merge(pending[0], delta, Long::plus) else unattributed += delta
            }
        }
        pending.clear()
        billed += billedTokens
        series += StatsPoint(at, contextTokens, billed, cumulativeCost)
    }

    /** A tool result of [chars] characters that arrived at [at]. */
    fun toolResult(chars: Int, at: Long?) {
        resultCount++
        resultChars += chars
        if (chars > resultMax) resultMax = chars.toLong()
        if (at != null) lastResultAt = at
    }

    /** An assistant record at [at]: if it answers a tool result, the gap is one step's round trip. */
    fun assistantAt(at: Long?) {
        val from = lastResultAt
        if (from != null && at != null) {
            lastResultAt = null
            if (at >= from) stepMs += at - from
        }
    }

    /** A tool call made by the most recent [request]. */
    fun tool(name: String, at: Long? = null) {
        if (firstEditAt == null && callsBeforeFirstEdit == null && name.lowercase() in EDIT_TOOLS) {
            callsBeforeFirstEdit = toolUses
            firstEditAt = at
        }
        toolUses++
        calls.merge(name, 1, Int::plus)
        pending += name
    }

    fun build(base: SessionStats, modelContextLimit: Long?): SessionStats = base.copy(
        startedAt = first,
        endedAt = last,
        durationMs = if (first != null && last != null) last!! - first!! else null,
        userPrompts = prompts,
        assistantTurns = turns,
        toolUses = toolUses,
        models = models.toList(),
        lastContextTokens = series.lastOrNull()?.contextTokens,
        modelContextLimit = modelContextLimit,
        billedTokens = billed,
        costTotal = if (anyCost) cost else null,
        series = series.toList(),
        topSources = calls.entries
            .map { (tool, n) -> ContextSource(tool, n, added[tool]) }
            .sortedWith(compareByDescending<ContextSource> { it.tokensAdded ?: -1L }.thenByDescending { it.calls }.thenBy { it.tool }),
        unattributedToolTokens = unattributed,
        firstContextTokens = series.firstOrNull()?.contextTokens,
        msToFirstEdit = if (first != null) firstEditAt?.let { it - first!! } else null,
        toolCallsBeforeFirstEdit = callsBeforeFirstEdit,
        toolResults = resultCount,
        avgToolResultChars = if (resultCount > 0) resultChars / resultCount else null,
        maxToolResultChars = if (resultCount > 0) resultMax else null,
        stepMsP50 = percentile(0.5),
        stepMsP90 = percentile(0.9),
        stepMsMean = if (stepMs.isEmpty()) null else stepMs.sum() / stepMs.size,
    )

    private fun percentile(p: Double): Long? =
        if (stepMs.isEmpty()) null else stepMs.sorted().let { it[minOf(it.size - 1, (it.size * p).toInt())] }

    private companion object {
        /** Tool names (lowercase) that change a file: the "first edit" of a session. */
        val EDIT_TOOLS = setOf("edit", "write", "multiedit", "notebookedit", "apply_patch")
    }

}
