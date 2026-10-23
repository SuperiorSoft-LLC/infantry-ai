package com.superiorsoftllc.agenthq.opencode

import com.superiorsoftllc.agenthq.harness.CommandAction
import com.superiorsoftllc.agenthq.harness.HarnessCommand

/**
 * The `/` commands an opencode session offers. STATIC list: the opencode CLI has no command that enumerates
 * its slash commands, so this names the built-ins the daemon can honour. `/model` switches the model for the
 * next run (`-m`); `/compact` hands opencode its own built-in `/compact`. There is no `/effort`: `opencode run`
 * takes no effort flag.
 */
object OpenCodeCommands {
    val catalog: List<HarnessCommand> = listOf(
        HarnessCommand("model", "Switch the model for the next turn", CommandAction.SET_MODEL, argument = "<provider/model>"),
        HarnessCommand("compact", "Compact the conversation (opencode's own /compact)", CommandAction.NATIVE, native = "/compact"),
    )
}
