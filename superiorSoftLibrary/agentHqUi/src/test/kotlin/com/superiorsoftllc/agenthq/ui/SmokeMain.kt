package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

/** Dev smoke: the real window against an in-process stub daemon (temp state, nothing under ~/.agenthq). */
fun main() {
    val dir = Files.createTempDirectory("agenthq-ui-smoke").toFile()
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val daemon = AgentHqDaemon.build(
        port = 18787, provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
    )
    daemon.start() // BUG-FLAKE-1: not server.start: build() holds port 18787 until start() lets go of it
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    scope.launch { delay(2500); controller.spawn("say hello") }
    application {
        Window(onCloseRequest = ::exitApplication, title = "AgentHQ smoke", state = rememberWindowState(size = DpSize(1400.dp, 860.dp))) {
            App(controller, "ws://127.0.0.1:18787/ws", null)
        }
    }
}
