package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.application
import com.superiorsoftllc.agenthq.ui.shell.AgentHqShell
import com.superiorsoftllc.agenthq.ui.shell.defaultPlatformHost


/** Desktop entry: only the Window (the undecorated [AgentHqWindow]). Configuration and the shell itself live in :agenthqUiCore. */
fun main() = runDevUi()

/** The dev window: UI only, against the daemon the environment names (default ws://127.0.0.1:8787/ws). */
fun runDevUi() = application {
    AgentHqWindow(onCloseRequest = ::exitApplication) {
        CompositionLocalProvider(LocalDirectoryPicker provides SwingDirectoryPicker) {
            AgentHqShell(defaultPlatformHost())
        }
    }
}
