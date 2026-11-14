// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.plugins.official.board.BoardDeps
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

/** LANE-COLLAPSE-2 dev tool: the board alone in a real, compact (420dp) window over a stub daemon; no input needed. Args: port prefsDir. */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    System.setProperty("infantry.ui.prefs.dir", args[1])
    val dir = Files.createTempDirectory("infantry-lane-shot").toFile()
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val daemon = InfantryDaemon.build(
        memory = FixedMemory, port = port, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
    )
    daemon.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    controller.connect("ws://127.0.0.1:$port/ws", null)
    scope.launch { delay(2000); listOf("Fix login", "Add search", "Write docs").forEach { controller.createTicket(it, "", null, null) } }
    val compactLayout = object : LayoutContext {
        override fun tabTree(id: String) = null
        override val compact: Boolean get() = true
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "LANE-SHOT", state = rememberWindowState(size = DpSize(1100.dp, 500.dp))) {
            val state by controller.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(LocalPlatformHost provides JvmPlatformHost()) {
                com.superiorsoftllc.infantry.plugins.official.board.BoardPane(
                    BoardDeps(CoreLink(state), CoreSession(state), CoreTickets(state, controller), CoreRoles(state, controller), CoreExecutor(state), compactLayout),
                ) {}
            }
        }
    }
}
