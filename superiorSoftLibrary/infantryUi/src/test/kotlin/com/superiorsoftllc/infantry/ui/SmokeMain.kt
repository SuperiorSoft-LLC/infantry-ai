// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

/** Dev smoke: the real window against an in-process stub daemon (temp state, nothing under ~/.infantry). */
fun main() {
    val dir = Files.createTempDirectory("infantry-ui-smoke").toFile()
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val daemon = InfantryDaemon.build(
        memory = FixedMemory,
        // Loopback always: the default host reads the owner's ~/.infantry remote-access setting (0.0.0.0 needs a token).
        port = 18787, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
    )
    daemon.start() // BUG-FLAKE-1: not server.start: build() holds port 18787 until start() lets go of it
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    scope.launch { delay(2500); controller.spawn("say hello") }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Infantry smoke", state = rememberWindowState(size = DpSize(1400.dp, 860.dp))) {
            App(controller, "ws://127.0.0.1:18787/ws", null)
        }
    }
}
