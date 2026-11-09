// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ActionResult
import com.superiorsoftllc.infantry.plugin.api.HitView
import com.superiorsoftllc.infantry.plugin.api.InspectOutcome
import com.superiorsoftllc.infantry.plugin.api.PluginManagerPort
import com.superiorsoftllc.infantry.plugin.api.PluginView
import com.superiorsoftllc.infantry.plugin.api.RefreshView
import com.superiorsoftllc.infantry.plugin.api.SourceView
import com.superiorsoftllc.infantry.plugin.api.UpdateView
import com.superiorsoftllc.infantry.plugins.official.DISABLE_CONFIRM_CANCEL_TAG
import com.superiorsoftllc.infantry.plugins.official.DISABLE_CONFIRM_OK_TAG
import com.superiorsoftllc.infantry.plugins.official.DISABLE_CONFIRM_TAG
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_FILTER_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_QUERY_TAG
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_SECTION_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_DETAILS_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_ENABLE_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_EXPAND_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_PENDING_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_ROW_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_REFUSED_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PluginsScreen
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val rowMatcher = androidx.compose.ui.test.SemanticsMatcher("plugin row") { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith(PLUGIN_ROW_PREFIX) == true }

/** A [PluginManagerPort] over plain lists; [gate] (when set) holds setEnabled until released, to see the pending state. */
internal class FakePluginPort(var rows: List<PluginView>) : PluginManagerPort {
    val toggles = mutableListOf<Pair<String, Boolean>>()
    @Volatile var gate: CountDownLatch? = null
    override fun list() = rows
    override fun updates() = listOf(UpdateView("Acme_Weather", "0.9.0", "1.0.0", emptyList()))
    override fun search(query: String) = listOf(
        HitView("Acme_Weather", "community-index", "community", "1.0.0", "Shows the local forecast in the status bar.", "MIT", true),
        HitView("Acme_Pomodoro", "community-index", "community", "2.1.0", "A focus timer with breaks, in the right panel.", "Apache-2.0", false),
        HitView("Infantry_News", "official", "official", "1.0.0", "Headlines from the project's feeds.", "Apache-2.0", false),
    )
    override fun inspect(ref: String) = InspectOutcome(error = "not in this test")
    override fun install(ref: String, consent: Set<String>, token: String?) = ActionResult(false, "not in this test")
    override fun setEnabled(id: String, enabled: Boolean): ActionResult {
        gate?.await(10, TimeUnit.SECONDS)
        toggles += id to enabled
        rows = rows.map { if (it.id == id) it.copy(enabled = enabled) else it }
        return ActionResult(true, "${id} is ${if (enabled) "on" else "off"}; restart to apply.", restartNeeded = true)
    }
    override fun update(id: String, consent: Set<String>?) = ActionResult(true, "updated")
    override fun rollback(id: String) = ActionResult(true, "rolled back")
    override fun remove(id: String) = ActionResult(true, "removed")
    override fun sources() = listOf(
        SourceView("official", "official", "https://plugins.infantry.ai/catalog.json", true),
        SourceView("community-index", "community", "https://github.com/infantry-ai/community-plugins/catalog.json", true),
        SourceView("my-folder", "custom", "C:\\plugins\\local", true),
    )
    override fun addSource(id: String, cls: String, location: String) = ActionResult(true, "added")
    override fun removeSource(id: String) = ActionResult(true, "removed")
    override fun refresh() = listOf(RefreshView("official", true, false, 14, "ok"), RefreshView("community-index", true, false, 37, "ok"), RefreshView("my-folder", false, true, 0, "folder not found"))
}

internal fun samplePlugins() = listOf(
    PluginView("Infantry_Agents", "1.0.0", "official", true, true, null, null, emptyList(), emptyList(), false, null,
        "The left panel's agents list.", listOf("panel left: infantry.agents"), listOf("Infantry_Chat")),
    PluginView("Infantry_Alerts", "1.0.0", "official", true, true, "Apache-2.0", null, emptyList(), emptyList(), false, null,
        "Toasts and the alert centre for failed jobs and asks.", listOf("tab infantry.alerts")),
    PluginView("Infantry_Appearance", "1.0.0", "official", true, true, null, null, emptyList(), emptyList(), false, null,
        "Theme, accent colour and density.", listOf("tab infantry.settings.appearance")),
    PluginView("Infantry_Approvals", "1.0.0", "official", true, false, null, null, emptyList(), emptyList(), false, null, "", emptyList()),
    PluginView("Acme_Weather", "0.9.0", "community", false, true, "MIT", null, listOf("net.http", "ui.statusbar"), listOf("ui.statusbar"), true, null,
        "Shows the local forecast in the status bar.", listOf("block infantry.shell.footer: weather")),
    PluginView("Acme_Notes", "2.0.0", "custom", false, false, "Apache-2.0", "Acme Corp", emptyList(), emptyList(), false, null,
        "Personal scratch notes per project.", listOf("tab acme.notes")),
)

/** PLUGINS-UI-1: the Installed list's search, filter chips, confirm-before-off and pending switch. */
@OptIn(ExperimentalTestApi::class)
class PluginsInstalledListUiTest {
    private fun SemanticsNodeInteractionsProvider.rows() = onAllNodes(rowMatcher).fetchSemanticsNodes().size

    @Test fun `filter chips and search narrow the list`() = runDesktopComposeUiTest(1000, 900) {
        setContent { InfantryTheme { PluginsScreen(FakePluginPort(samplePlugins())) } }
        waitUntil(timeoutMillis = 10_000) { rows() == 6 }
        onNodeWithTag(PLUGINS_FILTER_PREFIX + "Disabled").performClick()
        waitUntil(timeoutMillis = 5_000) { rows() == 2 }
        onNodeWithTag(PLUGINS_FILTER_PREFIX + "User").performClick()
        waitUntil(timeoutMillis = 5_000) { rows() == 2 }
        onNodeWithTag(PLUGINS_FILTER_PREFIX + "All").performClick()
        onNodeWithTag(PLUGINS_QUERY_TAG).performTextInput("forecast")
        waitUntil(timeoutMillis = 5_000) { rows() == 1 }
        onNodeWithTag(PLUGIN_ROW_PREFIX + "Acme_Weather").assertExists()
    }

    @Test fun `details open on the chevron`() = runDesktopComposeUiTest(1000, 900) {
        setContent { InfantryTheme { PluginsScreen(FakePluginPort(samplePlugins())) } }
        waitUntil(timeoutMillis = 10_000) { rows() == 6 }
        assertEquals(0, onAllNodesWithTag(PLUGIN_DETAILS_PREFIX + "Infantry_Agents").fetchSemanticsNodes().size)
        onNodeWithTag(PLUGIN_EXPAND_PREFIX + "Infantry_Agents").performClick()
        onNodeWithTag(PLUGIN_DETAILS_PREFIX + "Infantry_Agents").assertExists()
    }

    @Test fun `turning off a bundled official plugin asks first, then shows pending until applied`() = runDesktopComposeUiTest(1000, 900) {
        val port = FakePluginPort(samplePlugins())
        setContent { InfantryTheme { PluginsScreen(port) } }
        waitUntil(timeoutMillis = 10_000) { rows() == 6 }
        onNodeWithTag(PLUGIN_ENABLE_PREFIX + "Infantry_Agents").performClick()
        onNodeWithTag(DISABLE_CONFIRM_TAG).assertExists()
        onNodeWithTag(DISABLE_CONFIRM_CANCEL_TAG).performClick()
        onNodeWithTag(PLUGIN_ENABLE_PREFIX + "Infantry_Agents").assertIsOn()
        assertTrue(port.toggles.isEmpty())

        port.gate = CountDownLatch(1)
        onNodeWithTag(PLUGIN_ENABLE_PREFIX + "Infantry_Agents").performClick()
        onNodeWithTag(DISABLE_CONFIRM_OK_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PLUGIN_PENDING_PREFIX + "Infantry_Agents").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(PLUGIN_ENABLE_PREFIX + "Infantry_Agents").assertIsOff()
        port.gate!!.countDown()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(PLUGIN_PENDING_PREFIX + "Infantry_Agents").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("Infantry_Agents" to false), port.toggles)
        onNodeWithTag(PLUGIN_ENABLE_PREFIX + "Infantry_Agents").assertIsOff()
    }

    @Test fun `a user plugin turns on without asking`() = runDesktopComposeUiTest(1000, 900) {
        val port = FakePluginPort(samplePlugins())
        setContent { InfantryTheme { PluginsScreen(port) } }
        waitUntil(timeoutMillis = 10_000) { rows() == 6 }
        onNodeWithTag(PLUGIN_ENABLE_PREFIX + "Acme_Notes").performClick()
        waitUntil(timeoutMillis = 10_000) { port.toggles.isNotEmpty() }
        assertEquals(0, onAllNodesWithTag(DISABLE_CONFIRM_TAG).fetchSemanticsNodes().size)
        assertEquals(listOf("Acme_Notes" to true), port.toggles)
    }

    /** MASCOT-LICENSE-1: a plugin the loader refuses shows "Refused: <reason>" in its row, not an ON toggle. */
    @Test fun `a refused plugin shows its reason instead of a toggle`() = runDesktopComposeUiTest(1000, 900) {
        val refused = PluginView("Infantry_Mascot", "1.0.0", "community", false, true, null, null, emptyList(), emptyList(), false, null,
            "Mascot avatars.", emptyList(), refused = "community module without a license")
        val port = FakePluginPort(samplePlugins() + refused)
        setContent { InfantryTheme { PluginsScreen(port) } }
        waitUntil(timeoutMillis = 10_000) { rows() == 7 }
        onNodeWithTag(PLUGIN_REFUSED_PREFIX + "Infantry_Mascot").assertTextEquals("Refused: community module without a license")
        assertEquals(0, onAllNodesWithTag(PLUGIN_ENABLE_PREFIX + "Infantry_Mascot").fetchSemanticsNodes().size)
        onNodeWithTag(PLUGIN_ENABLE_PREFIX + "Acme_Notes").assertExists()
    }
}

/**
 * PLUGINS-UI-1 preview: PNGs of Installed (collapsed, one open), Browse and Sources, wide and phone, dark and light,
 * under build/plugins-shots. Asserts nothing; run with -Pshots or by name.
 */
@OptIn(ExperimentalTestApi::class)
class PluginsScreenShots {
    @Test fun all() {
        val dir = File("build/plugins-shots").apply { mkdirs() }
        for ((mode, dark) in listOf("dark" to true, "light" to false)) for ((size, w, h) in listOf(Triple("wide", 1100, 760), Triple("phone", 400, 860))) {
            for (shot in listOf("installed", "installed-open", "browse", "sources")) {
                Palette.theme = ThemeSettings(dark = dark)
                try {
                    runDesktopComposeUiTest(w, h) {
                        val port = FakePluginPort(samplePlugins())
                        setContent { InfantryTheme { Box(Modifier.fillMaxSize().background(Palette.Bg)) { PluginsScreen(port) } } }
                        waitUntil(timeoutMillis = 10_000) { onAllNodes(rowMatcher).fetchSemanticsNodes().isNotEmpty() }
                        when (shot) {
                            "installed-open" -> { onNodeWithTag(PLUGINS_FILTER_PREFIX + "User").performClick(); waitForIdle(); onNodeWithTag(PLUGIN_EXPAND_PREFIX + "Acme_Weather").performClick() }
                            "browse" -> onNodeWithTag(PLUGINS_SECTION_PREFIX + "Browse").performClick()
                            "sources" -> {
                                onNodeWithTag(PLUGINS_SECTION_PREFIX + "Sources").performClick()
                                waitForIdle()
                                onNodeWithTag(com.superiorsoftllc.infantry.plugins.official.SOURCE_REFRESH_TAG).performClick()
                            }
                        }
                        waitForIdle()
                        Thread.sleep(300); waitForIdle()
                        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "plugins-$shot-$mode-$size.png"))
                    }
                } finally { Palette.theme = ThemeSettings() }
            }
        }
    }
}
