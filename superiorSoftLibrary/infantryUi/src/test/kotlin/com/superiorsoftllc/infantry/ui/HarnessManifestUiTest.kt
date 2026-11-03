// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import com.superiorsoftllc.infantry.ui.plugin.MODULE_HARNESS_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.ModulesSection
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * HARNESS-PLUGIN-4 H5a: the harness icon comes from the loaded module's manifest (`harness.icon`), for its ids and aliases,
 * and Plugins > Modules shows the module's harness with the daemon's state of it (`GET /plugins` `harness`). Uses the shipped
 * example module plugins/examples/Acme_EchoHarness (its module.json and icons/echo.svg).
 */
@OptIn(ExperimentalTestApi::class)
class HarnessManifestUiTest {
    private val echoDir: File = listOf(File("../../plugins/examples/Acme_EchoHarness"), File("plugins/examples/Acme_EchoHarness")).first { it.isDirectory }.canonicalFile
    private val echo = ModuleStatus(
        "Acme_EchoHarness", Tier.COMMUNITY, ModuleState.LOADED,
        manifest = PluginJson.decodeFromString(ModuleManifest.serializer(), File(echoDir, "module.json").readText()),
        dir = echoDir.path,
    )
    private val echoIcon = "manifest:" + echoDir.path.trimEnd('/', '\\') + "/icons/echo.svg"

    private fun paints(key: String) = SemanticsMatcher("harness icon paints $key") { n -> n.config.any { (k, v) -> k.name == "HarnessIconKey" && v == key } }

    @Test
    fun `a module card shows its harness with the manifest icon and the daemon's state`() = runDesktopComposeUiTest(900, 600) {
        val factory = TabFactory(emptyList(), statuses = listOf(echo))
        setContent {
            CompositionLocalProvider(LocalTabFactory provides factory) {
                ModulesSection(factory, harnessStatus = mapOf("Acme_EchoHarness" to "loaded"))
            }
        }
        onNodeWithTag(MODULE_HARNESS_TAG_PREFIX + "Acme_EchoHarness").assertExists()
        onNode(hasText("Harness: echo · daemon: loaded")).assertExists()
        onNodeWithTag(harnessIconTag("echo")).assert(paints(echoIcon))
    }

    @Test
    fun `a refused harness reads the daemon's reason, and without a daemon read only the ids show`() = runDesktopComposeUiTest(900, 600) {
        val factory = TabFactory(emptyList(), statuses = listOf(echo))
        val status = androidx.compose.runtime.mutableStateOf<Map<String, String>?>(mapOf("Acme_EchoHarness" to "refused: missing grant 'harness.cli.install' in modules.json"))
        setContent { CompositionLocalProvider(LocalTabFactory provides factory) { ModulesSection(factory, harnessStatus = status.value) } }
        onNode(hasText("Harness: echo · daemon: refused: missing grant 'harness.cli.install' in modules.json")).assertExists()
        status.value = null
        waitForIdle()
        onNode(hasText("Harness: echo")).assertExists()
    }

    @Test
    fun `pills draw the manifest icon for the module's ids and aliases, built-ins keep their glyph, others the fallback`() = runDesktopComposeUiTest(600, 300) {
        // A community module that also claims a built-in id may not repaint it.
        val spoof = File(Files.createTempDirectory("spoof").toFile(), "Acme_Spoof").apply { mkdirs(); File(this, "x.svg").writeText(File(echoDir, "icons/echo.svg").readText()) }
        val claimsClaude = ModuleStatus(
            "Acme_Spoof", Tier.COMMUNITY, ModuleState.LOADED,
            manifest = PluginJson.decodeFromString(ModuleManifest.serializer(), """{"name":"Acme_Spoof","version":"1.0.0","harness":{"ids":["claude","spoofed"],"icon":"x.svg"}}"""),
            dir = spoof.path,
        )
        val factory = TabFactory(emptyList(), statuses = listOf(echo, claimsClaude))
        setContent {
            CompositionLocalProvider(LocalTabFactory provides factory) {
                androidx.compose.foundation.layout.Row {
                    HarnessIcon("echo")
                    HarnessIcon("echo-agent")
                    HarnessIcon("claude")
                    HarnessIcon("spoofed")
                    HarnessIcon("nobody")
                }
            }
        }
        onNodeWithTag(harnessIconTag("echo")).assert(paints(echoIcon))
        onNodeWithTag(harnessIconTag("echo-agent")).assert(paints(echoIcon))
        onNodeWithTag(harnessIconTag("claude")).assert(paints("claude"))
        onNodeWithTag(harnessIconTag("spoofed")).assert(paints("manifest:" + spoof.path + "/x.svg"))
        onNodeWithTag(harnessIconTag("nobody")).assert(paints(AhqHarnessIcons.FALLBACK))
    }
}
