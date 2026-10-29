package com.acme.hello

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.PluginJson
import com.superiorsoftllc.agenthq.ui.plugin.NoPluginServices
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.registerModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** EXAMPLE-1: module.json is what the desktop reads; the compiled-in Kotlin config must say exactly the same. */
class AcmeHelloConfigTest {
    // Gradle runs a module's tests in its own folder, where module.json sits.
    private val manifest = PluginJson.decodeFromString(ModuleManifest.serializer(), File("module.json").readText())

    @Test
    fun `the compiled-in config equals module json's config`() {
        assertEquals(manifest.config, AcmeHelloConfig.DECLARATIONS)
    }

    @Test
    fun `the greeting block lands in the left board slot and its config node is the module's own`() {
        val factory = TabFactory(listOf(registerModule(manifest, Tier.COMMUNITY, NoPluginServices, HelloPlugin())))
        assertEquals(listOf(AcmeHelloConfig.GREETING_NODE), factory.panels(Panels.LEFT_BOARD).map { it.id })
        assertEquals(emptyList(), factory.configTree.problems.filter { AcmeHelloConfig.GREETING_NODE in it })
        assertEquals(listOf("greeting", "message", "shout", "classes"), factory.configTree.fields[AcmeHelloConfig.GREETING_NODE]?.map { it.key })
    }

    @Test
    fun `the printed text follows greeting, message and shout`() {
        assertEquals("Hello World", greetingText(null, null, false))
        assertEquals("Hello Nation", greetingText("Hello Nation", "  ", false))
        assertEquals("HELLO NATION, FRIENDS", greetingText("Hello Nation", "friends", true))
    }
}
