// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.DaemonAccess
import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PluginServices
import com.superiorsoftllc.infantry.plugin.api.ServiceKey
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.LocalTabFactory
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * SIGNIN-APP-1: the owner's window crashed ("Vertically scrollable component was measured with an infinity maximum height") once the
 * Services card read Connected and its dashboard opened. A dashboard is another plugin's tab that scrolls by itself (Sites does), so the
 * hub must hand it a bounded height and never nest it inside its own vertical scroll.
 */
@OptIn(ExperimentalTestApi::class)
class ServicesHubLayoutTest {
    private val dashboardId = "acme.dashboard"

    /** A dashboard exactly as Sites draws one: a Column with its own verticalScroll. */
    private val dashboard = TabProvider(
        id = dashboardId, container = TabContainers.CENTER, order = 30,
        title = { "Acme" }, icon = tabIcon("acme", { moveTo(1f, 1f); lineTo(2f, 2f) }), testTag = "center-tab-ACME", parent = ACCOUNT_TAB_ID,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            repeat(40) { i -> Text("server row $i", Modifier.testTag("acme-row-$i")) }
        }
    }

    private val factory = TabFactory(listOf(InfantryAccount, InfantryModule(
        ModuleManifest(name = "Acme_Dashboard", version = "1.0.0", author = "Acme", description = "x", tabs = listOf(TabDeclaration(dashboardId, TabContainers.CENTER))),
        listOf(dashboard),
    )))

    private val state = """{"providers":[{"id":"acme","name":"Acme Cloud","description":"Servers.","signedIn":true,
        "plan":{"id":"basic","status":"active"},"services":[{"id":"w","name":"Widgets","entitled":true,"section":"$dashboardId"}]}]}"""

    private val daemon = DaemonAccess { route, _ ->
        if (route.startsWith("GET /plugins/Infantry_Account/state")) DaemonReply.Ok(200, state) else DaemonReply.Ok(200, "{}")
    }

    private val services = object : PluginServices {
        override val module = "Infantry_Account"
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: ServiceKey<T>): T? = if (key == Services.DAEMON) daemon as T else null
    }

    @Test
    fun `a connected service opens its own scrolling dashboard inside the Services tab without a layout crash`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val context = TabContext(
                AppState(), Controller(scope), open = {}, showChat = {}, insertIntoChat = {}, diffTarget = null, onOpenDiff = {}, transcript = {},
                services = services,
            )
            setContent {
                CompositionLocalProvider(LocalTabFactory provides factory) {
                    Box(Modifier.size(900.dp, 600.dp)) { ServicesHub(context) }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(androidx.compose.ui.test.hasText("Open its dashboard"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Open its dashboard", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(SERVICES_DASHBOARD_TAG_PREFIX + "acme").assertIsDisplayed()
            onNodeWithText("server row 0").assertIsDisplayed()
            onNodeWithTag(SERVICES_BACK_TAG).performClick()
            waitForIdle()
            onNodeWithText("Open its dashboard", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }
}
