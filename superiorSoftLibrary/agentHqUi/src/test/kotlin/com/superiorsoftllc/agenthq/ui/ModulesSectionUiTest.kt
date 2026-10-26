package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.DependencyDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleChange
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_CASCADE_TAG
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_MESSAGE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_NEEDS_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_REQUIRED_BY_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_TOGGLE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.ModuleChanges
import com.superiorsoftllc.agenthq.ui.plugin.ModuleRequest
import com.superiorsoftllc.agenthq.ui.plugin.ModulesSection
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** PLUGINS-2: Settings > Plugins > Modules shows needs / required by, and shows the rules' answer under the row. */
@OptIn(ExperimentalTestApi::class)
class ModulesSectionUiTest {
    private val cli = ModuleManifest("Infantry_Cli", "1.0.0")
    private val hello = ModuleManifest("Acme_Hello", "1.0.0", author = "Acme", license = "NOASSERTION", dependencies = listOf(DependencyDeclaration("Infantry_Cli", ">=1.0 <2")))
    private val statuses = listOf(
        ModuleStatus("Infantry_Cli", Tier.CORE, ModuleState.LOADED, manifest = cli),
        ModuleStatus("Acme_Hello", Tier.COMMUNITY, ModuleState.LOADED, manifest = hello),
    )

    @Test
    fun `needs and required-by are shown, a refusal reads under the row, cascade is off by default`() = runDesktopComposeUiTest(900, 600) {
        val asked = mutableListOf<ModuleRequest>()
        val changes = ModuleChanges { r ->
            asked += r
            if (r.module == "Infantry_Cli" && !r.cascade) ModuleChange.Refused("Cannot disable Infantry_Cli: enabled modules depend on it: Acme_Hello.", dependents = listOf("Acme_Hello"))
            else ModuleChange.Applied(ModulesConfig(), listOf("Acme_Hello", "Infantry_Cli"), "Infantry_Cli disabled; cascade also disabled Acme_Hello. Restart to apply.")
        }
        setContent { ModulesSection(TabFactory(emptyList(), statuses = statuses, changes = changes)) }

        onNodeWithTag(MODULE_NEEDS_TAG_PREFIX + "Acme_Hello").assertTextEquals("needs: Infantry_Cli >=1.0 <2")
        onNodeWithTag(MODULE_REQUIRED_BY_TAG_PREFIX + "Infantry_Cli").assertTextEquals("required by: Acme_Hello")
        onNodeWithTag(MODULE_CASCADE_TAG).assertIsOff()

        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").performClick()
        onNodeWithTag(MODULE_MESSAGE_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Cannot disable Infantry_Cli: enabled modules depend on it: Acme_Hello.")
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Disable")

        onNodeWithTag(MODULE_CASCADE_TAG).performClick()
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").performClick()
        assertEquals(ModuleRequest("Infantry_Cli", enable = false, cascade = true), asked.last())
        // Both rows now offer Enable: the change is in modules.json, waiting for a restart.
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Enable")
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Acme_Hello").assertTextEquals("Enable")
    }
}
