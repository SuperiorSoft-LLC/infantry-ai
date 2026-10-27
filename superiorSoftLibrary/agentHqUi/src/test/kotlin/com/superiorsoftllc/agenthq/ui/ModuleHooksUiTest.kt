package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.BlockDeclaration
import com.superiorsoftllc.agenthq.plugin.api.InterceptorDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.PanelDeclaration
import com.superiorsoftllc.agenthq.plugin.api.PreferenceDeclaration
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedPreference
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.plugins.core.InfantryGit
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.InterceptorKinds
import com.superiorsoftllc.agenthq.ui.plugin.PanelProvider
import com.superiorsoftllc.agenthq.ui.plugin.PanelSections
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.SlotBlock
import com.superiorsoftllc.agenthq.ui.plugin.Slots
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.TabInterceptor
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/** HOOKS-1: what module.json hooks draw: both container slots, each interceptor kind, a content wrapper, a panel preference. */
@OptIn(ExperimentalTestApi::class)
class ModuleHooksUiTest {
    private fun tag(t: String): @androidx.compose.runtime.Composable (TabContext) -> Unit = { Text(t, Modifier.testTag(t)) }

    private val module = AgentHqModule(
        ModuleManifest(
            "Acme_Hooks", "1.0.0", author = "Acme", license = "NOASSERTION",
            tabs = listOf(TabDeclaration("acme.hooks", TabContainers.CENTER)),
            panels = listOf(PanelDeclaration("acme.agents", Panels.LEFT)),
            preferences = listOf(PreferenceDeclaration("infantry.agents", "acme.agents")),
            interceptors = listOf(
                InterceptorDeclaration("acme.hooks", "early", InterceptorKinds.BEFORE),
                InterceptorDeclaration("acme.hooks", "late", InterceptorKinds.AFTER),
                InterceptorDeclaration("acme.hooks", "wrap", InterceptorKinds.AROUND),
            ),
            blocks = listOf(BlockDeclaration(Slots.CENTER_TOP, "top"), BlockDeclaration(Slots.CENTER_BOTTOM, "bottom")),
        ),
        tabs = listOf(TabProvider("acme.hooks", TabContainers.CENTER, 500, { "Hooks" }, InfantryGit.tabs.single().icon, "center-tab-HOOKS", content = tag("content"))),
        interceptors = mapOf(
            "early" to TabInterceptor(before = tag("early-before"), after = tag("early-after-not-run")),
            "late" to TabInterceptor(before = tag("late-before-not-run"), after = tag("late-after")),
            "wrap" to TabInterceptor(content = { _, proceed -> Column(Modifier.testTag("wrapper")) { proceed() } }),
        ),
        blocks = mapOf("top" to SlotBlock(tag("block-top")), "bottom" to SlotBlock(tag("block-bottom"))),
        tier = Tier.COMMUNITY,
        panels = listOf(PanelProvider("acme.agents", Panels.LEFT, 999, "acme-agents", content = tag("acme-agents-content"))),
    )

    @Test fun hooksDrawWhereTheirKindAndSlotSay() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = TabFactory(CoreModules.all + module, preferences = listOf(AcceptedPreference("Acme_Hooks", "infantry.agents", "acme.agents")))
        val context = TabContext(AppState(), Controller(scope), open = {}, showChat = {}, insertIntoChat = {}, diffTarget = null, onOpenDiff = {}, transcript = {})
        setContent {
            Column {
                f.Content("acme.hooks", context)
                PanelSections(f, Panels.LEFT, context)
            }
        }
        listOf("block-top", "early-before", "content", "late-after", "block-bottom", "acme-agents-content").forEach { onNodeWithTag(it).assertExists() }
        onNodeWithTag("wrapper").assertExists()
        onNodeWithTag("early-after-not-run").assertDoesNotExist()
        onNodeWithTag("late-before-not-run").assertDoesNotExist()
        // The replacement section sits under the core section's tag.
        onNodeWithTag("left-panel-agents").assertExists()
        scope.cancel()
    }
}
