package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.harness.ContextLimitCapability
import com.superiorsoftllc.agenthq.harness.ContextLimitSupport

/**
 * CONTEXT-LIMIT-1 (docs/CONTEXT-LIMIT.md): how Claude Code takes a session's context limit. One place, used by the one-shot
 * spec ([ClaudeCommand.spec]), the persistent session ([ClaudeSessionAdapter]) and the harness ([com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness]).
 *
 * Verified in Claude Code 2.1.312 (the installed binary's own messages): `CLAUDE_CODE_AUTO_COMPACT_WINDOW` takes a size
 * ("such as 200k or 200000") and auto-compacts the conversation at that window; a value under the CLI's minimum is read
 * as the minimum. So the CLI itself enforces it: SUPPORTED.
 */
object ClaudeContextLimit {
    const val ENV = "CLAUDE_CODE_AUTO_COMPACT_WINDOW"

    val CAPABILITY = ContextLimitCapability(
        ContextLimitSupport.SUPPORTED,
        "Claude Code auto-compacts at the limit ($ENV); a value under its minimum is raised to the minimum",
    )

    /** The env a run gets for [limit]; empty with no limit, so the CLI's own window applies. */
    fun env(limit: Int?): Map<String, String> = limit?.takeIf { it > 0 }?.let { mapOf(ENV to it.toString()) }.orEmpty()
}
