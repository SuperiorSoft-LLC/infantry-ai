// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.plugins.official.agents.AgentRow
import com.superiorsoftllc.infantry.ui.block.layout.OverrideHealth
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.RolePortrait
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.PluginFaults
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AVATAR-SEAM-1: an agent avatar style is a registered implementation, chosen by a preference, with the built-in
 * portrait as the fallback. A plugin's renderer draws when it is registered and chosen; with no plugin, a style nobody
 * registered, or a renderer that throws, the portrait still resolves (every existing avatar keeps its look).
 */
@OptIn(ExperimentalTestApi::class)
class AgentAvatarSeamUiTest {
    /** A style of a test plugin: it draws its own tag naming the state it was handed, in the requested size. */
    private object FakeAvatar : AgentAvatarRenderer {
        @Composable
        override fun Draw(request: AvatarRequest, modifier: Modifier) {
            androidx.compose.foundation.layout.Box(modifier.size(request.sizeDp.dp).background(Color.Magenta).testTag(FAKE_TAG_PREFIX + request.state))
        }
    }

    /** A style that cannot draw: the avatar must fall back to the portrait and the module must be reported. */
    private object BrokenAvatar : AgentAvatarRenderer {
        @Composable
        override fun Draw(request: AvatarRequest, modifier: Modifier) {
            error("this style cannot draw")
        }
    }

    private val session = SessionRecord(id = "1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p")
    private val pm = AgentView(session = session, role = "pm")
    private val state = AppState(agents = mapOf(pm.session.id to pm))

    private fun styleModule(name: String, key: String, renderer: AgentAvatarRenderer) = InfantryModule(
        ModuleManifest(name = name, version = "1.0.0", implementations = listOf(ImplementationDeclaration(AGENT_AVATAR_RENDERERS, key))),
        tier = Tier.OFFICIAL,
        implementations = mapOf(AGENT_AVATAR_RENDERERS to mapOf(key to renderer)),
    )

    @BeforeTest
    fun clean() { OverrideHealth.clear(); PluginFaults.reset() }

    @AfterTest
    fun after() { OverrideHealth.clear(); PluginFaults.reset() }

    @Test
    fun theChosenStyleDrawsTheRegisteredRendererWithTheSessionState() {
        val factory = TabFactory(CoreModules.all + styleModule("Acme_Avatar", "fake", FakeAvatar))
        runDesktopComposeUiTest(300, 300) {
            setContent {
                CompositionLocalProvider(LocalTabFactory provides factory, LocalAgentAvatarStyle provides "fake") {
                    AgentAvatar(pm, state)
                }
            }
            onNodeWithTag(FAKE_TAG_PREFIX + SessionState.BUSY.name).assertExists()
            assertEquals(0, onAllNodesWithTag(AVATAR_TAG).fetchSemanticsNodes().size, "the chosen style draws, not the portrait")
        }
    }

    @Test
    fun theDefaultStyleIsThePortraitTheAppDrawsToday() {
        val factory = TabFactory(CoreModules.all + styleModule("Acme_Avatar", "fake", FakeAvatar))
        runDesktopComposeUiTest(300, 300) {
            setContent { CompositionLocalProvider(LocalTabFactory provides factory) { AgentAvatar(pm, state) } }
            val portrait = onNodeWithTag(AVATAR_TAG).fetchSemanticsNode().config.getOrNull(PortraitKey)
            assertEquals(RolePortrait.PM.key, portrait)
            assertEquals(0, onAllNodesWithTag(FAKE_TAG_PREFIX + SessionState.BUSY.name).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aStyleWhosePluginIsGoneFallsBackToThePortrait() {
        // The preference still says "fake", but the module that registered it is not loaded (disabled, removed).
        val factory = TabFactory(CoreModules.all)
        runDesktopComposeUiTest(300, 300) {
            setContent {
                CompositionLocalProvider(LocalTabFactory provides factory, LocalAgentAvatarStyle provides "fake") { AgentAvatar(pm, state) }
            }
            assertEquals(RolePortrait.PM.key, onNodeWithTag(AVATAR_TAG).fetchSemanticsNode().config.getOrNull(PortraitKey))
        }
    }

    @Test
    fun aRendererThatThrowsDrawsThePortraitAndIsReported() {
        val factory = TabFactory(CoreModules.all + styleModule("Acme_Broken", "broken", BrokenAvatar))
        runDesktopComposeUiTest(300, 300) {
            setContent {
                CompositionLocalProvider(LocalTabFactory provides factory, LocalAgentAvatarStyle provides "broken") { AgentAvatar(pm, state) }
            }
            // The guard probes the renderer once, then draws the portrait for good.
            waitForIdle()
            assertEquals(RolePortrait.PM.key, onNodeWithTag(AVATAR_TAG).fetchSemanticsNode().config.getOrNull(PortraitKey))
            assertTrue(OverrideHealth.faults.keys.any { it.contains(AGENT_AVATAR_RENDERERS) }, OverrideHealth.faults.toString())
            assertTrue(PluginFaults.all.any { it.module == "Acme_Broken" }, PluginFaults.all.toString())
        }
    }

    @Test
    fun theRoleSiteKeepsDrawingThePortraitByDefault() {
        val factory = TabFactory(CoreModules.all)
        runDesktopComposeUiTest(300, 300) {
            setContent { CompositionLocalProvider(LocalTabFactory provides factory) { AgentAvatar("qa", "1", 22) } }
            assertEquals(RolePortrait.QA.key, onNodeWithTag(AVATAR_TAG).fetchSemanticsNode().config.getOrNull(PortraitKey))
        }
    }

    /** Evidence for the DONE report: the real agents-list row with the built-in style and with a plugin style, side by side. */
    @Test
    fun captureTheSeamInTheAgentsList() {
        val roles = listOf("architect", "frontend", "qa", "pm")
        val agents = roles.mapIndexed { i, role -> pm.copy(session = pm.session.copy(id = "a$i"), role = role) }
        val all = AppState(agents = agents.associateBy { it.session.id })
        val factory = TabFactory(CoreModules.all + styleModule("Acme_Avatar", "fake", FakeAvatar))
        runDesktopComposeUiTest(700, 260) {
            setContent {
                Row(Modifier.background(Palette.Bg).testTag(SHOT_TAG)) {
                    CompositionLocalProvider(LocalTabFactory provides factory) {
                        Column(Modifier.width(300.dp)) { agents.forEach { AgentRow(it, all, it.role == "pm", onClick = {}, onRemove = {}) } }
                    }
                    CompositionLocalProvider(LocalTabFactory provides factory, LocalAgentAvatarStyle provides "fake") {
                        Column(Modifier.width(300.dp)) { agents.forEach { AgentRow(it, all, it.role == "pm", onClick = {}, onRemove = {}) } }
                    }
                }
            }
            waitForIdle()
            val out = File("build/reports/avatar-seam-1/agents-list-styles.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag(SHOT_TAG).captureToImage().toAwtImage(), "png", out)
            println("AVATAR-SEAM-1 screenshot: ${out.absolutePath}")
        }
    }

    private companion object {
        const val FAKE_TAG_PREFIX = "fake-avatar-"
        const val SHOT_TAG = "avatar-seam-shot"
    }
}