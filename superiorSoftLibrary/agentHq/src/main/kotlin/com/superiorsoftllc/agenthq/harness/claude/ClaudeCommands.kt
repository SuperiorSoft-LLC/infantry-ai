package com.superiorsoftllc.agenthq.harness.claude

import com.superiorsoftllc.agenthq.harness.HarnessCommand
import com.superiorsoftllc.agenthq.harness.StandardCommands

/** The `/` commands Claude Code offers a user: `/model`, `/effort` (its own levels) and its native `/compact`. */
object ClaudeCommands {
    val EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

    val catalog: List<HarnessCommand> = StandardCommands.catalog(efforts = EFFORT_LEVELS)
}
