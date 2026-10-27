package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.Events
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.ui.model.AppState
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
}
