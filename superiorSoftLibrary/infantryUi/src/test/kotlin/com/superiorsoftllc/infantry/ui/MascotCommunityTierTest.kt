// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins
import java.io.File
import androidx.compose.ui.test.onAllNodesWithTag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MASCOT-PICKER-1 (ticket f37b281a): Infantry_Mascot installed from the catalog lands under `community/`, so its jar loads
 * through the filtered community class loader, not the official one MascotLoadedFromJarTest covers. The module must
 * still register `agent.avatar.renderers/mascot`, and the renderer must resolve, so Appearance > Avatar style lists it.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class MascotCommunityTierTest {
    @Test fun aCommunityInstalledMascotRegistersItsAvatarStyle() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins") }.first { File(it, "official/Infantry_Mascot").isDirectory }
        val source = File(repo, "official/Infantry_Mascot")
        val root = java.nio.file.Files.createTempDirectory("mascot-community-root").toFile()
        val dir = File(root, "community/Infantry_Mascot").apply { mkdirs() }
        File(source, "module.json").copyTo(File(dir, "module.json"))
        File(source, "build/libs/Infantry_Mascot.jar").copyTo(File(dir, "build/libs/Infantry_Mascot.jar"))
        File(root, "modules.json").writeText(
            """{ "folders": [ { "path": "community", "tier": "community" } ], "modules": { "Infantry_Mascot": { "enabled": true } } }""",
        )
        val state = java.nio.file.Files.createTempDirectory("mascot-community-state").toFile()
        val env = mapOf(PluginFolders.ROOT_ENV to root.absolutePath)
        val tabs = DesktopPlugins.load(state, env = env::get).factory
        val st = tabs.statuses.firstOrNull { it.name == "Infantry_Mascot" }
        val detail = "status=${st?.state}/${st?.tier} ${st?.reason} problems=${tabs.problems + tabs.implementations.problems}"
        assertTrue(tabs.implementations.has(AGENT_AVATAR_RENDERERS, "mascot"), "mascot registered by the community-loaded module: $detail")
        assertTrue(tabs.implementations.avatarStyleRenderer("mascot") != null, detail)
        assertTrue("mascot" in tabs.implementations.keys(AGENT_AVATAR_RENDERERS), "the Avatar style picker lists the mascot: $detail")
        // Drawn through the live AgentAvatar path: a host class the renderer touches only while composing would fault here.
        androidx.compose.ui.test.runDesktopComposeUiTest {
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(LocalTabFactory provides tabs, LocalAgentAvatarStyle provides "mascot") {
                    androidx.compose.foundation.layout.Row { listOf("BUSY", "IDLE").forEachIndexed { i, s -> AgentAvatar(AvatarRequest(role = "dev$i", sizeDp = 64, state = s)) } }
                }
            }
            waitForIdle()
            val faults = com.superiorsoftllc.infantry.ui.plugin.PluginFaults.all.filter { it.module == "Infantry_Mascot" }.map { it.message }
            assertTrue(faults.isEmpty(), "the community mascot drew without a fault: $faults")
            assertTrue(onAllNodesWithTag(AVATAR_TAG).fetchSemanticsNodes().isEmpty(), "portrait drawn instead of the mascot")
        }
    }
}
