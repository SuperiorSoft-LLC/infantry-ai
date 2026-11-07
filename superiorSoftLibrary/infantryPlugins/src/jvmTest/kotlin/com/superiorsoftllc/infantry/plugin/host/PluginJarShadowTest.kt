// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PLUGIN-UPDATE-LOCK-1: a plugin class loader reads a copy of its jars, so the module folder can be moved (the plugin
 * manager's update, rollback and remove) while the loader is open, also on Windows where an open jar is locked.
 */
class PluginJarShadowTest {
    private val tmp = Files.createTempDirectory("jar-shadow").toFile()
    private val module = File(tmp, "plugins/community/Acme_Shadow").also { File(it, "lib").mkdirs() }
    private val jar = File(module, "lib/Acme_Shadow.jar")
    private val previousBase = PluginJarShadow.base

    init {
        PluginJarShadow.base = File(tmp, "shadow")
        ZipOutputStream(jar.outputStream()).use { z -> z.putNextEntry(ZipEntry("acme/v.txt")); z.write("1".toByteArray()); z.closeEntry() }
    }

    @AfterTest fun cleanup() {
        PluginJarShadow.base = previousBase
        tmp.deleteRecursively()
    }

    @Test
    fun `the module folder moves while the loader is open, and the loader keeps reading its copy`() {
        // The module's own jar is listed twice (as the jar and under lib/): it is copied once.
        val loader = ShadowJarClassLoader("plugin-Acme_Shadow", module, listOf(jar, jar), null)
        assertEquals("1", loader.getResourceAsStream("acme/v.txt")!!.use { it.readBytes().decodeToString() })
        assertTrue(loader.shadow.shadowed)
        assertEquals(1, loader.urLs.size)
        assertTrue(loader.shadow.dir.startsWith(PluginJarShadow.base), "the copy is under the shadow base: ${loader.shadow.dir}")
        assertEquals(File(loader.shadow.dir, "lib/Acme_Shadow.jar"), loader.shadow.files.single(), "the copy keeps the path relative to the module")

        val kept = File(tmp, "plugins/_versions/Acme_Shadow/1.0.0").also { it.parentFile.mkdirs() }
        Files.move(module.toPath(), kept.toPath(), StandardCopyOption.ATOMIC_MOVE) // what the manager's swapOut does
        assertFalse(module.exists())
        assertEquals("1", loader.getResource("acme/v.txt")!!.openStream().use { it.readBytes().decodeToString() })

        val copy = loader.shadow.dir
        loader.close()
        assertFalse(copy.exists(), "closing the loader deletes its copy")
    }

    @Test
    fun `a native library is loaded from a copy beside the jars`() {
        val native = File(module, "native/acme.dll").also { it.parentFile.mkdirs(); it.writeText("lib") }
        File(module, "native/dep.dll").writeText("dep")
        val shadow = PluginJarShadow.of(module, listOf(jar), "Acme_Shadow")
        try {
            val copied = shadow.native(native)
            assertEquals(File(shadow.dir, "native/acme.dll"), copied)
            assertTrue(File(shadow.dir, "native/dep.dll").isFile, "the library's neighbours come along (its own dependencies)")
            File(tmp, "elsewhere.dll").let { outside -> assertEquals(outside, shadow.native(outside), "a file outside the module folder is not copied") }
        } finally {
            shadow.close()
        }
    }

    @Test
    fun `a dead process's copies are removed, a live one's are kept`() {
        val dead = File(PluginJarShadow.base, Long.MAX_VALUE.toString()).also { File(it, "Old-1").mkdirs() }
        val live = File(PluginJarShadow.base, ProcessHandle.current().parent().map { it.pid() }.orElse(ProcessHandle.current().pid()).toString())
            .also { File(it, "Running-1").mkdirs() }
        PluginJarShadow.of(module, listOf(jar), "Acme_Shadow").close()
        assertFalse(dead.exists(), "the folder of a process that has ended is swept")
        assertTrue(live.exists(), "a running process's copies are never touched")
    }

    @Test
    fun `without a place to copy to, the original files are read as before`() {
        PluginJarShadow.base = File(tmp, "not-a-folder").also { it.writeText("x") }
        val shadow = PluginJarShadow.of(module, listOf(jar), "Acme_Shadow")
        assertFalse(shadow.shadowed)
        assertEquals(listOf(jar), shadow.files)
        shadow.close()
        assertTrue(jar.isFile, "closing an unshadowed set never deletes the originals")
    }
}
