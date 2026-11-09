// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins
import java.io.File
import kotlin.test.Test
import androidx.compose.ui.test.onAllNodesWithTag
import kotlin.test.assertTrue

/**
 * MASCOT-LIVE-1: the desktop loads Infantry_Mascot from its built jar (the path the real window takes, not the compiled-in
 * module): the module loads, and `agent.avatar.renderers/mascot` is registered with a renderer.
 */
class MascotLoadedFromJarTest {
    @Test fun theDesktopLoaderRegistersTheMascotStyleFromTheJar() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins") }.first { File(it, "official/Infantry_Mascot").isDirectory }
        val dir = java.nio.file.Files.createTempDirectory("mascot-load").toFile()
        val env = mapOf(PluginFolders.ROOT_ENV to root.absolutePath)
        val set = DesktopPlugins.load(dir, env = env::get, property = { if (it == PluginFolders.DEV_TRUST_PROPERTY) "true" else null })
        val tabs = set.factory
        val st = tabs.statuses.firstOrNull { it.name == "Infantry_Mascot" }
        
        
        assertTrue(tabs.implementations.has(AGENT_AVATAR_RENDERERS, "mascot"), "mascot registered by the jar-loaded module")
        assertTrue(tabs.implementations.avatarStyleRenderer("mascot") != null)
    }
}

/** MASCOT-LIVE-1: the jar-loaded renderer drawn through the live [AgentAvatar] path with the style set. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class MascotJarDrawTest {
    @Test fun theJarLoadedMascotIsDrawnByAgentAvatarNotThePortrait() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins") }.first { File(it, "official/Infantry_Mascot").isDirectory }
        val dir = java.nio.file.Files.createTempDirectory("mascot-draw").toFile()
        val env = mapOf(PluginFolders.ROOT_ENV to root.absolutePath)
        val tabs = DesktopPlugins.load(dir, env = env::get, property = { if (it == PluginFolders.DEV_TRUST_PROPERTY) "true" else null }).factory
        androidx.compose.ui.test.runDesktopComposeUiTest {
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalTabFactory provides tabs,
                    LocalAgentAvatarStyle provides "mascot",
                ) { androidx.compose.foundation.layout.Row { listOf("BUSY", "IDLE", "WAITING_INPUT", "EXITED").forEachIndexed { i, s -> AgentAvatar(AvatarRequest(role = "dev$i", sizeDp = 64, state = s)) } } }
            }
            waitForIdle()
            val portrait = onAllNodesWithTag(AVATAR_TAG).fetchSemanticsNodes().size
            println("MASCOT-LIVE portraitNodes=$portrait faults=${com.superiorsoftllc.infantry.ui.plugin.PluginFaults.all.map { it.module + ":" + it.message }}")
            assertTrue(portrait == 0, "portrait drawn instead of the mascot")
        }
    }
}

