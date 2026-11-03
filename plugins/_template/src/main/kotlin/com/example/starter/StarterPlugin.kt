// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
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
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.Panels
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.plugin.TabInterceptor
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

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

        // module.json "panels": { "id": "example.starter.side", "panel": "left" }. A section of a side panel: on the left
        // it stacks above (order < Panels.TABS_ORDER) or below the agents list; on the right, above or below the tab
        // strip. A tab in the right panel's strip is a tab with container "right" instead.
        registrar.panel(
            PanelProvider(id = "example.starter.side", panel = Panels.LEFT, order = 200, testTag = "example-starter-side") {
                Text("Starter: ${it.state.agents.size} agents", Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            },
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
