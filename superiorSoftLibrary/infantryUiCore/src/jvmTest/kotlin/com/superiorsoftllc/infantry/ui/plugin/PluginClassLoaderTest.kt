// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Tier
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ticket-109d3f79: a community module's filtered parent shows the types the public plugin API hands out (TabContext.state is an
 * AppState, UiRegistrar takes ui.block.layout trees) but still hides DaemonLink and the window's Controller (a073edfa).
 */
class PluginClassLoaderTest {
    @Test
    fun `a community loader sees the plugin API's model and block types but not DaemonLink or the Controller`() {
        val dir = Files.createTempDirectory("plugin-loader").toFile()
        val jar = File(dir, "empty.jar")
        PluginClassLoader(dir, jar, javaClass.classLoader, filtered = true).use { loader ->
            for (name in listOf(
                "com.superiorsoftllc.infantry.ui.plugin.TabContext",
                "com.superiorsoftllc.infantry.ui.model.AppState",
                "com.superiorsoftllc.infantry.ui.model.DiffTarget",
                "com.superiorsoftllc.infantry.ui.block.layout.Block",
            )) assertEquals(name, loader.loadClass(name).name, "$name is part of the plugin API")
            for (name in listOf(
                "com.superiorsoftllc.infantry.ui.plugin.DaemonLink",
                "com.superiorsoftllc.infantry.ui.net.Controller",
            )) assertFailsWith<ClassNotFoundException>("$name must stay hidden from a community module") { loader.loadClass(name) }
        }
    }

    /**
     * UI-FREEZE-NIGHTLY-1 (ticket 5fd5ba18): the shared progress seam (ProgressBar / ProgressView / ProgressIndicator) must load from
     * every filtered tier. Hidden, Infantry_Settings' ProgressStrip and Infantry_Runners' AddRunnerForm threw NoClassDefFoundError in
     * composition and froze the window on Plugins > Refresh and Runners > Connect.
     */
    @Test
    fun `community and official loaders see the shared progress views`() {
        val dir = Files.createTempDirectory("plugin-loader").toFile()
        for (tier in listOf(Tier.COMMUNITY, Tier.OFFICIAL)) PluginClassLoader(dir, File(dir, "empty.jar"), javaClass.classLoader, tier).use { loader ->
            val name = "com.superiorsoftllc.infantry.ui.progress.ProgressViewsKt"
            assertEquals(name, loader.loadClass(name).name, "$tier: the progress seam is part of the plugin surface")
            assertFailsWith<ClassNotFoundException>("$tier: the daemon link stays hidden") { loader.loadClass("com.superiorsoftllc.infantry.ui.plugin.DaemonLink") }
        }
    }

    @Test
    fun `a community loader sees ConfigValues and nothing else in ui shell`() {
        val dir = Files.createTempDirectory("plugin-loader").toFile()
        PluginClassLoader(dir, File(dir, "empty.jar"), javaClass.classLoader, Tier.COMMUNITY).use { loader ->
            val name = "com.superiorsoftllc.infantry.ui.shell.ConfigValues"
            assertEquals(name, loader.loadClass(name).name)
            for (hidden in listOf("com.superiorsoftllc.infantry.ui.shell.ConfigContext", "com.superiorsoftllc.infantry.ui.shell.EmptyConfig", "${name}Fake"))
                assertFailsWith<ClassNotFoundException>("$hidden must stay hidden") { loader.loadClass(hidden) }
        }
    }

    @Test
    fun `an official loader also sees ui shell, the ui root kit and the host types official jars link, a community loader does not, and the daemon client stays hidden from both`() {
        val dir = Files.createTempDirectory("plugin-loader").toFile()
        val jar = File(dir, "empty.jar")
        // e063ce77: the Controller, ImageUploadOutcome, ui.sound and ui.voice are what the official jars link (owner's boot crash).
        val shell = listOf("com.superiorsoftllc.infantry.ui.shell.ShellContext", "com.superiorsoftllc.infantry.ui.ChatPanelState",
            "com.superiorsoftllc.infantry.ui.net.Controller", "com.superiorsoftllc.infantry.ui.net.ImageUploadOutcome",
            "com.superiorsoftllc.infantry.ui.sound.IncomingCommsSoundSettings", "com.superiorsoftllc.infantry.ui.voice.VoiceSettings")
        PluginClassLoader(dir, jar, javaClass.classLoader, Tier.OFFICIAL).use { loader ->
            for (name in shell) assertEquals(name, loader.loadClass(name).name, "$name must be visible to an official jar")
            assertFailsWith<ClassNotFoundException> { loader.loadClass("com.superiorsoftllc.infantry.ui.net.DaemonClient") }
            assertFailsWith<ClassNotFoundException> { loader.loadClass("com.superiorsoftllc.infantry.ui.plugin.DaemonLink") }
        }
        PluginClassLoader(dir, jar, javaClass.classLoader, Tier.COMMUNITY).use { loader ->
            for (name in shell) assertFailsWith<ClassNotFoundException>(name) { loader.loadClass(name) }
        }
    }
}
