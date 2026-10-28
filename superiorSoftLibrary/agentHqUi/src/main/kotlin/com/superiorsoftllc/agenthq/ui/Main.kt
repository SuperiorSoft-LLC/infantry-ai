package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.superiorsoftllc.agenthq.ui.shell.AgentHqShell
import com.superiorsoftllc.agenthq.ui.shell.defaultPlatformHost


/** Desktop entry: only the Window (the undecorated [AgentHqWindow]). Configuration and the shell itself live in :agenthqUiCore. */
fun main(args: Array<String>) {
    safeModeFlag(args)
    ShellApp.apply(args) // NS4: --app <dir>
    runDevUi()
}

/**
 * S6: `--safe-mode` starts the shell with every community module off (and a banner that can remove one), so a plugin
 * that breaks the window never locks the owner out. Same as AGENTHQ_SAFE_MODE=1.
 */
fun safeModeFlag(args: Array<String>) {
    if ("--safe-mode" in args) System.setProperty(com.superiorsoftllc.agenthq.plugin.host.PluginFolders.SAFE_MODE_PROPERTY, "true")
}

/** The dev window: UI only, against the daemon the environment names (default ws://127.0.0.1:8787/ws). */
fun runDevUi() = application {
    // RUN-CONSOLE: a pill's console windows live beside the main window.
    val consoles = remember { DesktopRunConsoles() }
    AgentHqWindow(onCloseRequest = ::exitApplication) {
        CompositionLocalProvider(LocalDirectoryPicker provides SwingDirectoryPicker, LocalRunConsole provides consoles) {
            SplashHost { AgentHqShell(defaultPlatformHost()) }
        }
    }
    consoles.Windows()
}
