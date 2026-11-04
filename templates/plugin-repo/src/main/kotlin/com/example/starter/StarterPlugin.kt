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
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

/** The starter's UI half: one tab. Everything registered here is declared in module.json under the same id. */
class StarterPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(
            TabProvider(
                id = "example.starter", container = TabContainers.CENTER, order = 100,
                title = { "Starter" },
                icon = tabIcon("example-starter", { circle(12f, 12f, 8f) }),
                testTag = "center-tab-EXAMPLE_STARTER",
            ) { StarterTab(it) },
        )
    }

    @Composable
    private fun StarterTab(context: TabContext) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("example-starter"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hello from Example_Starter.")
            Text("Agents: ${context.state.agents.size}.")
        }
    }
}
