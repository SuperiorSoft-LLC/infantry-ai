package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * PLUGINS-1 dev tool: the whole window against an in-process stub daemon (temp state), one PNG per tab of every tab
 * strip, so a change to how tabs are built can be compared shot by shot. Args: output folder (default build/tab-shots).
 */
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/tab-shots").apply { mkdirs() }
    val dir = Files.createTempDirectory("agenthq-tab-shots").toFile()
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val daemon = AgentHqDaemon.build(
        port = 18797, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
    )
    daemon.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    runDesktopComposeUiTest(1400, 860) {
        setContent { App(controller, "ws://127.0.0.1:18797/ws", null) }
        waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive }
        scope.launch { controller.spawn("say hello") }
        waitUntil(timeoutMillis = 20_000) { controller.state.value.agents.isNotEmpty() }
        waitForIdle()
        fun shot(name: String) {
            waitForIdle()
            Thread.sleep(400)
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
            println("shot ${File(out, "$name.png").path}")
        }
        // Every node tagged center-tab-* / right-tab-* is one tab; click each in strip order.
        for (prefix in listOf("center-tab-", "right-tab-")) {
            val tagged = SemanticsMatcher("tag starts with $prefix") { n ->
                n.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
            }
            val tags = onAllNodes(tagged).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }.filter { it != CENTER_TAB_BAR_TAG }.distinct()
            for (tag in tags) {
                onAllNodesWithTag(tag)[0].performClick()
                shot(tag)
            }
        }
    }
    daemon.close()
    exitProcess(0)
}
