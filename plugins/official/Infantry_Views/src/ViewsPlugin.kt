// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.plugin.api.ViewAccess
import com.superiorsoftllc.infantry.plugin.api.ViewInfo
import com.superiorsoftllc.infantry.plugin.api.ViewState
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import kotlinx.serialization.json.JsonObject

const val VIEWS_TAB_ID = "infantry.views"

/** The views to list as sub-tabs: `place: tab`, built for [platform] (absent `platforms` = all). */
fun tabViews(all: List<ViewInfo>, platform: String): List<ViewInfo> =
    all.filter { it.place == "tab" && (it.platforms?.contains(platform) ?: true) }

/**
 * DV-3: Infantry_Views. Registers the `ui.*` block types and the Views tab (one sub-tab per `place: tab` view). Web and
 * Android compile it in later (DV-7); [platform] is the `platforms` name of the client it runs on.
 */
class ViewsPlugin(private val platform: String = "desktop") : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        for ((type, impl) in ViewWidgets.registered) registrar.blockType(type, impl)
        registrar.tab(
            TabProvider(
                id = VIEWS_TAB_ID, container = TabContainers.CENTER, order = 90,
                title = { "Views" },
                icon = tabIcon("views", { circle(12f, 12f, 8f) }, { moveTo(8f, 12f); lineTo(16f, 12f) }),
                testTag = "center-tab-VIEWS",
            ) { ViewsTab(it, platform) },
        )
    }
}

/** The Views tab: a strip of the listed views and the picked one's host. Empty, loading and error states are one line each. */
@Composable
fun ViewsTab(context: TabContext, platform: String) {
    val access = context.services.get(Services.VIEWS)
    if (access == null) {
        Line("Plugin views are not available here.", "views-unavailable")
        return
    }
    val listing by produceState<Result<List<ViewInfo>>?>(null, access) { value = runCatching { access.list() } }
    val result = listing
    when {
        result == null -> Line("Loading plugin views…", "views-loading")
        result.isFailure -> Line("Could not list plugin views: ${result.exceptionOrNull()?.message ?: "unknown error"}", "views-list-error")
        else -> {
            val views = tabViews(result.getOrThrow(), platform)
            if (views.isEmpty()) {
                Line("No plugin views on this daemon", "views-empty")
            } else {
                val picked = context.subTab(VIEWS_TAB_ID)
                val current = views.firstOrNull { "${it.module}/${it.id}" == picked } ?: views.first()
                Column(Modifier.fillMaxSize().testTag("views-tab")) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in views) FilterChip(
                            selected = v == current, onClick = { context.pickSubTab(VIEWS_TAB_ID, "${v.module}/${v.id}") },
                            label = { Text(v.title, maxLines = 1) }, modifier = Modifier.testTag("views-subtab-${v.module}-${v.id}"),
                        )
                    }
                    ViewHost(access, current.module, current.id, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, tag: String) {
    Text(text, Modifier.padding(16.dp).testTag(tag), style = MaterialTheme.typography.bodyMedium, color = Tones.color("muted"))
}

/**
 * The view host: opens [module]/[view] through [ViewAccess], builds each page through the block-type registry and draws
 * it. Loading, error (one line and Retry) and a page made for a newer app (one line banner) are all handled; it never throws.
 */
@Composable
fun ViewHost(access: ViewAccess, module: String, view: String, modifier: Modifier = Modifier, params: JsonObject = JsonObject(emptyMap())) {
    var attempt by remember { mutableStateOf(0) }
    val flow = remember(access, module, view, params, attempt) { access.open(module, view, params) }
    val state by flow.collectAsState(ViewState.Loading)
    ViewStateBody(state, module, view, modifier) { attempt++ }
}

/** Draws one [ViewState]; separate from [ViewHost] so a test can drive each state. */
@Composable
fun ViewStateBody(state: ViewState, module: String, view: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    when (state) {
        ViewState.Loading -> Line("Loading…", "view-loading")
        is ViewState.Error -> OutlinedCard(modifier.padding(12.dp).testTag("view-error")) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(state.line.lineSequence().firstOrNull().orEmpty(), Modifier.weight(1f), color = Tones.color("bad"), style = MaterialTheme.typography.bodyMedium)
                TextButton(onRetry, Modifier.testTag("view-retry")) { Text("Retry") }
            }
        }
        is ViewState.Page -> {
            val built = remember(state.rev, state.page, module, view) { ViewBuilder.build(module, view, state.page, ViewWidgets.types) }
            Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag("view-page"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (built.newerLevel) Text("made for a newer version of the app", Modifier.testTag("view-newer-banner"), color = Tones.color("warn"), style = MaterialTheme.typography.bodySmall)
                val root = built.root
                if (root == null) Line("This view could not be drawn.", "view-empty-page")
                else CompositionLocalProvider(LocalViewProps provides built.props) { root.Render(BlockScope(Modifier, null, blockId = root.id)) }
            }
        }
    }
}
