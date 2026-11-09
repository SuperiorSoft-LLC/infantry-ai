// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.daemon.plugins.CapabilityRegistry
import com.superiorsoftllc.infantry.daemon.plugins.manager.Catalog
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogEntry
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogSource
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogVersion
import com.superiorsoftllc.infantry.daemon.plugins.manager.Fetcher
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.update.ReleaseAssets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLUGIN-UPDATE-LOCK-1: a plugin updates while its old version is loaded. On Windows a jar a class loader holds open cannot
 * be moved, so the update used to fail with "is in use; stop the daemon or the app". Plugin class loaders now read a
 * per-process copy of the jars ([com.superiorsoftllc.infantry.plugin.host.PluginJarShadow]): the install folder is never
 * held, the manager swaps it, and the old version keeps running from its copy until the live swap replaces it.
 */
class PluginUpdateWhileLoadedTest {
    private val tmp = Files.createTempDirectory("plugin-update-lock").toFile()
    private val state = File(tmp, "state").also { it.mkdirs() }
    private val root = File(state, "plugins").also { it.mkdirs() }
    private val repo = File(tmp, "repo").also { it.mkdirs() }
    private val noNetwork = Fetcher { url, _, _ -> throw IOException("offline: $url") }
    private var current: DesktopPlugins.Loaded? = null

    @AfterTest fun cleanup() {
        runCatching { current?.close() }
        tmp.deleteRecursively()
    }

    private fun manager() = PluginManager(root, state, fetcher = noNetwork)

    /** The active install folder of [id] (not a kept version under `_versions`). */
    private fun installed(id: String): File =
        root.walk().first { it.isDirectory && it.name == id && File(it, "module.json").isFile && !it.path.contains("_versions") }

    private fun jar(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("acme/lock.txt")); z.write(text.toByteArray()); z.closeEntry() }
    }.toByteArray()

    private fun pack(version: String): File {
        val f = File(repo, "Acme_Lock-$version.ihq")
        val manifest = """{"name":"Acme_Lock","version":"$version","apiVersion":2,"license":"Apache-2.0","author":"Acme","entry":"x.Y","jar":"lib/Acme_Lock.jar"}"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/Acme_Lock.jar")); z.write(jar(version)); z.closeEntry()
        }
        return f
    }

    @Test
    fun `an update succeeds while the old version's class loader holds its jar open`() {
        val m = manager()
        assertTrue(m.install(pack("1.0.0").path, emptySet()).let { it.ok || error("${it.code}: ${it.message}") })
        val dir = installed("Acme_Lock")
        val loader = PluginClassLoader(dir, File(dir, "lib/Acme_Lock.jar"), javaClass.classLoader, Tier.COMMUNITY)
        try {
            // Reading a resource makes the loader open its jar and keep it open, as a running plugin's loader does.
            assertEquals("1.0.0", loader.getResourceAsStream("acme/lock.txt")!!.use { it.readBytes().decodeToString() })

            val u = m.update("Acme_Lock", ref = pack("1.1.0").path)
            assertTrue(u.ok, "the update applies while the old version is loaded: ${u.code}: ${u.message}")
            assertTrue(File(installed("Acme_Lock"), "module.json").readText().contains("\"1.1.0\""), "the install folder holds 1.1.0")
            // The old version still runs from its own copy until the host swaps it (a lazily loaded class or resource works).
            assertEquals("1.0.0", loader.getResource("acme/lock.txt")!!.openStream().use { it.readBytes().decodeToString() })
        } finally {
            loader.close()
        }
        val copy = loader.shadow.dir
        assertTrue(loader.shadow.shadowed, "the loader reads a copy")
        assertNotEquals(dir.canonicalFile, copy.canonicalFile, "the copy is not the install folder")
        assertFalse(copy.exists(), "closing the loader removes its copy: $copy")
    }

    /**
     * The owner's real case, against the published Infantry_ReadAloud packages (1.1.0 -> 1.2.0) through the same path the
     * Plugins tab takes: a catalog source, the desktop module set holding the loaded 1.1.0, the manager's update, the live
     * swap. Needs the two packages on disk (`INFANTRY_REPRO_READALOUD_DIR/<version>/Infantry_ReadAloud.ihq`, from the
     * community catalog's release urls); skipped without them, so the suite stays offline.
     */
    @Test
    fun `Read Aloud 1_1_0 updates to 1_2_0 while its UI half is loaded`() {
        val src = System.getenv("INFANTRY_REPRO_READALOUD_DIR")?.let(::File)?.takeIf { File(it, "1.2.0/Infantry_ReadAloud.ihq").isFile }
            ?: return println("[skip] INFANTRY_REPRO_READALOUD_DIR not set: the real Read Aloud packages are not on disk")
        val versions = listOf("1.1.0", "1.2.0").map { v ->
            val f = File(src, "$v/Infantry_ReadAloud.ihq").copyTo(File(repo, "Infantry_ReadAloud-$v.ihq"), overwrite = true)
            CatalogVersion(v, grants = listOf("speech.out"), url = f.name, size = f.length(), sha256 = ReleaseAssets.sha256(f))
        }
        val entry = CatalogEntry("Infantry_ReadAloud", "Read chat messages aloud", listOf("voice"), "Apache-2.0", null, versions)
        File(repo, "catalog.json").writeText(kotlinx.serialization.json.Json.encodeToString(Catalog.serializer(), Catalog(id = "community", cls = "community", plugins = listOf(entry))))
        val m = manager()
        assertTrue(m.addSource(CatalogSource("idx", "community", dir = repo.path)).ok)
        m.install("Infantry_ReadAloud@1.1.0", setOf("speech.out")).let { assertTrue(it.ok, "${it.code}: ${it.message}") }
        if (m.status("Infantry_ReadAloud")?.enabled != true) assertTrue(m.setEnabled("Infantry_ReadAloud", true).ok)

        val set = DesktopPlugins.load(state, env = { null }, property = { null }, print = {}).also { current = it }
        val before = set.factory.modules.firstOrNull { it.manifest.name == "Infantry_ReadAloud" }
        assertNotNull(before, "Read Aloud 1.1.0 loaded in the desktop module set: ${set.log}")
        assertEquals("1.1.0", before.manifest.version)

        val u = m.update("Infantry_ReadAloud", grantAll = true)
        println("[repro] update: ok=${u.ok} code=${u.code} message=${u.message}")
        assertTrue(u.ok, "Read Aloud updates while 1.1.0 is loaded: ${u.code}: ${u.message}")

        val swapped = assertIs<DesktopPlugins.Swap.Applied>(set.swap(), "the live swap applies the update")
        current = swapped.next
        assertTrue("Infantry_ReadAloud" in swapped.loaded, swapped.message)
        val after = swapped.next.factory.modules.first { it.manifest.name == "Infantry_ReadAloud" }
        assertEquals("1.2.0", after.manifest.version, "the UI half now runs 1.2.0")
        assertEquals("1.2.0", m.status("Infantry_ReadAloud")?.version)
        // What the daemon's capability list answers after its reload (DaemonPluginHost.remember installs the same declarations).
        val caps = CapabilityRegistry({ 1_000L }, { 1_000L }).apply { install(listOf("Infantry_ReadAloud" to after.manifest)) }
        assertTrue(caps.list().any { it.id == "readaloud.speak" }, "readaloud.speak is declared by 1.2.0: ${caps.list().map { it.id }}")
        println("[repro] Done: ${after.manifest.version}; capabilities ${caps.list().map { it.id }}")
    }
}
