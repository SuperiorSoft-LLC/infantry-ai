package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.harness.EffortCapability
import com.superiorsoftllc.agenthq.harness.EffortSupport

/**
 * EFFORT-1 (docs/EFFORT.md): Claude Code takes a reasoning effort as `--effort <level>` ([ClaudeCommand.spec]).
 * Verified on Claude Code 2.1.312: `claude --help` lists `--effort <level> (low, medium, high, xhigh, max)`; `claude -p "say ok"
 * --effort low` ran and answered; `--effort bogus` warns `Unknown --effort value` and falls back to the default.
 */
object ClaudeEffort {
    val CAPABILITY = EffortCapability(EffortSupport.SUPPORTED, "claude --effort <level> (low, medium, high, xhigh, max)")
}
