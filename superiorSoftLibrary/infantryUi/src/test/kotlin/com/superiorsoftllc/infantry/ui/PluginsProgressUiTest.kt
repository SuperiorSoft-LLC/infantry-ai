// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.plugins.manager.ApiRanges
import com.superiorsoftllc.infantry.daemon.plugins.manager.Catalog
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogEntry
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogSource
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogVersion
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManagerPortAdapter
import com.superiorsoftllc.infantry.plugin.api.ActionResult
import com.superiorsoftllc.infantry.plugin.api.PluginManagerPort
import com.superiorsoftllc.infantry.plugin.api.PluginProgress
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_NOTE_TAG
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_PROGRESS_TAG
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_RESULT_TAG
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_RETRY_TAG
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_EXPAND_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PLUGIN_UPDATE_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PluginsScreen
import com.superiorsoftllc.infantry.update.ReleaseAssets
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGIN-PROGRESS-1: Settings > Plugins shows each step of an update, the exact reason of a failure with Retry, and applies the Update button end to end. */
@OptIn(ExperimentalTestApi::class)
class PluginsProgressUiTest {
    private val tmp = Files.createTempDirectory("plugins-progress-test").toFile()
    private val root = File(tmp, "plugins").also { it.mkdirs() }
    private val state = File(tmp, "state").also { it.mkdirs() }
    private val repo = File(tmp, "repo").also { it.mkdirs() }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun pack(version: String): File {
        val f = File(repo, "Acme_Echo-$version.ihq")
        val manifest = """{"name":"Acme_Echo","version":"$version","apiVersion":2,"license":"Apache-2.0","author":"Acme","entry":"x.Y","jar":"lib/Acme_Echo.jar"}"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/Acme_Echo.jar")); z.write(ByteArray(300) { it.toByte() }); z.closeEntry()
        }
        return f
    }

    /** A folder catalog offering 1.0.0 and 1.1.0 with 1.0.0 installed; [badHash] corrupts the 1.1.0 pin. */
    private fun setup(badHash: Boolean = false): PluginManager {
        val v = listOf("1.0.0", "1.1.0").map { ver ->
            val p = pack(ver)
            CatalogVersion(ver, ApiRanges(), emptyList(), p.name, p.length(), if (badHash && ver == "1.1.0") "0".repeat(64) else ReleaseAssets.sha256(p))
        }
        File(repo, "catalog.json").writeText(kotlinx.serialization.json.Json.encodeToString(Catalog.serializer(), Catalog(id = "test", cls = "community",
            plugins = listOf(CatalogEntry("Acme_Echo", "test plugin", listOf("test"), "Apache-2.0", null, v)))))
        val manager = PluginManager(root, state)
        assertTrue(manager.addSource(CatalogSource("idx", "community", dir = repo.path)).ok)
        val r = manager.install("idx:Acme_Echo@1.0.0", emptySet())
        assertTrue(r.ok, "${r.code}: ${r.message}")
        return manager
    }

    /** The real adapter; [onUpdate] swaps in a fake slow update that reports through the screen's listener. */
    private class Port(
        val real: PluginManagerPort,
        val onUpdate: ((PluginManagerPort, (PluginProgress) -> Unit) -> ActionResult)? = null,
    ) : PluginManagerPort by real {
        @Volatile var listener: ((PluginProgress) -> Unit)? = null
        val seen = CopyOnWriteArrayList<String>()
        override fun setProgressListener(listener: ((PluginProgress) -> Unit)?) {
            this.listener = listener
            real.setProgressListener(listener?.let { l -> { p -> seen += p.step; l(p) } })
        }
        override fun update(id: String, consent: Set<String>?): ActionResult =
            onUpdate?.invoke(real) { p -> listener?.invoke(p) } ?: real.update(id, consent)
    }

    private fun text(n: SemanticsNode): String =
        (n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty() + " " + n.children.joinToString(" ") { text(it) }).trim()

    private fun ComposeUiTest.has(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.textOf(tag: String) = text(onNodeWithTag(tag).fetchSemanticsNode())

    private fun ComposeUiTest.openRow() {
        waitUntil(timeoutMillis = 15_000) { has(PLUGIN_EXPAND_PREFIX + "Acme_Echo") }
        onNodeWithTag(PLUGIN_EXPAND_PREFIX + "Acme_Echo").performClick()
        waitUntil(timeoutMillis = 15_000) { has(PLUGIN_UPDATE_PREFIX + "Acme_Echo") }
    }

    @Test fun `a slow update shows Downloading then Installing then done`() = runDesktopComposeUiTest {
        val manager = setup()
        val gate1 = CountDownLatch(1); val gate2 = CountDownLatch(1)
        val port = Port(PluginManagerPortAdapter(manager)) { real, report ->
            report(PluginProgress("Acme_Echo", "Downloading", 40, 100)); gate1.await(20, TimeUnit.SECONDS)
            report(PluginProgress("Acme_Echo", "Installing")); gate2.await(20, TimeUnit.SECONDS)
            real.update("Acme_Echo", null)
        }
        setContent { MaterialTheme { PluginsScreen(port) } }
        openRow()
        onNodeWithTag(PLUGIN_UPDATE_PREFIX + "Acme_Echo").performClick()
        waitUntil(timeoutMillis = 15_000) { has(PLUGINS_PROGRESS_TAG) && textOf(PLUGINS_PROGRESS_TAG).contains("Downloading Acme Echo 40%") }
        gate1.countDown()
        waitUntil(timeoutMillis = 15_000) { has(PLUGINS_PROGRESS_TAG) && textOf(PLUGINS_PROGRESS_TAG).contains("Installing") }
        gate2.countDown()
        waitUntil(timeoutMillis = 30_000) { !has(PLUGINS_PROGRESS_TAG) && has(PLUGINS_NOTE_TAG) }
        assertTrue(textOf(PLUGINS_NOTE_TAG).startsWith("Done:"), textOf(PLUGINS_NOTE_TAG))
        assertEquals("1.1.0", manager.status("Acme_Echo")?.version)
    }

    @Test fun `the Update button applies the update and the real manager reports its steps in order`() = runDesktopComposeUiTest {
        val manager = setup()
        val port = Port(PluginManagerPortAdapter(manager))
        setContent { MaterialTheme { PluginsScreen(port) } }
        openRow()
        onNodeWithTag(PLUGIN_UPDATE_PREFIX + "Acme_Echo").performClick()
        waitUntil(timeoutMillis = 30_000) { manager.status("Acme_Echo")?.version == "1.1.0" }
        waitUntil(timeoutMillis = 15_000) { has(PLUGINS_NOTE_TAG) }
        val steps = port.seen.distinct()
        assertEquals(listOf("Downloading", "Verifying", "Installing").filter { it in steps }, steps.filter { it != "Reloading" }, "steps out of order: $steps")
        assertTrue("Downloading" in steps && "Installing" in steps, "steps: $steps")
    }

    @Test fun `a checksum mismatch shows Failed with the exact reason and a Retry`() = runDesktopComposeUiTest {
        val manager = setup(badHash = true)
        setContent { MaterialTheme { PluginsScreen(Port(PluginManagerPortAdapter(manager))) } }
        openRow()
        onNodeWithTag(PLUGIN_UPDATE_PREFIX + "Acme_Echo").performClick()
        waitUntil(timeoutMillis = 30_000) { has(PLUGINS_RESULT_TAG) }
        val shown = textOf(PLUGINS_RESULT_TAG)
        assertTrue(shown.contains("Failed:") && shown.contains("sha256"), shown)
        onNodeWithTag(PLUGINS_RETRY_TAG).assertExists()
        assertEquals("1.0.0", manager.status("Acme_Echo")?.version)
    }
}
