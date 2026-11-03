package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.superiorsoftllc.infantry.plugin.api.ConfigManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.config.ConfigPlan
import com.superiorsoftllc.infantry.ui.plugin.config.NODE_FIELD_CHECK_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.infantry.ui.shell.uiPrefsOverride
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA-CONFIG-3: the Settings dialog over the real plugin set, loaded the way the tab shots load it: the repository's
 * plugins/ root, the official jars built by `:superiorSoftLibrary:infantryUi:testOfficialJars`, devTrust (a dev build is not
 * signed with the Infantry key). Ui prefs are in memory; the daemon link and node-config store are fakes.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigDialogOfficialJarsUiTest {
    private var savedOverride: MutableMap<String, String>? = null

    @BeforeTest fun pinPrefs() {
        savedOverride = uiPrefsOverride
        uiPrefsOverride = mutableMapOf()
    }

    @AfterTest fun restore() {
        uiPrefsOverride = savedOverride
        Palette.theme = ThemeSettings()
    }

    private var manifest: ConfigManifest? = null
    private val api = object : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(emptyList<NodeConfigEntry>())
        override suspend fun put(entry: NodeConfigEntry) = Result.success(listOf(entry))
        override suspend fun manifest(host: String) = Result.success(manifest)
        override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> { this@ConfigDialogOfficialJarsUiTest.manifest = manifest; return Result.success(manifest) }
    }

    /** The modules the loader read from plugins/, in a TabFactory that stores its config in the fake. */
    private fun realPluginSet(): TabFactory {
        val root = File(requireNotNull(System.getProperty("infantry.qa.pluginsRoot")) { "run through :infantryUi:testOfficialJars" })
        val state = Files.createTempDirectory("infantry-official-jars").toFile()
        val env = mapOf(PluginFolders.ROOT_ENV to root.absolutePath)
        val loaded = DesktopPlugins.load(state, env = env::get, property = { if (it == PluginFolders.DEV_TRUST_PROPERTY) "true" else null }, print = {})
        val refused = loaded.factory.statuses.filter { it.name.startsWith("Infantry_") && it.state != ModuleState.LOADED }
        assertTrue(refused.isEmpty(), "official modules not loaded: ${refused.map { it.name + ": " + it.reason }}")
        return TabFactory(loaded.factory.modules, statuses = loaded.factory.statuses, configApi = api)
    }

    @Test fun theDialogHasTheRealTabsItsTrustSectionsAreLockedAndSearchFindsAFieldByKeyword() {
        val tabs = realPluginSet()
        withConfigDialog(tabs) {
            // Chat and Approvals are not tabs of their own: their settings are sections of Appearance (chat) and Agents (tool approval).
            for (tab in listOf(ConfigTab.APPEARANCE, ConfigTab.AGENTS)) onNodeWithTag(CONFIG_TAB_TAG_PREFIX + tab.name).assertExists()
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.APPEARANCE.name).performClick()
            waitForIdle()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.MARKDOWN_ENGINE.name).performScrollTo().assertExists()
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.AGENTS.name).performClick()
            waitForIdle()
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.TOOL_APPROVAL.name).performScrollTo().assertExists()
            // Trust sections: declared as locked block cards (nothing edits them), drawn once.
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
            waitForIdle()
            onAllNodesWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.ABOUT.name).assertCountEquals(1)
            val trust = tabs.configTree.declarations.filter { d ->
                ConfigEntry.entries.firstOrNull { configSectionNode(it) == d.declaration.block }
                    ?.let { it.tab in setOf(ConfigTab.SECRETS, ConfigTab.DEVICES, ConfigTab.ABOUT) && it !in ConfigPlan.MOVED } == true
            }
            assertTrue(trust.isNotEmpty(), "the trust sections are in the tree")
            assertEquals(emptyList(), trust.filter { !it.declaration.locked }.map { it.declaration.block }, "every trust section is locked")
            // Search: a keyword of the Theme card finds it and opens its tab.
            onNodeWithTag(CONFIG_SEARCH_TAG).performTextInput("colour")
            waitForIdle()
            onNodeWithTag(CONFIG_RESULT_TAG_PREFIX + ConfigEntry.THEME.name).performClick()
            waitForIdle()
            onNodeWithTag(NODE_FIELD_CHECK_TAG_PREFIX + "infantry.shell.theme/dark").performScrollTo().assertExists()
        }
    }
}
