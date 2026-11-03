// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Events
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.ui.model.AppState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * HOOKS-1: the desktop loader over a real UiPlugin jar. The example community module (plugins/community/Acme_Hello) is
 * built by its own Gradle project and handed over as `acme.hello.dir`; its classes are not on this test's classpath, so
 * only the jar can provide them.
 */
class DesktopPluginsJarTest {
    private val state = Files.createTempDirectory("plugins-jar-state").toFile()
    private val root = File(state, "plugins")

    @Test
    fun `a real UiPlugin jar loads, its tab registers, its interceptor and observer run`() {
        val source = File(System.getProperty("acme.hello.dir") ?: error("acme.hello.dir not set: run through Gradle jvmTest"))
        val module = File(root, "community/Acme_Hello").apply { mkdirs() }
        File(source, "module.json").copyTo(File(module, "module.json"))
        File(source, "build/libs/Acme_Hello.jar").copyTo(File(module, "build/libs/Acme_Hello.jar"))
        File(root, "modules.json").writeText("""{"modules":{"Acme_Hello":{"enabled":true,"allow":["events","storage","daemon","notifications"]}}}""")
        assertFailsWith<ClassNotFoundException>("the plugin class must come from the jar only") { Class.forName("com.acme.hello.HelloPlugin") }

        val loaded = DesktopPlugins.load(state, env = { null }, property = { null }, print = {})
        val f = loaded.factory
        val status = f.statuses.single { it.name == "Acme_Hello" }
        assertEquals(ModuleState.LOADED, status.state, status.reason)
        assertEquals(Tier.COMMUNITY, status.tier)

        val hello = f.find("acme.hello")
        assertEquals("Acme_Hello", hello?.module, "the jar's tab registers")
        assertEquals("Hello", f.title(hello!!, AppState()))
        assertEquals(TabContainers.RIGHT, f.find("acme.hello.side")?.container)
        assertEquals("Epics +hi", f.title(f.find("infantry.epics")!!, AppState()), "the jar's around interceptor wraps the core Epics title")

        f.events.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to "acme.hello")))
        assertEquals("1", File(DesktopPlugins.dataDir(state, "Acme_Hello"), "opened.txt").readText(), "the jar's observer ran and wrote its own storage")
        assertTrue(loaded.log.any { "Acme_Hello (community): loaded" in it }, loaded.log.joinToString("\n"))
    }

    @Test
    fun `closing a loaded set unsubscribes its observers and lets go of the jar, so it can be deleted`() {
        val source = File(System.getProperty("acme.hello.dir") ?: error("acme.hello.dir not set: run through Gradle jvmTest"))
        val module = File(root, "community/Acme_Hello").apply { mkdirs() }
        File(source, "module.json").copyTo(File(module, "module.json"))
        File(source, "build/libs/Acme_Hello.jar").copyTo(File(module, "build/libs/Acme_Hello.jar"))
        File(root, "modules.json").writeText("""{"modules":{"Acme_Hello":{"enabled":true,"allow":["events","storage","daemon","notifications"]}}}""")
        val opened = File(DesktopPlugins.dataDir(state, "Acme_Hello"), "opened.txt")
        fun tabOpened(f: TabFactory) = f.events.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to "acme.hello")))

        val first = DesktopPlugins.load(state, env = { null }, property = { null }, print = {})
        tabOpened(first.factory)
        assertEquals("1", opened.readText())
        // The soft restart: a second set is loaded, then the first is closed. Only the second still hears events.
        val second = DesktopPlugins.load(state, env = { null }, property = { null }, print = {})
        first.close()
        first.close() // twice is harmless
        tabOpened(first.factory)
        assertEquals("1", opened.readText(), "the closed set's observer no longer runs")
        tabOpened(second.factory)
        assertEquals("2", opened.readText(), "the new set's observer counts once, not twice")
        second.close()
        assertTrue(module.deleteRecursively(), "no class loader keeps the jar open")
    }
}
