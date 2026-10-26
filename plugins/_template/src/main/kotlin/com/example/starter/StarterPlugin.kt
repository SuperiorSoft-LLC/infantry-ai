package com.example.starter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabInterceptor
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/**
 * The starter module. Everything registered here is declared in module.json under the same id or name; the app
 * refuses anything that is not. It reaches the app only through [UiRegistrar.services], and only the services
 * module.json lists (and, for a community module, plugins/modules.json allows).
 */
class StarterPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.services.get(Services.NOTIFICATIONS)?.notify("Example_Starter", "loaded")

        // module.json "tabs": { "id": "example.starter", "container": "center" }
        registrar.tab(
            TabProvider(
                id = "example.starter", container = TabContainers.CENTER, order = 100,
                title = { "Starter" },
                icon = tabIcon("example-starter", { circle(12f, 12f, 8f) }),
                testTag = "center-tab-EXAMPLE_STARTER",
            ) { StarterTab(it) },
        )

        // module.json "interceptors": { "target": "example.starter", "name": "count" }. Point "target" at any tab id
        // (e.g. "infantry.epics") to hook that tab instead; here it adds the agent count to this tab's title.
        registrar.interceptor("count", TabInterceptor(title = { state, proceed -> "${proceed()} (${state.agents.size})" }))
    }

    @Composable
    private fun StarterTab(context: TabContext) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("example-starter"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hello from Example_Starter.")
            Text("Agents: ${context.state.agents.size}.")
        }
    }
}
