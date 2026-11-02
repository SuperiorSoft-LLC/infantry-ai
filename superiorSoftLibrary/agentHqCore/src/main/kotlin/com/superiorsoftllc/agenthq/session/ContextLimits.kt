package com.superiorsoftllc.agenthq.session

/**
 * CONTEXT-LIMIT-1 (docs/CONTEXT-LIMIT.md): the one rule for a context limit value, shared by spawn, ticket and CLI/MCP input.
 * A limit is a token count; null = none (the harness's own window). How a harness honours it is its own
 * ([com.superiorsoftllc.agenthq.harness.ContextLimitCapability]); this only says which numbers are a limit at all.
 * RUNTIME-LOAD-4: the rule lives in the daemon API ([com.superiorsoftllc.agenthq.daemon.api.board.ContextLimits]) so the board
 * plugin's ticket tools read and print a limit the same way; this forwards to it.
 */
object ContextLimits {
    private val rule = com.superiorsoftllc.agenthq.daemon.api.board.ContextLimits

    /** Below this no agent can hold even its own instructions; a typo (`200` for `200k`) is refused, not run. */
    const val MIN = com.superiorsoftllc.agenthq.daemon.api.board.ContextLimits.MIN

    /** Above any model window on offer today (1M); a larger value is a typo, not a choice. */
    const val MAX = com.superiorsoftllc.agenthq.daemon.api.board.ContextLimits.MAX

    /** Null when [limit] is usable (or null), else why not. */
    fun problem(limit: Int?): String? = rule.problem(limit)

    /** `200000`, `200k`, `1m`, `1.5M`; blank / `0` / `off` / `none` = no limit; anything else is `Parsed.error`. */
    fun parse(raw: String?): com.superiorsoftllc.agenthq.daemon.api.board.ContextLimits.Parsed = rule.parse(raw)

    /** `200k`, `1m`, `1.5m`, or the plain number when it is not round. */
    fun label(limit: Int): String = rule.label(limit)
}
