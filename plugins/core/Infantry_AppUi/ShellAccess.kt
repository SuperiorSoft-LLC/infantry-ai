package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.Shell
import com.superiorsoftllc.agenthq.ui.coreShell

/**
 * S4d (docs/SHELL-LEAKS.md): the window's [Shell] for the app UI's blocks. They are fills, which only a core module
 * may register, so a null is a wiring bug, not a refusal (`TabContext.coreController`, its twin for tabs, is gone since RUNTIME-16). What a
 * block can read off the ShellContext it reads there; this is what still passes `(state, controller)` to a pane.
 */
internal val appShell: Shell
    @Composable get() = checkNotNull(coreShell) { "an app UI block drawn in a community module's composition" }
