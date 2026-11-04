// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.example.starter

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import com.superiorsoftllc.infantry.ui.plugin.NoPluginServices
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.registerModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// The UI half registers exactly what module.json declares, and the app's factory accepts all of it.
class StarterPluginTest {
    // Gradle runs a module's tests in its own folder, where module.json sits.
    private val manifest = PluginJson.decodeFromString(ModuleManifest.serializer(), File("module.json").readText())

    @Test
    fun `the tab is declared, registered and bound`() {
        val module = registerModule(manifest, Tier.COMMUNITY, NoPluginServices, StarterPlugin())
        val factory = TabFactory(listOf(module))
        assertEquals(emptyList(), factory.problems)
        assertEquals("example.starter", factory.tabs(TabContainers.CENTER).single().id)
    }
}
