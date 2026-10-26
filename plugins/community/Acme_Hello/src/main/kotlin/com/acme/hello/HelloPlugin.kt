package com.acme.hello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.plugin.api.DaemonReply
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabInterceptor
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import kotlinx.coroutines.launch

/**
 * Example community module. Everything it registers is declared in its module.json; it reaches the app only through
 * the services handed to it (events, storage, daemon, notifications), each one allowed in plugins/modules.json.
 */
class HelloPlugin : UiPlugin {
    /** Tabs opened since start, counted by the `count` observer and kept in this module's own storage. */
    private var opened by mutableIntStateOf(0)

    override fun register(registrar: UiRegistrar) {
        val storage = registrar.services.get(Services.STORAGE)
        opened = storage?.read("opened.txt")?.toIntOrNull() ?: 0
        registrar.services.get(Services.NOTIFICATIONS)?.notify("Acme_Hello", "loaded")

        registrar.tab(
            TabProvider(
                id = "acme.hello", container = TabContainers.CENTER, order = 95,
                title = { "Hello" },
                icon = tabIcon("acme-hello", { circle(12f, 12f, 9f) }, { moveTo(8.5f, 14f); curveTo(10f, 16f, 14f, 16f, 15.5f, 14f) }),
                testTag = "center-tab-ACME_HELLO",
            ) { HelloTab(it) },
        )
        // PLUGINS-3: a tab in the right panel's strip, like any core right tab (module.json: container "right").
        registrar.tab(
            TabProvider(
                id = "acme.hello.side", container = TabContainers.RIGHT, order = 35,
                title = { "Hello" },
                icon = tabIcon("acme-hello-side", { circle(12f, 12f, 9f) }),
                testTag = "right-tab-ACME_HELLO",
            ) { Text("Hello from the right panel. Tabs opened since start: $opened.", Modifier.padding(12.dp).testTag("acme-hello-side")) },
        )
        // Offered as a replacement for the core Git tab; the trust rules refuse it unless modules.json allows the override.
        registrar.tab(
            TabProvider(
                id = "acme.git", container = TabContainers.CENTER, order = 990,
                title = { "Acme Git" },
                icon = tabIcon("acme-git", { circle(12f, 12f, 3f) }),
                testTag = "center-tab-ACME_GIT",
            ) { Text("Acme's Git tab", Modifier.padding(16.dp)) },
        )
        registrar.interceptor("hi", TabInterceptor(title = { _, proceed -> proceed() + " +hi" }))
        registrar.observer("count") { e: PluginEvent ->
            opened += 1
            storage?.write("opened.txt", opened.toString())
            println("[Acme_Hello] tab opened: ${e.data["id"]}")
        }
    }

    @Composable
    private fun HelloTab(context: TabContext) {
        val daemon = context.services.get(Services.DAEMON)
        val scope = rememberCoroutineScope()
        var reply by remember { mutableStateOf("") }
        fun call(route: String) = scope.launch {
            reply = when (val r = daemon?.call(route, null)) {
                is DaemonReply.Ok -> "$route -> ${r.status} ${r.body.take(80)}"
                is DaemonReply.Refused -> "$route refused: ${r.reason}"
                is DaemonReply.Failed -> "$route failed: ${r.reason}"
                null -> "no daemon service"
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("acme-hello"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hello from Acme_Hello, a community module.")
            Text("Agents: ${context.state.agents.size}. Tabs opened since start: $opened.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { call("GET /health") }, modifier = Modifier.testTag("acme-hello-health")) { Text("GET /health (declared)") }
                OutlinedButton(onClick = { call("GET /sessions") }, modifier = Modifier.testTag("acme-hello-sessions")) { Text("GET /sessions (not declared)") }
            }
            if (reply.isNotEmpty()) Text(reply, Modifier.testTag("acme-hello-reply"))
        }
    }
}
