package com.superiorsoftllc.agenthq.session

/**
 * CONTEXT-LIMIT-1 (docs/CONTEXT-LIMIT.md): the one rule for a context limit value, shared by spawn, ticket and CLI/MCP input.
 * A limit is a token count; null = none (the harness's own window). How a harness honours it is its own
 * ([com.superiorsoftllc.agenthq.harness.ContextLimitCapability]); this only says which numbers are a limit at all.
 */
object ContextLimits {
    /** Below this no agent can hold even its own instructions; a typo (`200` for `200k`) is refused, not run. */
    const val MIN = 10_000

    /** Above any model window on offer today (1M); a larger value is a typo, not a choice. */
    const val MAX = 10_000_000

    /** Null when [limit] is usable (or null), else why not. */
    fun problem(limit: Int?): String? = when {
        limit == null -> null
        limit < MIN || limit > MAX -> "context limit $limit is out of range: $MIN..$MAX tokens (e.g. 200000 or 200k)"
        else -> null
    }

    /**
     * Reads a user-typed limit: `200000`, `200k`, `1m`, `1.5M` (case-insensitive, `_`/`,` ignored). Blank, `0`, `off`,
     * `none` = no limit (null, [Parsed.cleared] true). Anything else is [Parsed.error].
     */
    fun parse(raw: String?): Parsed {
        val text = raw?.trim()?.lowercase()?.replace("_", "")?.replace(",", "").orEmpty()
        if (text.isEmpty() || text == "0" || text == "off" || text == "none") return Parsed(null, cleared = true)
        val m = Regex("""(\d+(?:\.\d+)?)([km]?)""").matchEntire(text)
            ?: return Parsed(null, error = "context limit '${raw!!.take(20)}' is not a token count (e.g. 200000, 200k, 1m, or off)")
        val n = m.groupValues[1].toDouble() * when (m.groupValues[2]) { "k" -> 1_000.0; "m" -> 1_000_000.0; else -> 1.0 }
        val value = if (n > Int.MAX_VALUE) Int.MAX_VALUE else n.toInt()
        return problem(value)?.let { Parsed(null, error = it) } ?: Parsed(value)
    }

    data class Parsed(val value: Int?, val cleared: Boolean = false, val error: String? = null)

    /** `200k`, `1m`, `1.5m`, or the plain number when it is not round. */
    fun label(limit: Int): String = when {
        limit % 1_000_000 == 0 -> "${limit / 1_000_000}m"
        limit % 100_000 == 0 && limit > 1_000_000 -> "${limit / 1_000_000.0}m"
        limit % 1_000 == 0 -> "${limit / 1_000}k"
        else -> limit.toString()
    }
}
