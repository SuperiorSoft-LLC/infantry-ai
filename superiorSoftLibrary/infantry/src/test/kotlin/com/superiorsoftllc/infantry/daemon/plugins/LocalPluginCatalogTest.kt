// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.attention.OwnerAsks
import com.superiorsoftllc.infantry.daemon.plugins.manager.ApiRanges
import com.superiorsoftllc.infantry.daemon.plugins.manager.Catalog
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogEntry
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogSource
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogVersion
import com.superiorsoftllc.infantry.daemon.plugins.manager.ManagerJson
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import com.superiorsoftllc.infantry.update.ReleaseAssets
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLUGIN-MCP-1: the plugin manager read seam and the owner-gated request, on a temp root with a folder catalog. */
class LocalPluginCatalogTest {
    private val tmp = Files.createTempDirectory("lpc-test").toFile()
    private val root = File(tmp, "plugins").also { it.mkdirs() }
    private val state = File(tmp, "state").also { it.mkdirs() }
    private val repo = File(tmp, "repo").also { it.mkdirs() }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private class FakeAsks : OwnerAsks {
        class Asked(val session: String, val text: String, val options: List<Pair<String, String>>, val id: String)
        val asked = mutableListOf<Asked>()
        private val listeners = mutableListOf<(String, String?) -> Unit>()
        override fun ask(sessionId: String, question: String, options: List<Pair<String, String>>, recommended: String, reason: String): String =
            "q${asked.size + 1}".also { asked += Asked(sessionId, question, options, it) }
        override fun onResolved(listener: (questionId: String, answer: String?) -> Unit): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners -= listener }
        }
        fun resolve(id: String, answer: String?) = listeners.toList().forEach { it(id, answer) }
    }

    private fun setup(): PluginManager {
        val f = File(repo, "Acme_Echo-1.0.0.ihq")
        val manifest = """{"name":"Acme_Echo","version":"1.0.0","apiVersion":2,"license":"Apache-2.0","entry":"x.Y","jar":"lib/Acme_Echo.jar","daemon":{"entry":"x.D","jar":"lib/Acme_Echo.jar","tools":[{"name":"echo","action":"echo.read"}]}}"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/Acme_Echo.jar")); z.write(ByteArray(300) { it.toByte() }); z.closeEntry()
        }
        val entry = CatalogEntry("Acme_Echo", "test plugin", listOf("test"), "Apache-2.0", null,
            listOf(CatalogVersion("1.0.0", ApiRanges(), listOf("daemon.code", "daemon.tool"), f.name, f.length(), ReleaseAssets.sha256(f))))
        File(repo, "catalog.json").writeText(ManagerJson.encodeToString(Catalog.serializer(), Catalog(id = "test", cls = "community", plugins = listOf(entry))))
        return PluginManager(root, state).also { assertTrue(it.addSource(CatalogSource("idx", "community", dir = repo.path)).ok) }
    }

    @Test fun `search and show read the catalog and install nothing`() {
        val reader = LocalPluginCatalog(setup(), { null })
        val hit = reader.search("echo").single()
        assertEquals("Acme_Echo", hit.id)
        assertEquals(listOf("daemon.code", "daemon.tool"), hit.grants)
        assertEquals(emptyList(), reader.search("echo", tag = "nope"))
        val shown = reader.show("Acme_Echo")
        assertNull(shown.problem)
        assertEquals(listOf("daemon.code", "daemon.tool"), shown.grants)
        assertNull(reader.status("Acme_Echo"))
    }

    @Test fun `request asks the owner with the grants and installs nothing until answered`() {
        val asks = FakeAsks()
        val manager = setup()
        val reader = LocalPluginCatalog(manager, { asks })
        val r = reader.request("Acme_Echo", "need echo", "s1")
        assertEquals("q1", r.questionId)
        val q = asks.asked.single()
        assertEquals("s1", q.session)
        assertTrue("daemon.code, daemon.tool" in q.text, q.text)
        assertTrue("Licence: Apache-2.0" in q.text && "Class: community" in q.text && "Signer:" in q.text, q.text)
        assertEquals(listOf("Install with these grants", "Do not install"), q.options.map { it.first })
        assertNull(manager.status("Acme_Echo"), "nothing is installed before the owner answers")

        asks.resolve("q1", "Do not install")
        assertNull(manager.status("Acme_Echo"), "declined = nothing installed")
    }

    @Test fun `the owner's yes installs exactly the shown grants`() {
        val asks = FakeAsks()
        val manager = setup()
        val reader = LocalPluginCatalog(manager, { asks })
        reader.request("Acme_Echo", "need echo", "s1")
        asks.resolve("q1", "Install with these grants")
        val row = assertNotNull(manager.status("Acme_Echo"))
        assertEquals(listOf("daemon.code", "daemon.tool"), row.granted)
    }

    @Test fun `request without a session or without the question service raises nothing`() {
        val asks = FakeAsks()
        assertNull(LocalPluginCatalog(setup(), { asks }).request("Acme_Echo", "x", null).questionId)
        assertNull(LocalPluginCatalog(setup(), { null }).request("Acme_Echo", "x", "s1").questionId)
        assertTrue(asks.asked.isEmpty())
    }
}
