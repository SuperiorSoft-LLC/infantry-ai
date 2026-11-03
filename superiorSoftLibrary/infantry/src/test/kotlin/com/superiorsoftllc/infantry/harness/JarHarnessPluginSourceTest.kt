// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** HARNESS-PLUGIN-1 step H1: a harness plugin loaded from a jar outside the class path, behind the same registry seam. */
class JarHarnessPluginSourceTest {

    private val prefix = JarSourceTestPlugin::class.java.name
    private val services = "META-INF/services/${HarnessPlugin::class.java.name}"

    /** The test class path minus [JarSourceTestPlugin]: the plugin can only come from the jar. */
    private val parent = object : ClassLoader("hides-jar-plugin", JarHarnessPluginSourceTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(prefix)) throw ClassNotFoundException("$name is hidden from the parent")
            return super.loadClass(name, resolve)
        }
    }

    private fun dir(): File = Files.createTempDirectory("harness-jars").toFile().apply { deleteOnExit() }

    /** Packs every compiled `JarSourceTestPlugin*` class, plus [servicesLine] as the services entry when not null. */
    private fun jar(folder: File, name: String, servicesLine: String? = prefix): File {
        val path = prefix.replace('.', '/')
        val classDir = File(JarSourceTestPlugin::class.java.getResource("/$path.class")!!.toURI()).parentFile
        val simple = JarSourceTestPlugin::class.java.simpleName
        val out = File(folder, name)
        JarOutputStream(out.outputStream()).use { j ->
            classDir.listFiles { f -> f.name.startsWith(simple) && f.name.endsWith(".class") }!!.forEach { f ->
                j.putNextEntry(JarEntry("${path.substringBeforeLast('/')}/${f.name}"))
                j.write(f.readBytes())
                j.closeEntry()
            }
            if (servicesLine != null) {
                j.putNextEntry(JarEntry(services))
                j.write((servicesLine + "\n").toByteArray())
                j.closeEntry()
            }
        }
        return out
    }

    @Test
    fun `a plugin in a jar outside the class path registers and runs`() = runBlocking {
        val folder = dir()
        jar(folder, "echo.jar")
        val registry = HarnessRegistry(onFailure = {})

        val report = registry.discover(JarHarnessPluginSource.folder(folder, parent))

        assertEquals(listOf(JarSourceTestPlugin.HARNESS_ID), report.accepted.single { it.pluginId == JarSourceTestPlugin.PLUGIN_ID }.harnessIds)
        val harness = registry.resolve(JarSourceTestPlugin.HARNESS_ID)!!
        assertIs<URLClassLoader>(harness.javaClass.classLoader)
        assertNotSame(JarSourceTestPlugin::class.java, harness.javaClass.enclosingClass, "loaded from the jar, not the class path")
        val events = registry.run(HarnessRequest("hi", JarSourceTestPlugin.HARNESS_ID, "proj")).toList()
        assertEquals(HarnessEvent.TextDelta("from-jar:hi"), events.first())
    }

    @Test
    fun `only the jar's own services entries count, never the parent's class path plugins`() {
        val folder = dir()
        jar(folder, "echo.jar")
        val labels = JarHarnessPluginSource.folder(folder, parent).candidates().map { it.label }
        assertEquals(listOf("jar:echo.jar:$prefix"), labels)
    }

    @Test
    fun `each jar loads on its own class loader`() {
        val folder = dir()
        jar(folder, "a.jar")
        jar(folder, "b.jar")
        val plugins = JarHarnessPluginSource.folder(folder, parent).candidates().map { it.create() }
        assertEquals(2, plugins.size)
        assertNotSame(plugins[0].javaClass.classLoader, plugins[1].javaClass.classLoader)
        // Same plugin id twice: the registry refuses the second, the first stays.
        val registry = HarnessRegistry(onFailure = {})
        val report = registry.discover(JarHarnessPluginSource.folder(folder, parent))
        assertEquals(1, report.accepted.size)
        assertEquals(RefusalReason.DUPLICATE_PLUGIN, report.refused.single().reason)
    }

    @Test
    fun `a jar without a services entry or with a broken one is a failure, and does not hide the others`() {
        val folder = dir()
        jar(folder, "a-empty.jar", servicesLine = null)
        jar(folder, "b-broken.jar", servicesLine = "com.example.DoesNotExist")
        jar(folder, "c-good.jar")
        val failures = mutableListOf<PluginFailure>()
        val registry = HarnessRegistry(onFailure = { failures += it })

        val report = registry.discover(JarHarnessPluginSource.folder(folder, parent))

        assertEquals(listOf(JarSourceTestPlugin.PLUGIN_ID), report.accepted.map { it.pluginId })
        assertEquals(listOf("jar:a-empty.jar", "jar:b-broken.jar"), report.failed.map { it.failure.plugin })
        assertTrue(failures[0].cause.message!!.contains("lists no HarnessPlugin"), failures[0].message)
        assertTrue(failures.all { it.phase == PluginFailurePhase.INSTANTIATE })
    }

    @Test
    fun `a missing or empty folder yields no candidates`() {
        assertEquals(emptyList(), JarHarnessPluginSource.folder(File(dir(), "absent")).candidates())
        assertEquals(emptyList(), JarHarnessPluginSource.folder(dir()).candidates())
    }
}
