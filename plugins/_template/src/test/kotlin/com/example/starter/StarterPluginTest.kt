package com.example.starter

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.PluginJson
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.plugin.NoPluginServices
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.registerModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The module registers exactly what its module.json declares, and the app's factory accepts all of it. */
class StarterPluginTest {
    // Gradle runs a module's tests in its own folder, where module.json sits.
    private val manifest = PluginJson.decodeFromString(ModuleManifest.serializer(), File("module.json").readText())

    @Test
    fun `the tab and the interceptor are declared, registered and bound`() {
        val module = registerModule(manifest, Tier.COMMUNITY, NoPluginServices, StarterPlugin())
        val factory = TabFactory(listOf(module))
        assertEquals(emptyList(), factory.problems)
        val tab = factory.tabs(TabContainers.CENTER).single()
        assertEquals("example.starter", tab.id)
        assertEquals("Starter (0)", factory.title(tab, AppState()), "the interceptor wraps the title")
    }
}
