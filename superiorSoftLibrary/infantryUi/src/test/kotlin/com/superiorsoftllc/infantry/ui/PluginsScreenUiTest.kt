// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.plugins.manager.Catalog
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogEntry
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogSource
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogVersion
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManagerPortAdapter
import com.superiorsoftllc.infantry.plugins.official.BROWSE_INSTALL_PREFIX
import com.superiorsoftllc.infantry.plugins.official.CONSENT_INSTALL_TAG
import com.superiorsoftllc.infantry.plugins.official.CONSENT_TAG
import com.superiorsoftllc.infantry.plugins.official.CONSENT_TICK_PREFIX
import com.superiorsoftllc.infantry.plugins.official.CONSENT_WARNING_TAG
import com.superiorsoftllc.infantry.plugins.official.NOT_SANDBOXED_WARNING
import com.superiorsoftllc.infantry.plugins.official.PLUGINS_SECTION_PREFIX
import com.superiorsoftllc.infantry.plugins.official.PluginsScreen
import com.superiorsoftllc.infantry.update.ReleaseAssets
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLUGIN-UI-1: Settings > Plugins, driven Browse -> Install over a real [PluginManager] and a folder (file based) source.
 * The module must end up installed with the ticked grants only.
 */
@OptIn(ExperimentalTestApi::class)
class PluginsScreenUiTest {
    private val tmp = Files.createTempDirectory("plugins-ui-test").toFile()
    private val root = File(tmp, "plugins").also { it.mkdirs() }
    private val state = File(tmp, "state").also { it.mkdirs() }
    private val repo = File(tmp, "repo").also { it.mkdirs() }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun pack(): File {
        val f = File(repo, "Acme_Echo-1.0.0.ihq")
        val manifest = """{"name":"Acme_Echo","version":"1.0.0","apiVersion":2,"license":"Apache-2.0","author":"Acme","entry":"x.Y","jar":"lib/Acme_Echo.jar","daemon":{"entry":"x.D","jar":"lib/Acme_Echo.jar","tools":[{"name":"echo","action":"echo.read"}]}}"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/Acme_Echo.jar")); z.write(ByteArray(300) { it.toByte() }); z.closeEntry()
        }
        return f
    }

    @Test fun `browse then install writes only the ticked grants`() = runDesktopComposeUiTest {
        val pkg = pack()
        val entry = CatalogEntry("Acme_Echo", "test plugin", listOf("test"), "Apache-2.0", null,
            listOf(CatalogVersion("1.0.0", com.superiorsoftllc.infantry.daemon.plugins.manager.ApiRanges(), listOf("daemon.code", "daemon.tool"), pkg.name, pkg.length(), ReleaseAssets.sha256(pkg))))
        File(repo, "catalog.json").writeText(kotlinx.serialization.json.Json.encodeToString(Catalog.serializer(), Catalog(id = "test", cls = "community", plugins = listOf(entry))))
        val manager = PluginManager(root, state)
        val added = manager.addSource(CatalogSource("idx", "community", dir = repo.path)); assertTrue(added.ok, "${added.code}: ${added.message}")

        setContent { MaterialTheme { PluginsScreen(PluginManagerPortAdapter(manager)) } }
        onNodeWithTag(PLUGINS_SECTION_PREFIX + "Browse").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(BROWSE_INSTALL_PREFIX + "Acme_Echo").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(BROWSE_INSTALL_PREFIX + "Acme_Echo").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(CONSENT_TAG).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CONSENT_WARNING_TAG).assertTextEquals(NOT_SANDBOXED_WARNING)
        onNodeWithTag(CONSENT_TICK_PREFIX + "daemon.tool").performClick() // untick one
        onNodeWithTag(CONSENT_INSTALL_TAG).performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(CONSENT_TAG).fetchSemanticsNodes().isEmpty() }
        val row = assertNotNull(manager.status("Acme_Echo"))
        assertEquals(listOf("daemon.code"), row.granted)
        assertEquals(listOf("daemon.code"), com.superiorsoftllc.infantry.plugin.host.PluginFolders.config(root).modules.getValue("Acme_Echo").allow.sorted())
    }
}
