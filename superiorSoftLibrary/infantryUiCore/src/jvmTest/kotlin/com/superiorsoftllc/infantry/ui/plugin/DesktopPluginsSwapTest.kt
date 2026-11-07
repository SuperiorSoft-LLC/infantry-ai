// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Events
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugin.host.LeakWatch
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PLUGIN-HOTSWAP-1: one UI plugin (the built Acme_Hello jar, as a deployment loads it) is disabled, enabled, updated and
 * removed while the set runs. Only that plugin unloads and loads; every other module keeps its very objects and generation,
 * and the old class loader is collected.
 */
class DesktopPluginsSwapTest {
    private val state = Files.createTempDirectory("plugins-swap-state").toFile()
    private val root = File(state, "plugins")
    private val module = File(root, "community/Acme_Hello")
    private val opened get() = File(DesktopPlugins.dataDir(state, "Acme_Hello"), "opened.txt")
    /** The set the window would draw now. Like the host, the test keeps only the current set: an old one would hold the old plugin. */
    private var current: DesktopPlugins.Loaded? = null

    @AfterTest fun cleanup() {
        runCatching { current?.close() }
        state.deleteRecursively()
    }

    private fun install() {
        val source = File(System.getProperty("acme.hello.dir") ?: error("acme.hello.dir not set: run through Gradle jvmTest"))
        module.mkdirs()
        File(source, "module.json").copyTo(File(module, "module.json"), overwrite = true)
        File(source, "build/libs/Acme_Hello.jar").copyTo(File(module, "build/libs/Acme_Hello.jar"), overwrite = true)
    }

    private fun enable(on: Boolean) =
        File(root, "modules.json").writeText("""{"modules":{"Acme_Hello":{"enabled":$on,"allow":["events","storage","daemon","notifications"]}}}""")

    private fun load() = DesktopPlugins.load(state, env = { null }, property = { null }, print = {}).also { current = it }

    private val factory get() = current!!.factory

    /** What one swap did, as plain data (no plugin object), plus the weak reference to Acme_Hello's closed loader. */
    private class Step(val unloaded: List<String>, val loaded: List<String>, val released: java.lang.ref.WeakReference<ClassLoader>?)

    /** Swaps the current set and keeps only the next one, as JvmPlatformHost does. */
    private fun swap(): Step {
        val r = assertIs<DesktopPlugins.Swap.Applied>(current!!.swap(), "swap")
        current = r.next
        return Step(r.unloaded, r.loaded, r.released["Acme_Hello"])
    }

    private fun generations() = factory.modules.associate { it.manifest.name to factory.generation(it.manifest.name) }

    private fun helloId() = factory.modules.singleOrNull { it.manifest.name == "Acme_Hello" }?.let { System.identityHashCode(it) }

    private fun TabFactory.tabOpened() = events.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to "acme.hello")))

    private fun TabFactory.others() = modules.filter { it.manifest.name != "Acme_Hello" }

    private fun opens() = if (opened.isFile) opened.readText().toInt() else 0

    /** The labels of the Acme_Hello config fields the factory's compiled config tree holds on [node]. */
    private fun TabFactory.labels(node: String = "acme.hello.greeting") =
        configTree.fields[node].orEmpty().filter { it.module == "Acme_Hello" }.map { it.field.label }

    @Test
    fun `when nothing changed the swap does nothing`() {
        install(); enable(true)
        val first = load()
        val r = assertIs<DesktopPlugins.Swap.Unsupported>(first.swap())
        assertTrue("nothing changed" in r.reason, r.reason)
    }

    @Test
    fun `disable, enable, update and remove one plugin live while the others keep their objects`() {
        install(); enable(true)
        load()
        assertNotNull(factory.find("acme.hello"))
        val others = factory.others() // compiled-in core modules: holding them holds no plugin loader
        val gen0 = generations()
        factory.tabOpened()
        assertEquals(1, opens())
        val labels0 = factory.labels()
        assertTrue("Custom message" in labels0, labels0.toString())

        // Disable: only Acme_Hello unloads; its loader is collected; its observer is gone; core modules are the same objects.
        enable(false)
        val off = swap()
        assertEquals(listOf("Acme_Hello"), off.unloaded)
        assertEquals(emptyList(), off.loaded)
        assertNull(factory.find("acme.hello"), "its tab left the window")
        factory.others().zip(others).forEach { (a, b) -> assertSame(b, a, "${b.manifest.name} was not reloaded") }
        factory.others().forEach { assertEquals(gen0[it.manifest.name], factory.generation(it.manifest.name)) }
        assertTrue(LeakWatch.collected(off.released!!, 10_000), "the old class loader is collected")
        factory.tabOpened()
        assertEquals(1, opens(), "the unloaded observer does not run")
        assertEquals(emptyList(), factory.labels(), "its config nodes left the compiled tree")
        assertTrue(factory.configTree.fields.isNotEmpty(), "core's config nodes stay")

        // Enable: it loads again with a new generation; its observer counts once (no duplicate from the kept set).
        enable(true)
        val on = swap()
        assertEquals(listOf("Acme_Hello"), on.loaded)
        assertNotNull(factory.find("acme.hello"))
        assertEquals(ModuleState.LOADED, factory.statuses.single { it.name == "Acme_Hello" }.state)
        assertTrue(factory.generation("Acme_Hello") > gen0.getValue("Acme_Hello"), "a new composition for the new version")
        factory.others().zip(others).forEach { (a, b) -> assertSame(b, a) }
        factory.tabOpened()
        assertEquals(2, opens(), "one observer, counted once")
        assertEquals(labels0, factory.labels(), "its config nodes are back")
        val idOn = helloId()
        val genOn = factory.generation("Acme_Hello")

        // Update: a rebuilt jar and a changed module.json (new fingerprint) reload only this plugin.
        val jar = File(module, "build/libs/Acme_Hello.jar")
        jar.setLastModified(jar.lastModified() + 10_000)
        val manifest = File(module, "module.json")
        manifest.writeText(manifest.readText().replace("\"label\": \"Custom message\"", "\"label\": \"Swapped message\""))
        val up = swap()
        assertEquals(listOf("Acme_Hello"), up.unloaded)
        assertEquals(listOf("Acme_Hello"), up.loaded)
        assertNotEquals(idOn, helloId(), "a new module object")
        assertNotEquals(genOn, factory.generation("Acme_Hello"))
        factory.others().zip(others).forEach { (a, b) -> assertSame(b, a) }
        assertTrue(LeakWatch.collected(up.released!!, 10_000), "the replaced version's class loader is collected")
        factory.tabOpened()
        assertEquals(3, opens(), "the new version's observer runs once, the old one not at all")
        val labels3 = factory.labels()
        assertTrue("Swapped message" in labels3 && "Custom message" !in labels3, "its config nodes are replaced: $labels3")

        // Remove: disabled, it unloads; the jar is not locked, so the folder can go; then nothing is left to change.
        enable(false)
        val gone = swap()
        assertTrue(LeakWatch.collected(gone.released!!, 10_000))
        assertTrue(module.deleteRecursively(), "no class loader keeps the jar open")
        val after = assertIs<DesktopPlugins.Swap.Unsupported>(current!!.swap(), "a removed, disabled plugin is no change")
        assertFalse(after.reason.isBlank())
    }
}
