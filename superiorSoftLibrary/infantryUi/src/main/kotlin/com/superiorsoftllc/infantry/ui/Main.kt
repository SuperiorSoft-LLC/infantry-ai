package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.superiorsoftllc.infantry.ui.shell.InfantryShell
import com.superiorsoftllc.infantry.ui.shell.defaultPlatformHost


/** Desktop entry: only the Window (the undecorated [InfantryWindow]). Configuration and the shell itself live in :infantryUiCore. */
fun main(args: Array<String>) {
    com.superiorsoftllc.infantry.session.InfantryEnv.installProcessMirror()
    safeModeFlag(args)
    ShellApp.apply(args) // NS4: --app <dir>
    runDevUi()
}

/**
 * S6: `--safe-mode` starts the shell with every community module off (and a banner that can remove one), so a plugin
 * that breaks the window never locks the owner out. Same as the safe-mode environment switch.
 */
fun safeModeFlag(args: Array<String>) {
    if ("--safe-mode" in args) System.setProperty(com.superiorsoftllc.infantry.plugin.host.PluginFolders.SAFE_MODE_PROPERTY, "true")
}

/** The dev window: UI only, against the daemon the environment names (default ws://127.0.0.1:8787/ws). */
fun runDevUi() = application {
    // RUN-CONSOLE: a pill's console windows live beside the main window.
    val consoles = remember { DesktopRunConsoles() }
    InfantryWindow(onCloseRequest = ::exitApplication) {
        CompositionLocalProvider(LocalDirectoryPicker provides SwingDirectoryPicker, LocalRunConsole provides consoles) {
            SplashHost { InfantryShell(defaultPlatformHost()) }
        }
    }
    consoles.Windows()
}
