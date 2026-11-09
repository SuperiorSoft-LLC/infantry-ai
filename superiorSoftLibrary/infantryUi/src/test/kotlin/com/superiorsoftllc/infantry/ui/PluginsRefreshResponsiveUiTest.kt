// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogSource
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManagerPortAdapter
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_SECTION_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PluginsScreen
import com.superiorsoftllc.infantry.plugins.official.SOURCE_REFRESH_TAG
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** BUG PLUGIN-SOURCE-REFRESH-FREEZE-1: pressing Sources > Refresh with a dead source (real core downloader, retries) keeps the screen answering. */
@OptIn(ExperimentalTestApi::class)
class PluginsRefreshResponsiveUiTest {
    private val tmp = Files.createTempDirectory("plugins-refresh-test").toFile()
    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test fun `a dead source during Refresh does not stall the screen`() = runDesktopComposeUiTest {
        val root = File(tmp, "plugins").also { it.mkdirs() }; val state = File(tmp, "state").also { it.mkdirs() }
        val center = com.superiorsoftllc.infantry.plugin.host.ProgressCenter(clock = System::currentTimeMillis, minIntervalMs = { 0 })
        val transport = com.superiorsoftllc.infantry.daemon.api.downloads.DownloadTransport { _, _, _, _ -> Thread.sleep(2_000); throw IOException("connection refused") }
        val downloads = com.superiorsoftllc.infantry.downloads.CoreDownloads(progress = { center.forSource(it) }, coreTransport = transport)
        val m = PluginManager(root, state, coreDownloads = downloads.forSource("plugins"), progressSystem = center.forSource("plugins"))
        m.setSources(emptyList())
        for (i in 1..3) assertTrue(m.addSource(CatalogSource("dead$i", "community", url = "https://example.invalid/$i/c.json")).ok)
        setContent { MaterialTheme { PluginsScreen(PluginManagerPortAdapter(m)) } }
        onNodeWithTag(PLUGINS_SECTION_PREFIX + "Sources").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(SOURCE_REFRESH_TAG).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(SOURCE_REFRESH_TAG).performClick()
        val t0 = System.nanoTime(); var worst = 0L
        repeat(10) {
            val a = System.nanoTime(); waitForIdle(); onNodeWithTag(PLUGINS_SECTION_PREFIX + "Browse").assertExists()
            worst = maxOf(worst, (System.nanoTime() - a) / 1_000_000); Thread.sleep(300)
        }
        println("REFRESH-RESPONSIVE worst UI round trip ms=$worst total ms=${(System.nanoTime() - t0) / 1_000_000}")
        if (worst > 3_000) { Thread.getAllStackTraces().forEach { (t, st) -> println("THREAD ${t.name} ${t.state}\n" + st.take(12).joinToString("\n") { "  at $it" }) } }
        assertTrue(worst < 3_000, "UI round trip $worst ms during Refresh")
    }

    @Test fun `the real transport and the real community catalog do not stall the screen`() = runDesktopComposeUiTest {
        val root = File(tmp, "plugins2").also { it.mkdirs() }; val state = File(tmp, "state2").also { it.mkdirs() }
        val center = com.superiorsoftllc.infantry.plugin.host.ProgressCenter(clock = System::currentTimeMillis, minIntervalMs = { 0 })
        val downloads = com.superiorsoftllc.infantry.downloads.CoreDownloads(progress = { center.forSource(it) })
        val m = PluginManager(root, state, coreDownloads = downloads.forSource("plugins"), progressSystem = center.forSource("plugins"))
        println("SOURCES " + m.sources().joinToString { it.id + "=" + (it.url ?: it.dir) })
        setContent { MaterialTheme { PluginsScreen(PluginManagerPortAdapter(m)) } }
        onNodeWithTag(PLUGINS_SECTION_PREFIX + "Sources").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(SOURCE_REFRESH_TAG).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(SOURCE_REFRESH_TAG).performClick()
        var worst = 0L
        repeat(25) {
            val a = System.nanoTime(); waitForIdle(); onNodeWithTag(PLUGINS_SECTION_PREFIX + "Browse").assertExists()
            worst = maxOf(worst, (System.nanoTime() - a) / 1_000_000); Thread.sleep(300)
        }
        println("REAL-REFRESH worst UI round trip ms=$worst")
        if (worst > 3_000) { Thread.getAllStackTraces().forEach { (t, st) -> println("THREAD " + t.name + " " + t.state + " " + st.take(12).joinToString(" | ")) } }
        assertTrue(worst < 3_000, "UI round trip $worst ms during Refresh")
    }
}
